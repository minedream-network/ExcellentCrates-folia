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
import su.nightexpress.nightcore.util.EntityUtil;
import su.nightexpress.nightcore.util.LocationUtil;

public class SimpleRollOpening extends WorldOpening {

    private static final String DISPLAY_TAG = "excellentcrates_reward_display";

    private final int    spinsRequired;
    private final long   spinInterval;
    private final long   initialFinishDelay;

    private Reward reward;

    private long spinCount;
    private long finishDelay;

    private Item     rewardDisplay;
    private Location displayLocation;

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

        this.reward = source.getCrate().rollReward(player);
    }

    private void onFirstTick() {
        Block block = this.source.getBlock();

        Location center;
        if (block == null) {
            Location playerLoc = this.player.getEyeLocation().clone();
            Vector direction = playerLoc.getDirection();

            for (int i = 0; i < 3; i++) {
                playerLoc.add(direction);
            }

            center = LocationUtil.setCenter3D(playerLoc);
        }
        else {
            double offset = Math.max(0, this.crate.getHologramYOffset());
            double height = block.getBoundingBox().getHeight() + offset;

            center = LocationUtil.setCenter2D(block.getLocation()).add(0, height, 0);

            WorldPos blockPos = WorldPos.from(block);
            this.hideHologram(blockPos);
        }

        this.displayLocation = center;
        this.cleanupStaleDisplays();
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
        if (this.isSpinsCompleted()) {
            if (this.finishDelay > 0) {
                this.finishDelay--;
            }
            return;
        }

        if (this.tickCount == 0) {
            this.onFirstTick();
            this.setRefundable(false);
        }

        if (this.tickCount % this.spinInterval == 0) {
            this.spin();
            this.displayReward();
        }
    }

    @Override
    protected void onComplete() {

    }

    @Override
    protected void onStop() {
        this.addReward(this.reward);

        this.removeRewardDisplay();

        Block block = this.source.getBlock();
        if (block != null) {
            WorldPos blockPos = WorldPos.from(block);
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

        if (this.rewardDisplay == null) {
            this.rewardDisplay = player.getWorld().spawn(this.displayLocation, Item.class, item -> item.setVelocity(new Vector()));
            this.rewardDisplay.setPersistent(false);
            this.rewardDisplay.addScoreboardTag(DISPLAY_TAG);
            this.rewardDisplay.setCustomNameVisible(true);
            this.rewardDisplay.setGravity(false);
            this.rewardDisplay.setPickupDelay(Integer.MAX_VALUE);
            this.rewardDisplay.setUnlimitedLifetime(true);
            this.rewardDisplay.setInvulnerable(true);
            Bukkit.getOnlinePlayers().stream()
                .filter(viewer -> viewer != this.player)
                .forEach(viewer -> viewer.hideEntity(this.plugin, this.rewardDisplay));
            //this.rewardDisplay.setBillboard(Display.Billboard.CENTER);
            //this.rewardDisplay.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.35f, 0.35f, 0.35f), new AxisAngle4f()));
        }
        if (this.rewardDisplay != null) {
            ItemStack itemStack = reward.getPreviewItem();
            this.rewardDisplay.setItemStack(itemStack);
            EntityUtil.setCustomName(this.rewardDisplay, reward.getName());
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

        this.rewards.clear();
        this.tickCount = 0L;
        this.spinCount = 0L;
        this.finishDelay = this.initialFinishDelay;
        this.displayLocation = null;
        this.reward = this.crate.rollReward(this.player);

        this.removeRewardDisplay();

        this.start();
        return true;
    }

    private void removeRewardDisplay() {
        if (this.rewardDisplay == null) return;

        if (this.rewardDisplay.isValid()) {
            this.rewardDisplay.remove();
        }
        this.rewardDisplay = null;
    }

    private void cleanupStaleDisplays() {
        if (this.displayLocation == null) return;

        for (Entity entity : this.displayLocation.getWorld().getNearbyEntities(this.displayLocation, 1.5D, 2D, 1.5D)) {
            if (!(entity instanceof Item item)) continue;
            if (!item.getScoreboardTags().contains(DISPLAY_TAG)) continue;

            item.remove();
        }
    }

    private void playOpeningSound(@NotNull Sound sound, float volume, float pitch) {
        if (this.displayLocation == null || this.displayLocation.getWorld() == null) return;

        Bukkit.getOnlinePlayers().stream()
            .filter(Player::isOnline)
            .filter(viewer -> viewer.getWorld().equals(this.displayLocation.getWorld()))
            .filter(viewer -> this.shouldHearOpeningSound(viewer))
            .forEach(viewer -> viewer.playSound(this.displayLocation, sound, volume, pitch));
    }

    private boolean shouldHearOpeningSound(@NotNull Player viewer) {
        if (!Config.OPENING_SOUNDS_GLOBAL_ENABLED.get() && !viewer.getUniqueId().equals(this.player.getUniqueId())) {
            return false;
        }

        OpeningSoundMode mode = this.plugin.getUserManager().getOrFetch(viewer).getOpeningSoundMode();
        if (mode == OpeningSoundMode.NONE) return false;
        if (mode == OpeningSoundMode.SELF) return viewer.getUniqueId().equals(this.player.getUniqueId());
        return true;
    }
}
