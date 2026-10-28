package su.nightexpress.excellentcrates.data.reward;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.data.DataManager;
import su.nightexpress.nightcore.util.TimeUtil;

public class RewardData {

    private final String crateId;
    private final String rewardId;
    private final String holder;

    private volatile int  rolls;
    private volatile long cooldownUntil;

    private volatile boolean saveRequired;
    private volatile boolean persisted = true;
    private volatile long    revision;

    @NotNull
    public static RewardData create(@NotNull Reward reward, @Nullable Player player) {
        Crate crate = reward.getCrate();
        String holder = DataManager.getHolder(reward, player);

        RewardData data = new RewardData(crate.getId(), reward.getId(), holder, 0, 0L);
        data.persisted = false;
        return data;
    }

    public RewardData(@NotNull String crateId, @NotNull String rewardId, @NotNull String holder, int rolls, long cooldownUntil) {
        this.crateId = crateId.toLowerCase();
        this.rewardId = rewardId.toLowerCase();
        this.holder = holder;
        this.setRolls(rolls);
        this.setCooldownUntil(cooldownUntil);
    }

    public boolean isSaveRequired() {
        return this.saveRequired;
    }

    public synchronized void setSaveRequired(boolean saveRequired) {
        this.saveRequired = saveRequired;
        if (saveRequired) {
            this.revision++;
        }
    }

    public long getRevision() {
        return this.revision;
    }

    public boolean isPersisted() {
        return this.persisted;
    }

    public void markPersisted() {
        this.persisted = true;
    }

    public synchronized void markSaved(long revision) {
        if (this.revision == revision) {
            this.saveRequired = false;
        }
    }

    public synchronized void reset() {
        this.rolls = 0;
        this.cooldownUntil = 0L;
    }

    public boolean isOnCooldown() {
        return !this.isCooldownExpired();
    }

    public boolean isCooldownExpired() {
        return this.cooldownUntil > 0L && TimeUtil.isPassed(this.cooldownUntil);
    }

    public synchronized void addRoll(int amount) {
        this.rolls += amount;
    }

    @NotNull
    public String getCrateId() {
        return this.crateId;
    }

    @NotNull
    public String getRewardId() {
        return this.rewardId;
    }

    @NotNull
    public String getHolder() {
        return this.holder;
    }

    public int getRolls() {
        return this.rolls;
    }

    public void setRolls(int rolls) {
        this.rolls = rolls;
    }

    public long getCooldownUntil() {
        return this.cooldownUntil;
    }

    public void setCooldownUntil(long cooldownUntil) {
        this.cooldownUntil = cooldownUntil;
    }
}
