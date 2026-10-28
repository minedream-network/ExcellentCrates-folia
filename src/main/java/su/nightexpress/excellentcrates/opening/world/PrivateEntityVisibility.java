package su.nightexpress.excellentcrates.opening.world;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PrivateEntityVisibility {

    private static final String TRACK_EVENT = "io.papermc.paper.event.player.PlayerTrackEntityEvent";

    private final Map<UUID, UUID> viewers = new ConcurrentHashMap<>();
    private final Listener listener = new Listener() {};
    private final AtomicBoolean eventErrorLogged = new AtomicBoolean();

    private volatile boolean trackingEventSupported;

    public void setup(@NotNull CratesPlugin plugin) {
        try {
            Class<? extends Event> eventType = Class.forName(TRACK_EVENT).asSubclass(Event.class);
            Method playerGetter = eventType.getMethod("getPlayer");
            Method entityGetter = eventType.getMethod("getEntity");

            Bukkit.getPluginManager().registerEvent(eventType, this.listener, EventPriority.LOWEST, (ignored, event) -> {
                try {
                    Entity entity = (Entity) entityGetter.invoke(event);
                    Player player = (Player) playerGetter.invoke(event);
                    if (!this.canTrack(entity.getUniqueId(), player.getUniqueId())) {
                        ((Cancellable) event).setCancelled(true);
                    }
                }
                catch (ReflectiveOperationException exception) {
                    if (this.eventErrorLogged.compareAndSet(false, true)) {
                        plugin.error("Could not filter private opening displays: " + exception.getMessage());
                    }
                }
            }, plugin, true);
            this.trackingEventSupported = true;
        }
        catch (ClassNotFoundException ignored) {
            // Spigot uses the main-thread hideEntity fallback.
        }
        catch (ReflectiveOperationException | LinkageError exception) {
            plugin.warn("Private opening display tracking is unavailable: " + exception.getMessage());
        }
    }

    public boolean register(@NotNull Entity entity, @NotNull UUID viewerId) {
        if (!this.trackingEventSupported) return false;

        this.register(entity.getUniqueId(), viewerId);
        return true;
    }

    public void unregister(@NotNull Entity entity) {
        this.unregister(entity.getUniqueId());
    }

    public void unregister(@Nullable UUID entityId) {
        if (entityId != null) this.viewers.remove(entityId);
    }

    public void shutdown() {
        HandlerList.unregisterAll(this.listener);
        this.viewers.clear();
        this.trackingEventSupported = false;
    }

    void register(@NotNull UUID entityId, @NotNull UUID viewerId) {
        this.viewers.put(entityId, viewerId);
    }

    boolean canTrack(@NotNull UUID entityId, @NotNull UUID viewerId) {
        UUID allowedViewer = this.viewers.get(entityId);
        return allowedViewer == null || allowedViewer.equals(viewerId);
    }
}
