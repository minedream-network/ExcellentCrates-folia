package su.nightexpress.excellentcrates.api.broadcast;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.api.crate.Reward;

@FunctionalInterface
public interface RewardBroadcastFilter {

    boolean shouldReceive(@NotNull Player target, @NotNull Player opener, @NotNull Reward reward);
}
