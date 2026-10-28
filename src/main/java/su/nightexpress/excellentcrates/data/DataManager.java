package su.nightexpress.excellentcrates.data;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.limit.LimitValues;
import su.nightexpress.excellentcrates.data.crate.GlobalCrateData;
import su.nightexpress.excellentcrates.crate.reward.RewardKey;
import su.nightexpress.excellentcrates.data.reward.RewardData;
import su.nightexpress.nightcore.manager.AbstractManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class DataManager extends AbstractManager<CratesPlugin> {

    private final Map<String, GlobalCrateData> crateDataMap;
    private final Map<RewardKey, RewardData>   rewardLimitMap;
    private final Map<RewardKey, Integer>      rewardReservations;
    private final Map<ReservationKey, Deque<RewardReservation>> rewardReservationEntries;
    private final Object cratePersistenceLock;
    private final Object rewardPersistenceLock;

    private volatile boolean dataLoaded;

    public DataManager(@NotNull CratesPlugin plugin) {
        super(plugin);
        this.crateDataMap = new ConcurrentHashMap<>();
        this.rewardLimitMap = new ConcurrentHashMap<>();
        this.rewardReservations = new HashMap<>();
        this.rewardReservationEntries = new HashMap<>();
        this.cratePersistenceLock = new Object();
        this.rewardPersistenceLock = new Object();
    }

    @Override
    protected void onLoad() {
        this.plugin.runTaskAsync(() -> this.loadData());

        this.addAsyncTask(this::saveCrateDatas, Config.DATA_CRATE_DATA_SAVE_INTERVAL.get());
        this.addAsyncTask(this::saveRewardLimits, Config.DATA_REWARD_LIMITS_SAVE_INTERVAL.get());
    }

    @Override
    protected void onShutdown() {
        if (!this.saveCrateDatasChecked() && !this.saveCrateDatasChecked()) {
            this.plugin.error("Could not persist crate data during shutdown after two attempts.");
        }
        if (!this.saveRewardLimitsChecked() && !this.saveRewardLimitsChecked()) {
            this.plugin.error("Could not persist reward limits during shutdown after two attempts.");
        }
        this.crateDataMap.clear();
        this.rewardLimitMap.clear();
        synchronized (this) {
            this.rewardReservations.clear();
            this.rewardReservationEntries.clear();
        }
        this.dataLoaded = false;
    }

    public void saveData() {
        this.saveCrateDatas();
        this.saveRewardLimits();
    }

    public void saveCrateDatas() {
        this.saveCrateDatasChecked();
    }

    private boolean saveCrateDatasChecked() {
        synchronized (this.cratePersistenceLock) {
            Set<GlobalCrateData> dataSet = this.getCrateDatas().stream()
                .filter(GlobalCrateData::isDirty)
                .collect(Collectors.toSet());
            if (dataSet.isEmpty()) return true;

            Map<GlobalCrateData, Long> revisions = dataSet.stream()
                .collect(Collectors.toMap(data -> data, GlobalCrateData::getRevision));
            if (this.plugin.getDataHandler().updateCrateDatasChecked(dataSet)) {
                revisions.forEach(GlobalCrateData::markSaved);
                return true;
            }
            return false;
        }
        //this.plugin.debug("Saved " + dataSet.size() + " crate datas.");
    }

    public void saveRewardLimits() {
        this.saveRewardLimitsChecked();
    }

    private boolean saveRewardLimitsChecked() {
        synchronized (this.rewardPersistenceLock) {
            Map<RewardData, Long> revisions;
            synchronized (this) {
                revisions = this.getRewardLimits().stream()
                    .filter(RewardData::isSaveRequired)
                    .collect(Collectors.toMap(data -> data, RewardData::getRevision));
            }
            if (revisions.isEmpty()) return true;

            if (this.plugin.getDataHandler().updateRewardLimitsChecked(revisions.keySet())) {
                synchronized (this) {
                    revisions.forEach(RewardData::markSaved);
                }
                return true;
            }
            return false;
        }
        //this.plugin.debug("Saved " + limits.size() + " reward limits.");
    }

    public void loadData() {
        this.loadCrateDatas();
        this.loadRewardLimits();

        this.dataLoaded = true;
    }

    public void loadCrateDatas() {
        synchronized (this.cratePersistenceLock) {
            Map<String, GlobalCrateData> loaded = this.plugin.getDataHandler().loadCrateDatas().stream()
                .collect(Collectors.toMap(GlobalCrateData::getCrateId, data -> data, (first, second) -> second));

            this.crateDataMap.keySet().removeIf(crateId -> {
                GlobalCrateData current = this.crateDataMap.get(crateId);
                return !loaded.containsKey(crateId) && (current == null || !current.isDirty());
            });
            loaded.forEach((crateId, data) -> this.crateDataMap.compute(crateId, (unused, current) -> {
                return current != null && current.isDirty() ? current : data;
            }));
        }

        //this.plugin.debug("Loaded " + this.crateDataMap.size() + " crate datas.");
    }

    public void loadRewardLimits() {
        synchronized (this.rewardPersistenceLock) {
            Map<RewardKey, RewardData> loaded = this.plugin.getDataHandler().loadRewardLimits().stream()
                .collect(Collectors.toMap(DataManager::getRewardKey, data -> data, (first, second) -> second));

            synchronized (this) {
                this.rewardLimitMap.keySet().removeIf(key -> {
                    RewardData current = this.rewardLimitMap.get(key);
                    return !loaded.containsKey(key) && (current == null || !current.isSaveRequired());
                });
                loaded.forEach((key, data) -> this.rewardLimitMap.compute(key, (unused, current) -> {
                    return current != null && current.isSaveRequired() ? current : data;
                }));
            }
        }

        //this.plugin.debug("Loaded " + this.rewardLimitMap.size() + " reward limit datas.");
    }



    public void handleSynchronization() {
        if (!this.isDataLoaded()) return;

        if (Config.isCrateDataSynchronized()) {
            this.loadCrateDatas();
        }
        if (Config.isRewardLimitsSynchronized()) {
            this.loadRewardLimits();
        }
    }

    public void handleCrateRemoval(@NotNull Crate crate) {
        if (Config.isCrateDataSynchronized()) {
            this.deleteCrateData(crate);
        }
        if (Config.isRewardLimitsSynchronized()) {
            this.deleteRewardLimits(crate);
        }
    }

    public void handleRewardRemoval(@NotNull Reward reward) {
        if (Config.isRewardLimitsSynchronized()) {
            this.deleteRewardLimits(reward);
        }
    }



    public boolean isDataLoaded() {
        return this.dataLoaded;
    }

    @NotNull
    public Set<GlobalCrateData> getCrateDatas() {
        return new HashSet<>(this.crateDataMap.values());
    }

    @Nullable
    public GlobalCrateData getCrateData(@NotNull String crateId) {
        return this.crateDataMap.get(crateId.toLowerCase());
    }

    @NotNull
    public GlobalCrateData getCrateDataOrCreate(@NotNull Crate crate) {
        return this.crateDataMap.computeIfAbsent(crate.getId(), crateId -> GlobalCrateData.create(crate));
    }

    public void deleteCrateData(@NotNull Crate crate) {
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteCrateData(crate));
        this.crateDataMap.remove(crate.getId());
    }



    @NotNull
    public RewardData getRewardLimitOrCreate(@NotNull Reward reward, @Nullable Player player) {
        RewardKey key = getRewardKey(reward, player);
        return this.rewardLimitMap.computeIfAbsent(key, rewardKey -> RewardData.create(reward, player));
    }

    @Nullable
    public RewardData getRewardLimit(@NotNull Reward reward, @Nullable Player player) {
        RewardKey key = getRewardKey(reward, player);
        return this.rewardLimitMap.get(key);
    }

    @NotNull
    public Set<RewardData> getRewardLimits() {
        return new HashSet<>(this.rewardLimitMap.values());
    }

    public synchronized boolean isRewardOnCooldown(@NotNull Reward reward, @NotNull Player player) {
        LimitValues limits = reward.getLimits();
        if (!limits.isEnabled()) return false;

        if (limits.hasGlobalCooldown()) {
            RewardData data = this.getRewardLimit(reward, null);
            if ((data != null && data.isOnCooldown()) || this.getReservationCount(reward, null) > 0) {
                return true;
            }
        }

        if (limits.hasPlayerCooldown()) {
            RewardData data = this.getRewardLimit(reward, player);
            return (data != null && data.isOnCooldown()) || this.getReservationCount(reward, player) > 0;
        }

        return false;
    }

    public synchronized int getRewardAvailableRolls(@NotNull Reward reward, @NotNull Player player) {
        LimitValues limits = reward.getLimits();
        int globalLeft = -1;
        int playerLeft = -1;

        if (limits.isEnabled()) {
            if (limits.isGlobalAmountLimited()) {
                RewardData data = this.getRewardLimit(reward, null);
                int rolls = data == null ? 0 : data.getRolls();
                globalLeft = Math.max(0, limits.getGlobalAmount() - rolls - this.getReservationCount(reward, null));
            }
            if (limits.isPlayerAmountLimited()) {
                RewardData data = this.getRewardLimit(reward, player);
                int rolls = data == null ? 0 : data.getRolls();
                playerLeft = Math.max(0, limits.getPlayerAmount() - rolls - this.getReservationCount(reward, player));
            }
        }

        if (globalLeft < 0 || playerLeft < 0) {
            return Math.max(playerLeft, globalLeft);
        }
        return Math.min(playerLeft, globalLeft);
    }

    @NotNull
    public synchronized Reward reserveReward(@NotNull Player player, @NotNull Supplier<Reward> selector) {
        Reward reward = selector.get();
        if (!reward.canWin(player)) {
            throw new IllegalStateException("Selected reward is no longer available: " + reward.getId());
        }

        this.reserveRewardInternal(reward, player);
        return reward;
    }

    public synchronized boolean reserveRewards(@NotNull Player player, @NotNull Collection<Reward> rewards) {
        List<Reward> reserved = new ArrayList<>();

        for (Reward reward : rewards) {
            if (!reward.canWin(player)) {
                reserved.forEach(current -> this.releaseRewardInternal(current, player));
                return false;
            }

            this.reserveRewardInternal(reward, player);
            reserved.add(reward);
        }
        return true;
    }

    public synchronized void releaseRewards(@NotNull Player player, @NotNull Collection<Reward> rewards) {
        rewards.forEach(reward -> this.releaseRewardInternal(reward, player));
    }

    public synchronized void replaceReservedRewards(@NotNull Player player,
                                                    @NotNull List<Reward> rewards,
                                                    @NotNull Runnable replacement) {
        List<Reward> previous = List.copyOf(rewards);
        List<DetachedReservation> detached = new ArrayList<>(previous.size());

        for (Reward reward : previous) {
            RewardReservation reservation = this.detachReservation(reward, player);
            if (reservation == null) {
                this.restoreReservations(detached);
                throw new IllegalStateException("Missing reward reservation: " + reward.getId());
            }
            detached.add(new DetachedReservation(getReservationKey(reward, player), reservation));
        }

        rewards.clear();
        try {
            replacement.run();
        }
        catch (RuntimeException exception) {
            rewards.forEach(reward -> this.releaseRewardInternal(reward, player));
            rewards.clear();
            rewards.addAll(previous);
            this.restoreReservations(detached);
            throw exception;
        }
    }

    public synchronized boolean commitReservedReward(@NotNull Player player, @NotNull Reward reward) {
        RewardReservation reservation = this.detachReservation(reward, player);
        if (reservation == null) return false;

        this.applyRewardLimit(player, reward, reservation.limits());
        return true;
    }

    public synchronized boolean commitReward(@NotNull Player player, @NotNull Reward reward) {
        if (!reward.canWin(player)) return false;

        this.applyRewardLimit(player, reward);
        return true;
    }

    private void reserveRewardInternal(@NotNull Reward reward, @NotNull Player player) {
        LimitValues limits = copyLimits(reward.getLimits());
        List<RewardKey> keys = this.getReservationKeys(reward, player, limits);
        keys.forEach(key -> this.rewardReservations.merge(key, 1, Integer::sum));
        this.rewardReservationEntries.computeIfAbsent(getReservationKey(reward, player), unused -> new ArrayDeque<>())
            .addLast(new RewardReservation(keys, limits));
    }

    private void releaseRewardInternal(@NotNull Reward reward, @NotNull Player player) {
        this.detachReservation(reward, player);
    }

    @Nullable
    private RewardReservation detachReservation(@NotNull Reward reward, @NotNull Player player) {
        ReservationKey reservationKey = getReservationKey(reward, player);
        Deque<RewardReservation> reservations = this.rewardReservationEntries.get(reservationKey);
        if (reservations == null) return null;

        RewardReservation reservation = reservations.pollFirst();
        if (reservations.isEmpty()) {
            this.rewardReservationEntries.remove(reservationKey);
        }
        if (reservation != null) {
            reservation.keys().forEach(this::decrementReservation);
        }
        return reservation;
    }

    private void restoreReservations(@NotNull List<DetachedReservation> detached) {
        for (int index = detached.size() - 1; index >= 0; index--) {
            DetachedReservation entry = detached.get(index);
            entry.reservation().keys().forEach(key -> this.rewardReservations.merge(key, 1, Integer::sum));
            this.rewardReservationEntries.computeIfAbsent(entry.key(), unused -> new ArrayDeque<>())
                .addFirst(entry.reservation());
        }
    }

    private void decrementReservation(@NotNull RewardKey key) {
        Integer count = this.rewardReservations.get(key);
        if (count == null) return;

        if (count <= 1) {
            this.rewardReservations.remove(key);
        }
        else {
            this.rewardReservations.put(key, count - 1);
        }
    }

    private int getReservationCount(@NotNull Reward reward, @Nullable Player player) {
        return this.rewardReservations.getOrDefault(getRewardKey(reward, player), 0);
    }

    @NotNull
    private List<RewardKey> getReservationKeys(@NotNull Reward reward, @NotNull Player player, @NotNull LimitValues limits) {
        if (!limits.isEnabled()) return Collections.emptyList();

        List<RewardKey> keys = new ArrayList<>(2);
        if (limits.hasGlobalCooldown() || limits.isGlobalAmountLimited()) {
            keys.add(getRewardKey(reward, null));
        }
        if (limits.hasPlayerCooldown() || limits.isPlayerAmountLimited()) {
            keys.add(getRewardKey(reward, player));
        }
        return keys;
    }

    private void applyRewardLimit(@NotNull Player player, @NotNull Reward reward) {
        this.applyRewardLimit(player, reward, reward.getLimits());
    }

    private void applyRewardLimit(@NotNull Player player, @NotNull Reward reward, @NotNull LimitValues limits) {
        if (!limits.isEnabled()) return;

        if (limits.hasGlobalCooldown() || limits.isGlobalAmountLimited()) {
            RewardData data = this.getRewardLimitOrCreate(reward, null);
            if (limits.hasGlobalCooldown()) data.setCooldownUntil(limits.generateGlobalCooldown());
            if (limits.isGlobalAmountLimited()) data.addRoll(1);
            data.setSaveRequired(true);
        }

        if (limits.hasPlayerCooldown() || limits.isPlayerAmountLimited()) {
            RewardData data = this.getRewardLimitOrCreate(reward, player);
            if (limits.hasPlayerCooldown()) data.setCooldownUntil(limits.generatePlayerCooldown());
            if (limits.isPlayerAmountLimited()) data.addRoll(1);
            data.setSaveRequired(true);
        }
    }

    @NotNull
    private static LimitValues copyLimits(@NotNull LimitValues limits) {
        return new LimitValues(
            limits.isEnabled(),
            limits.getCooldownType(),
            limits.getGlobalAmount(),
            limits.getPlayerAmount(),
            limits.getGlobalCooldown(),
            limits.getPlayerCooldown()
        );
    }

    @NotNull
    private static ReservationKey getReservationKey(@NotNull Reward reward, @NotNull Player player) {
        return new ReservationKey(player.getUniqueId(), reward.getCrate().getId(), reward.getId());
    }

    public void deleteRewardLimit(@NotNull RewardData limit) {
        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimit(limit));
        this.rewardLimitMap.remove(getRewardKey(limit));
    }

    public void deleteRewardLimits(@NotNull Crate crate) {
        String crateId = crate.getId();

        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimits(crate));
        this.rewardLimitMap.keySet().removeIf(key -> key.crateId().equalsIgnoreCase(crateId));
    }

    public void deleteRewardLimits(@NotNull Reward reward) {
        String crateId = reward.getCrate().getId();
        String rewardId = reward.getId();

        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimits(reward));
        this.rewardLimitMap.keySet().removeIf(key -> key.crateId().equalsIgnoreCase(crateId) && key.rewardId().equalsIgnoreCase(rewardId));
    }

    public void deleteRewardLimits(@NotNull UUID playerId) {
        String holder = playerId.toString();

        this.plugin.runTaskAsync(() -> this.plugin.getDataHandler().deleteRewardLimits(playerId));
        this.rewardLimitMap.keySet().removeIf(key -> key.holder().equalsIgnoreCase(holder));
    }



    @NotNull
    public static String getHolder(@NotNull Reward reward, @Nullable Player player) {
        return player == null ? reward.getCrate().getId() : player.getUniqueId().toString();
    }

    @NotNull
    public static RewardKey getRewardKey(@NotNull Reward reward, @Nullable Player player) {
        Crate crate = reward.getCrate();
        String holder = getHolder(reward, player);

        return new RewardKey(holder, crate.getId(), reward.getId());
    }

    @NotNull
    public static RewardKey getRewardKey(@NotNull RewardData limit) {
        return new RewardKey(limit.getHolder(), limit.getCrateId(), limit.getRewardId());
    }

    private record ReservationKey(@NotNull UUID playerId, @NotNull String crateId, @NotNull String rewardId) {

        private ReservationKey {
            crateId = crateId.toLowerCase();
            rewardId = rewardId.toLowerCase();
        }
    }

    private record RewardReservation(@NotNull List<RewardKey> keys, @NotNull LimitValues limits) {

    }

    private record DetachedReservation(@NotNull ReservationKey key, @NotNull RewardReservation reservation) {

    }
}
