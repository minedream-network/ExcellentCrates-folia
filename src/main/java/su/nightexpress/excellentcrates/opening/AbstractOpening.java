package su.nightexpress.excellentcrates.opening;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.Placeholders;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.data.crate.GlobalCrateData;
import su.nightexpress.excellentcrates.data.crate.UserCrateData;
import su.nightexpress.excellentcrates.user.CrateUser;
import su.nightexpress.nightcore.bridge.paper.scheduler.FoliaScheduler;
import su.nightexpress.nightcore.util.Players;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

public abstract class AbstractOpening implements Opening {

    protected final CratesPlugin plugin;
    protected final Player       player;
    protected final CrateSource  source;
    protected final Crate        crate;
    protected final Cost         cost;
    protected final List<Reward> rewards;

    protected long    tickCount;
    protected boolean running;
    protected boolean refundable;
    protected boolean awaitingRewardConfirmation;
    protected boolean rewardsCompleted;
    protected boolean rerollMenuSwitching;
    protected boolean ignoreAnimationOnReroll;

    private int rerollsUsed;

    public AbstractOpening(@NotNull CratesPlugin plugin, @NotNull Player player, @NotNull CrateSource source, @Nullable Cost cost) {
        this.plugin = plugin;
        this.player = player;
        this.source = source;
        this.crate = source.getCrate();
        this.cost = cost;
        this.rewards = new ArrayList<>();
        this.setRefundable(true);
    }

    @Override
    public void start() {
        if (this.running) return;

        this.running = true;
        this.onStart();
    }

    @Override
    public void stop() {
        if (!this.running) return;

        this.running = false;
        this.onStop();
    }

    @Override
    public void tick() {
        if (!this.running) {
            if (this.awaitingRewardConfirmation && this.player.isOnline() && !this.isViewingRerollMenu() && !this.rerollMenuSwitching) {
                this.reopenRerollMenu();
                return;
            }

            if (!this.awaitingRewardConfirmation || this.rewardsCompleted) {
                this.plugin.getOpeningManager().removeOpening(this.player);
            }
            return;
        }

        if (this.isCompleted()) {
            this.stop();
            return;
        }

        if (this.isTickTime()) {
            this.onTick();
        }

        this.tickCount = Math.max(0L, this.tickCount + 1L);
    }

    @Override
    public final boolean isRunning() {
        return this.running;
    }

    @Override
    public long getTickCount() {
        return this.tickCount;
    }

    @Override
    public boolean isTickTime() {
        return this.tickCount == 0 || this.tickCount % this.getInterval() == 0L;
    }

    protected abstract void onStart();

    protected abstract void onTick();

    protected abstract void onComplete();

    protected void onStop() {
        if (this.isRefundable()) {
            if (this.cost != null) {
                this.cost.refundAll(this.player);
            }
            if (this.source.getItem() != null) {
                Players.addItem(this.player, this.crate.getItemStack());
            }
        }

        if (this.isCompleted()) {
            if (this.shouldConfirmRewards()) {
                this.openRerollMenu(false);
                return;
            }

            this.completeRewards();
            return;
        }

        this.awaitingRewardConfirmation = false;
        this.rerollMenuSwitching = false;
        this.plugin.getOpeningManager().removeOpening(this.getPlayer());
    }

    public boolean isAwaitingRewardConfirmation() {
        return this.awaitingRewardConfirmation;
    }

    public boolean isViewingRerollMenu() {
        return this.player.getOpenInventory() != null && isRerollMenu(this.player.getOpenInventory().getTopInventory());
    }

    public void handleRerollClick(@NotNull InventoryClickEvent event) {
        event.setCancelled(true);

        Inventory topInventory = event.getView().getTopInventory();
        if (!(topInventory.getHolder() instanceof RerollInventoryHolder holder) || holder.getOpening() != this) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= topInventory.getSize()) {
            return;
        }

        if (holder.isConfirmSlot(slot)) {
            new FoliaScheduler(this.plugin).runTask(this.player, () -> this.confirmRewards());
        }
        else if (holder.isRerollSlot(slot)) {
            new FoliaScheduler(this.plugin).runTask(this.player, () -> this.rerollRewards());
        }
    }

    public void reopenRerollMenu() {
        if (!this.awaitingRewardConfirmation || !this.player.isOnline()) return;

        new FoliaScheduler(this.plugin).runTask(this.player, () -> {
            if (!this.awaitingRewardConfirmation || !this.player.isOnline()) return;

            RerollMenu menu = this.plugin.getOpeningManager().getRerollMenu();
            if (this.isViewingRerollMenu()) {
                if (menu.shouldReopen(this)) {
                    this.rerollMenuSwitching = true;
                    menu.open(this);
                    return;
                }

                menu.refresh(this);
                this.rerollMenuSwitching = false;
                return;
            }

            this.rerollMenuSwitching = false;
            menu.open(this);
        });
    }

    public void handleRerollClose() {
        if (!this.awaitingRewardConfirmation) return;

        if (this.rerollMenuSwitching) {
            this.rerollMenuSwitching = false;
            return;
        }

        this.confirmRewards();
    }

    public void confirmRewards() {
        if (!this.awaitingRewardConfirmation) return;

        this.awaitingRewardConfirmation = false;
        this.completeRewards();
        this.player.closeInventory();
    }

    public boolean rerollRewards() {
        if (!this.awaitingRewardConfirmation || !this.canReroll()) return false;

        this.rerollsUsed++;

        if (this.rerollWithAnimation()) {
            return true;
        }

        int amount = Math.max(1, this.rewards.size());
        this.rewards.clear();
        IntStream.range(0, amount).forEach(index -> this.rewards.add(this.crate.rollReward(this.player)));

        this.openRerollMenu(true);
        return true;
    }

    public boolean canReroll() {
        return this.rerollsUsed < this.crate.getRerollAttempts();
    }

    public int getRerollsLeft() {
        return Math.max(0, this.crate.getRerollAttempts() - this.rerollsUsed);
    }

    public int getRerollsUsed() {
        return this.rerollsUsed;
    }

    protected boolean rerollWithAnimation() {
        return false;
    }

    protected void openRerollMenu(boolean switching) {
        this.awaitingRewardConfirmation = true;

        if (this.isViewingRerollMenu()) {
            this.rerollMenuSwitching = switching;
            this.reopenRerollMenu();
            return;
        }

        this.rerollMenuSwitching = switching;
        this.reopenRerollMenu();
    }

    private boolean shouldConfirmRewards() {
        return this.crate.isRerollEnabled() && this.crate.getRerollAttempts() > 0 && !this.rewardsCompleted;
    }

    private void completeRewards() {
        if (this.rewardsCompleted) return;

        this.awaitingRewardConfirmation = false;
        this.rerollMenuSwitching = false;
        this.rewardsCompleted = true;
        this.plugin.getOpeningManager().removeOpening(this.getPlayer());
        this.onComplete();

        CrateUser user = plugin.getUserManager().getOrFetch(player);
        UserCrateData userData = user.getCrateData(this.crate);
        GlobalCrateData globalData = plugin.getDataManager().getCrateDataOrCreate(this.crate);

        userData.addOpenings(1);
        globalData.setLatestOpener(this.player);
        globalData.setDirty(true);

        this.rewards.forEach(reward -> reward.give(this.player));

        if (crate.isOpeningCooldownEnabled()) {
            userData.addOpeningStreak(1);

            if (!userData.isOnCooldown() && !crate.hasCooldownBypassPermission(player)) {
                userData.setCooldown(crate.getOpeningCooldownTime());
            }
        }

        if (crate.hasMilestones()) {
            userData.addMilestones(1);
            plugin.getCrateManager().triggerMilestones(player, crate, userData.getMilestone());
            if (userData.getMilestone() >= crate.getMaxMilestone() && crate.isMilestonesRepeatable()) {
                userData.setMilestone(0);
            }
        }

        Lang.CRATE_OPEN_RESULT_INFO.message().send(this.player, replacer -> replacer
            .replace(this.crate.replacePlaceholders())
            .replace(Placeholders.GENERIC_REWARDS, this.rewards.stream()
                .map(reward -> reward.replacePlaceholders().apply(Lang.CRATE_OPEN_RESULT_REWARD.text()))
                .collect(Collectors.joining(", "))
            )
        );

        List<String> postOpenCommands = Replacer.create().replace(this.crate.replacePlaceholders()).apply(this.crate.getPostOpenCommands());
        Players.dispatchCommands(this.player, postOpenCommands);

        this.plugin.getUserManager().save(user);
    }

    public static boolean isRerollMenu(@NotNull Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof RerollInventoryHolder;
    }

    @Override
    @NotNull
    public List<Reward> getRewards() {
        return this.rewards;
    }

    @Override
    public void addReward(@NotNull Reward reward) {
        this.rewards.add(reward);
    }

    @Override
    public void addRewards(@NotNull Collection<Reward> rewards) {
        this.rewards.addAll(rewards);
    }

    @Override
    public boolean isRefundable() {
        return this.refundable;
    }

    @Override
    public void setRefundable(boolean refundable) {
        this.refundable = refundable;
    }

    public boolean isIgnoreAnimationOnReroll() {
        return this.ignoreAnimationOnReroll;
    }

    public void setIgnoreAnimationOnReroll(boolean ignoreAnimationOnReroll) {
        this.ignoreAnimationOnReroll = ignoreAnimationOnReroll;
    }

    @Override
    @NotNull
    public Player getPlayer() {
        return this.player;
    }

    @Override
    @NotNull
    public CrateSource getSource() {
        return this.source;
    }

    @Override
    @NotNull
    public Crate getCrate() {
        return this.crate;
    }

    @Override
    @Nullable
    public Cost getCost() {
        return this.cost;
    }
}
