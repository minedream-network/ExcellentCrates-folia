package su.nightexpress.excellentcrates.opening.world.impl;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.opening.world.WorldOpening;
import su.nightexpress.excellentcrates.user.OpeningSoundMode;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.bridge.paper.scheduler.FoliaScheduler;
import su.nightexpress.nightcore.util.EntityUtil;
import su.nightexpress.nightcore.util.LocationUtil;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleRollOpening extends WorldOpening {

    private static final String DISPLAY_TAG = "excellentcrates_reward_display";

    private final int    spinsRequired;
    private final long   spinInterval;
    private final long   initialFinishDelay;
    private final UUID   playerId;
    private final FoliaScheduler visualScheduler;
    private final AtomicBoolean  visualInitPending;
    private final AtomicBoolean  visualUpdatePending;
    private final AtomicInteger  visualGeneration;
    private final String         displayTag;

    private Reward reward;

    private long spinCount;
    private long finishDelay;
    private long animationTick;

    private volatile Item     rewardDisplay;
    private volatile UUID     rewardDisplayId;
    private volatile Location displayLocation;
    private volatile boolean  visualReady;
    private volatile DisplaySnapshot pendingDisplay;

    public SimpleRollOpening(@NotNull CratesPlugin plugin,
                             @NotNull Player player,
                             @NotNull CrateSource source,
                             @Nullable Cost cost,
                             int spinsRequired,
                             long spinInterval,
                             long finishDelay) {
        super(plugin, player, source, cost);
        this.spinsRequired = spinsRequired;
        this.spinInterval = spinInterval;
        this.initialFinishDelay = finishDelay;
        this.finishDelay = finishDelay;
        this.playerId = player.getUniqueId();
        this.visualScheduler = new FoliaScheduler(plugin);
        this.visualInitPending = new AtomicBoolean();
        this.visualUpdatePending = new AtomicBoolean();
        this.visualGeneration = new AtomicInteger();
        this.displayTag = DISPLAY_TAG + "_" + player.getUniqueId().toString().replace("-", "");

        this.reward = source.getCrate().rollRewardReserved(player);
        this.addReward(this.reward);
    }

    private void onFirstTick() {
        if (!this.visualInitPending.compareAndSet(false, true)) return;

        WorldPos blockPos = this.source.getBlockPos();
        if (blockPos == null) {
            Location playerLoc = this.player.getEyeLocation().clone();
            Vector direction = playerLoc.getDirection();

            for (int i = 0; i < 3; i++) {
                playerLoc.add(direction);
            }

            Location center = LocationUtil.setCenter3D(playerLoc);
            this.displayLocation = center;
            int generation = this.visualGeneration.get();
            var task = this.visualScheduler.runTask(center, () -> {
                try {
                    if (generation != this.visualGeneration.get()) return;

                    this.cleanupStaleDisplays();
                    this.visualReady = true;
                }
                finally {
                    this.visualInitPending.set(false);
                }
            });
            if (task == null) {
                this.visualInitPending.set(false);
            }
            return;
        }

        Location blockLocation = blockPos.toLocation();
        if (blockLocation == null) {
            this.visualInitPending.set(false);
            return;
        }
        int generation = this.visualGeneration.get();
        var task = this.visualScheduler.runTask(blockLocation, () -> {
            try {
                if (generation != this.visualGeneration.get()) return;

                Block ownedBlock = blockLocation.getBlock();
                double offset = Math.max(0, this.crate.getHologramYOffset());
                double height = ownedBlock.getBoundingBox().getHeight() + offset;

                this.displayLocation = LocationUtil.setCenter2D(blockLocation).add(0, height, 0);

                this.plugin.runTask(this.player, () -> this.hideHologram(blockPos));
                this.cleanupStaleDisplays();
                this.visualReady = true;
            }
            finally {
                this.visualInitPending.set(false);
            }
        });
        if (task == null) {
            this.visualInitPending.set(false);
        }
    }

    @Override
    public long getInterval() {
        return 1L;
    }

    @Override
    public void instaRoll() {
        this.setRefundable(false);
        this.spinCount = this.spinsRequired - 1;
        this.finishDelay = 0L;

        this.spin();

        // Display roll visuals only when instal roll was called in the middle of the opening process to "finish" the visual part.
        if (this.tickCount > 0) {
            this.displayReward();
        }

        this.stop();
    }

    @Override
    protected void onStart() {

    }

    @Override
    protected void onTick() {
        if (!this.visualReady) {
            this.onFirstTick();
            return;
        }

        if (this.isSpinsCompleted()) {
            if (this.finishDelay > 0) {
                this.finishDelay--;
            }
            return;
        }

        if (this.isRefundable()) {
            this.setRefundable(false);
        }

        if (this.animationTick % this.spinInterval == 0) {
            this.spin();
            this.displayReward();
        }
        this.animationTick++;
    }

    @Override
    protected void onComplete() {

    }

    @Override
    protected void onStop() {
        this.removeRewardDisplay();

        WorldPos blockPos = this.source.getBlockPos();
        if (blockPos != null) {
            this.showHologram(blockPos);
        }

        super.onStop();
    }

    @Override
    public boolean isCompleted() {
        return this.isSpinsCompleted() && this.finishDelay <= 0;
    }

    private void spin() {
        this.spinCount++;
    }

    private boolean isSpinsCompleted() {
        return this.spinCount >= this.spinsRequired;
    }

    private void displayReward() {
        Reward reward = this.isSpinsCompleted() ? this.reward : this.crate.rollReward(this.player);
        Location location = this.displayLocation;
        if (location == null) return;

        int generation = this.visualGeneration.get();
        ItemStack itemStack = reward.getPreviewItem();
        String rewardName = reward.getName();
        this.pendingDisplay = new DisplaySnapshot(generation, itemStack, rewardName);
        if (this.visualUpdatePending.compareAndSet(false, true)) {
            this.scheduleDisplayUpdate(location);
        }

        this.playOpeningSound(Sound.UI_BUTTON_CLICK, 0.5f, 1f);
        this.playOpeningSound(Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1f);

        if (this.isSpinsCompleted()) {
            this.playOpeningSound(Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1f);
        }
    }

    @Override
    protected boolean rerollWithAnimation() {
        if (this.isIgnoreAnimationOnReroll()) {
            return false;
        }

        this.awaitingRewardConfirmation = false;
        this.rerollMenuSwitching = true;

        this.player.closeInventory();
        this.rerollMenuSwitching = false;

        this.plugin.getDataManager().replaceReservedRewards(this.player, this.rewards, () -> {
            this.rewards.add(this.crate.rollRewardReserved(this.player));
        });
        this.tickCount = 0L;
        this.animationTick = 0L;
        this.spinCount = 0L;
        this.finishDelay = this.initialFinishDelay;
        this.removeRewardDisplay();
        this.displayLocation = null;
        this.visualReady = false;
        this.visualInitPending.set(false);
        this.reward = this.rewards.getFirst();

        this.start();
        return true;
    }

    private void removeRewardDisplay() {
        this.visualGeneration.incrementAndGet();
        this.pendingDisplay = null;

        Item display = this.rewardDisplay;
        UUID displayId = this.rewardDisplayId;
        Location location = this.displayLocation;
        this.rewardDisplay = null;
        this.rewardDisplayId = null;
        if (display == null || location == null) return;

        var task = this.visualScheduler.runTask(location, () -> {
            this.plugin.getPrivateEntityVisibility().unregister(displayId);
            if (display.isValid()) {
                display.remove();
            }
        });
        if (task == null) {
            this.plugin.getPrivateEntityVisibility().unregister(displayId);
        }
    }

    private void cleanupStaleDisplays() {
        if (this.displayLocation == null) return;

        for (Entity entity : this.displayLocation.getWorld().getNearbyEntities(this.displayLocation, 1.5D, 2D, 1.5D)) {
            if (!(entity instanceof Item item)) continue;
            if (!item.getScoreboardTags().contains(this.displayTag)) continue;

            this.plugin.getPrivateEntityVisibility().unregister(item);
            item.remove();
        }
    }

    private void scheduleDisplayUpdate(@NotNull Location location) {
        var task = this.visualScheduler.runTask(location, () -> {
            try {
                DisplaySnapshot snapshot = this.pendingDisplay;
                this.pendingDisplay = null;
                if (snapshot == null || snapshot.generation() != this.visualGeneration.get()) return;

                if (this.rewardDisplay == null) {
                    boolean[] privateTracking = new boolean[1];
                    try {
                        Item spawned = location.getWorld().spawn(location, Item.class, item -> {
                            item.setVelocity(new Vector());
                            item.setPersistent(false);
                            item.addScoreboardTag(this.displayTag);
                            item.setCustomNameVisible(true);
                            item.setGravity(false);
                            item.setPickupDelay(Integer.MAX_VALUE);
                            item.setUnlimitedLifetime(true);
                            item.setInvulnerable(true);
                            item.setItemStack(snapshot.itemStack());
                            EntityUtil.setCustomName(item, snapshot.rewardName());
                            this.rewardDisplayId = item.getUniqueId();
                            privateTracking[0] = this.plugin.getPrivateEntityVisibility().register(item, this.playerId);
                        });
                        if (snapshot.generation() != this.visualGeneration.get()) {
                            this.plugin.getPrivateEntityVisibility().unregister(this.rewardDisplayId);
                            this.rewardDisplayId = null;
                            spawned.remove();
                            return;
                        }
                        this.rewardDisplay = spawned;
                    }
                    catch (RuntimeException exception) {
                        this.plugin.getPrivateEntityVisibility().unregister(this.rewardDisplayId);
                        this.rewardDisplayId = null;
                        throw exception;
                    }

                    if (!privateTracking[0]) {
                        Item display = this.rewardDisplay;
                        Bukkit.getOnlinePlayers().stream()
                            .filter(viewer -> !viewer.getUniqueId().equals(this.playerId))
                            .forEach(viewer -> this.plugin.runTask(viewer, () -> viewer.hideEntity(this.plugin, display)));
                    }
                    return;
                }
                this.rewardDisplay.setItemStack(snapshot.itemStack());
                EntityUtil.setCustomName(this.rewardDisplay, snapshot.rewardName());
            }
            finally {
                this.visualUpdatePending.set(false);
                if (this.pendingDisplay != null && this.visualUpdatePending.compareAndSet(false, true)) {
                    this.scheduleDisplayUpdate(location);
                }
            }
        });
        if (task == null) {
            this.visualUpdatePending.set(false);
        }
    }

    private void playOpeningSound(@NotNull Sound sound, float volume, float pitch) {
        if (this.displayLocation == null || this.displayLocation.getWorld() == null) return;

        Location soundLocation = this.displayLocation.clone();
        Bukkit.getOnlinePlayers().stream()
            .forEach(viewer -> this.plugin.runTask(viewer, () -> {
                if (!viewer.isOnline() || viewer.getWorld() != soundLocation.getWorld()) return;
                if (!this.shouldHearOpeningSound(viewer)) return;

                viewer.playSound(soundLocation, sound, volume, pitch);
            }));
    }

    private boolean shouldHearOpeningSound(@NotNull Player viewer) {
        if (!Config.OPENING_SOUNDS_GLOBAL_ENABLED.get() && !viewer.getUniqueId().equals(this.playerId)) {
            return false;
        }

        OpeningSoundMode mode = this.plugin.getUserManager().getOrFetch(viewer).getOpeningSoundMode();
        if (mode == OpeningSoundMode.NONE) return false;
        if (mode == OpeningSoundMode.SELF) return viewer.getUniqueId().equals(this.playerId);
        return true;
    }

    private record DisplaySnapshot(int generation, @NotNull ItemStack itemStack, @NotNull String rewardName) {

    }
}
