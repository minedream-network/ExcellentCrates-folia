package su.nightexpress.excellentcrates.hologram.entity;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.util.pos.WorldPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class FakeEntityGroup {

    private final WorldPos         blockPos;
    private final List<FakeEntity> entities;
    private final Set<UUID>        humanViewers;
    private final Set<UUID>        hiddenViewers;
    private final Map<UUID, List<String>> renderedText;

    private volatile boolean disabled;
    private volatile boolean available;
    private volatile long    availabilityGeneration;

    public FakeEntityGroup(@NotNull WorldPos blockPos) {
        this.blockPos = blockPos;
        this.entities = new CopyOnWriteArrayList<>();
        this.humanViewers = ConcurrentHashMap.newKeySet();
        this.hiddenViewers = ConcurrentHashMap.newKeySet();
        this.renderedText = new ConcurrentHashMap<>();
        this.disabled = false;
        this.available = true;
        this.availabilityGeneration = 0L;
    }

    public void addViewer(@NotNull Player player) {
        this.humanViewers.add(player.getUniqueId());
    }

    public boolean removeViewer(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        this.renderedText.remove(playerId);
        return this.humanViewers.remove(playerId);
    }

    public boolean isViewer(@NotNull Player player) {
        return this.humanViewers.contains(player.getUniqueId());
    }

    @NotNull
    public Set<UUID> getViewerIds() {
        return Set.copyOf(this.humanViewers);
    }

    public boolean hasRenderedText(@NotNull Player player, @NotNull List<String> text) {
        return text.equals(this.renderedText.get(player.getUniqueId()));
    }

    public void setRenderedText(@NotNull Player player, @NotNull List<String> text) {
        this.renderedText.put(player.getUniqueId(), List.copyOf(text));
    }

    public void hideFor(@NotNull Player player) {
        this.hiddenViewers.add(player.getUniqueId());
    }

    public void showFor(@NotNull Player player) {
        this.hiddenViewers.remove(player.getUniqueId());
    }

    public boolean isHiddenFor(@NotNull Player player) {
        return this.hiddenViewers.contains(player.getUniqueId());
    }

    @NotNull
    public Set<UUID> clearViewers() {
        Set<UUID> viewers = Set.copyOf(this.humanViewers);
        this.humanViewers.clear();
        this.hiddenViewers.clear();
        this.renderedText.clear();
        return viewers;
    }

    public void addEntity(@NotNull FakeEntity entity) {
        this.entities.add(entity);
    }

    @NotNull
    public List<FakeEntity> getEntities() {
        return this.entities;
    }

    @NotNull
    public Set<Integer> getEntityIDs() {
        return this.entities.stream().map(FakeEntity::getId).collect(Collectors.toSet());
    }

    @NotNull
    public WorldPos getBlockPosition() {
        return this.blockPos;
    }

    public boolean isDisabled() {
        return this.disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isAvailable() {
        return this.available;
    }

    public long getAvailabilityGeneration() {
        return this.availabilityGeneration;
    }

    public synchronized long setAvailable(boolean available) {
        if (this.available != available) {
            this.available = available;
            this.availabilityGeneration++;
        }
        return this.availabilityGeneration;
    }
}
