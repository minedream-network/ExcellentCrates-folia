package su.nightexpress.excellentcrates.data.crate;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.crate.impl.Crate;

import java.util.UUID;

public class GlobalCrateData {

    private final String crateId;

    private volatile UUID latestOpenerId;
    private volatile String latestOpenerName;
    private volatile String latestRewardId;

    private volatile boolean dirty;
    private volatile boolean persisted = true;
    private volatile long    revision;

    @NotNull
    public static GlobalCrateData create(@NotNull Crate crate) {
        GlobalCrateData data = new GlobalCrateData(crate.getId(), null, null, null);
        data.persisted = false;
        return data;
    }

    public GlobalCrateData(@NotNull String crateId,
                           @Nullable UUID latestOpenerId,
                           @Nullable String latestOpenerName,
                           @Nullable String latestRewardId) {
        this.crateId = crateId.toLowerCase();
        this.latestOpenerId = latestOpenerId;
        this.latestOpenerName = latestOpenerName;
        this.latestRewardId = latestRewardId;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public synchronized void setDirty(boolean dirty) {
        this.dirty = dirty;
        if (dirty) {
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
            this.dirty = false;
        }
    }

    public void setLatestOpener(@NotNull Player player) {
        this.latestOpenerId = player.getUniqueId();
        this.latestOpenerName = player.getName();
    }

    public void setLatestReward(@NotNull Reward reward) {
        this.latestRewardId = reward.getId();
    }

    @NotNull
    public String getCrateId() {
        return this.crateId;
    }

    @Nullable
    public UUID getLatestOpenerId() {
        return this.latestOpenerId;
    }

    @Nullable
    public String getLatestOpenerName() {
        return this.latestOpenerName;
    }

    @Nullable
    public String getLatestRewardId() {
        return this.latestRewardId;
    }
}
