package su.nightexpress.excellentcrates.hologram.entity;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

public class FakeEntity {

    private final int      id;
    private final Location location;

    public FakeEntity(int id, @NotNull Location location) {
        this.id = id;
        this.location = location;
    }

    @NotNull
    public static FakeEntity create(@NotNull Location location) {
        return new FakeEntity(EntityIdAllocator.next(location.getWorld()), location);
    }

    public int getId() {
        return this.id;
    }

    @NotNull
    public Location getLocation() {
        return this.location;
    }
}
