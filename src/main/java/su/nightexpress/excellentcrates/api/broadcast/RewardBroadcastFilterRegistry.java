package su.nightexpress.excellentcrates.api.broadcast;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.api.crate.Reward;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RewardBroadcastFilterRegistry {
    private final Map<String, RewardBroadcastFilter> filters = new ConcurrentHashMap<>();

    public void registerFilter(@NotNull String owner, @NotNull RewardBroadcastFilter filter) {
        this.filters.put(owner.toLowerCase(Locale.ROOT), filter);
    }

    public void unregisterFilter(@NotNull String owner) {
        this.filters.remove(owner.toLowerCase(Locale.ROOT));
    }

    public void clear() {
        this.filters.clear();
    }

    public boolean shouldReceive(@NotNull Player target, @NotNull Player opener, @NotNull Reward reward) {
        for (RewardBroadcastFilter filter : this.filters.values()) {
            if (!filter.shouldReceive(target, opener, reward)) {
                return false;
            }
        }
        return true;
    }
}
