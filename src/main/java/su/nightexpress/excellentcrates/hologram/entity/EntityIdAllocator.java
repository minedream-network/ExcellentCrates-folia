package su.nightexpress.excellentcrates.hologram.entity;

import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.nightcore.util.EntityUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Allocates IDs for client-side hologram entities without linking the plugin to
 * a server- or NightCore-version-specific entity ID method.
 */
final class EntityIdAllocator {

    private static final String WORLD_AWARE_METHOD = "nextEntityId";

    // Real server entity IDs increment from a low value. This high, descending
    // range is used only if neither the modern server API nor NightCore works.
    private static final AtomicInteger FALLBACK_IDS = new AtomicInteger(Integer.MAX_VALUE);

    private EntityIdAllocator() {

    }

    public static int next(@NotNull World world) {
        Integer worldAwareId = nextWorldAware(world);
        if (worldAwareId != null) return worldAwareId;

        try {
            return EntityUtil.nextEntityId();
        }
        catch (LinkageError exception) {
            return FALLBACK_IDS.getAndDecrement();
        }
    }

    @Nullable
    private static Integer nextWorldAware(@NotNull World world) {
        UnsafeValues unsafeValues = Bukkit.getUnsafe();

        try {
            Method method = UnsafeValues.class.getMethod(WORLD_AWARE_METHOD, World.class);
            Object result = method.invoke(unsafeValues, world);
            return result instanceof Number number ? number.intValue() : null;
        }
        catch (NoSuchMethodException exception) {
            // Spigot and older Paper versions use NightCore's native allocator.
            return null;
        }
        catch (IllegalAccessException | InvocationTargetException exception) {
            // Keep holograms available even when a fork exposes, but cannot
            // invoke, the world-aware API from a plugin class loader.
            return null;
        }
    }
}
