package su.nightexpress.excellentcrates.hologram;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.hologram.entity.FakeDisplay;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntity;
import su.nightexpress.excellentcrates.hologram.entity.FakeEntityGroup;
import su.nightexpress.excellentcrates.hologram.handler.HologramPacketsHandler;
import su.nightexpress.excellentcrates.hologram.handler.HologramProtocolHandler;
import su.nightexpress.excellentcrates.hologram.listener.HologramListener;
import su.nightexpress.excellentcrates.hooks.HookId;
import su.nightexpress.excellentcrates.util.CrateUtils;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.bridge.paper.scheduler.FoliaScheduler;
import su.nightexpress.nightcore.util.LocationUtil;
import su.nightexpress.nightcore.util.Plugins;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class HologramManager extends AbstractManager<CratesPlugin> {

    private final Map<String, FakeDisplay> displayMap;
    private final FoliaScheduler           scheduler;
    private final AtomicLong               generation;

    private volatile HologramHandler handler;

    public HologramManager(@NotNull CratesPlugin plugin) {
        super(plugin);
        this.displayMap = new ConcurrentHashMap<>();
        this.scheduler = new FoliaScheduler(plugin);
        this.generation = new AtomicLong();
    }

    @Override
    protected void onLoad() {
        this.generation.incrementAndGet();

        if (this.detectHandler()) {
            this.addListener(new HologramListener(this.plugin, this));

            this.addTask(this::tickHolograms, Config.CRATE_HOLOGRAM_UPDATE_INTERVAL.get());
        }
    }

    @Override
    protected void onShutdown() {
        this.generation.incrementAndGet();

        this.displayMap.values().forEach(this::discard);
        this.displayMap.clear();

        this.handler = null;
    }

    private boolean detectHandler() {
        if (Plugins.isInstalled(HookId.PACKET_EVENTS)) {
            this.handler = new HologramPacketsHandler();
        }
        else if (Plugins.isInstalled(HookId.PROTOCOL_LIB)) {
            this.handler = new HologramProtocolHandler();
        }
        else {
            this.plugin.warn("*".repeat(25));
            this.plugin.warn("You have no packet library plugins installed for the Holograms feature to work.");
            this.plugin.warn("Please install one of the following plugins to enable crate holograms: " + HookId.PACKET_EVENTS + " or " + HookId.PROTOCOL_LIB);
            this.plugin.warn("*".repeat(25));
        }

        return this.hasHandler();
    }

    private void tickHolograms() {
        this.plugin.getCrateManager().getCrates().forEach(crate -> {
            if (!crate.isHologramEnabled()) return;

            this.renderNow(crate);
        });
    }

    public boolean hasHandler() {
        return this.handler != null;
    }

    @Nullable
    private FakeDisplay getDisplay(@NotNull Crate crate) {
        return this.displayMap.get(crate.getId());
    }

    public void disableBlockHologram(@NotNull Crate crate, @NotNull WorldPos blockPos) {
        this.toggleBlockHologram(crate, blockPos, false);
    }

    public void enableBlockHologram(@NotNull Crate crate, @NotNull WorldPos blockPos) {
        this.toggleBlockHologram(crate, blockPos, true);
    }

    public void hideBlockHologram(@NotNull Player player, @NotNull Crate crate, @NotNull WorldPos blockPos) {
        FakeDisplay display = this.getDisplay(crate);
        if (display == null) return;

        FakeEntityGroup group = display.getGroup(blockPos);
        if (group == null) return;

        group.hideFor(player);
        this.removeForViewer(player, group);
    }

    public void showBlockHologram(@NotNull Player player, @NotNull Crate crate, @NotNull WorldPos blockPos) {
        FakeDisplay display = this.getDisplay(crate);
        if (display == null) return;

        FakeEntityGroup group = display.getGroup(blockPos);
        if (group == null) return;

        group.showFor(player);

        if (!group.isDisabled()) {
            this.render(crate);
        }
    }

    private void toggleBlockHologram(@NotNull Crate crate, @NotNull WorldPos blockPos, boolean enabled) {
        FakeDisplay display = this.getDisplay(crate);
        if (display == null) return;

        FakeEntityGroup group = display.getGroup(blockPos);
        if (group == null) return;

        group.setDisabled(!enabled);

        if (group.isDisabled()) {
            this.discard(group);
        } else {
            this.render(crate);
        }
    }

    public void removeForViewer(@NotNull Player player) {
        this.displayMap.values().forEach(display -> this.removeForViewer(player, display));
    }

    public void removeForViewer(@NotNull Player player, @NotNull FakeDisplay display) {
        display.getGroups().forEach(group -> this.removeForViewer(player, group));
    }

    public void removeForViewer(@NotNull Player player, @NotNull FakeEntityGroup group) {
        if (!group.removeViewer(player)) return;

        HologramHandler handler = this.handler;
        if (handler != null) {
            handler.sendDestroyEntityPacket(player, group.getEntityIDs());
        }
    }

    public void discard(@NotNull Crate crate) {
        FakeDisplay display = this.displayMap.remove(crate.getId());
        if (display == null) return;

        this.discard(display);
    }

    public void discard(@NotNull FakeDisplay display) {
        display.getGroups().forEach(this::discard);
    }

    public void discard(@NotNull FakeEntityGroup group) {
        long groupGeneration = group.setAvailable(false);

        Set<UUID> viewers = group.clearViewers();
        if (viewers.isEmpty()) return;

        HologramHandler handler = this.handler;
        Set<Integer> entityIds = group.getEntityIDs();
        if (handler == null || entityIds.isEmpty()) return;

        this.scheduler.runTask(() -> viewers.forEach(viewerId -> {
            Player player = Bukkit.getPlayer(viewerId);
            if (player == null) return;

            this.scheduler.runTask(player, () -> {
                if (group.getAvailabilityGeneration() == groupGeneration) {
                    handler.sendDestroyEntityPacket(player, entityIds);
                }
            });
        }));
    }

    public void render(@NotNull Crate crate) {
        long generation = this.generation.get();
        this.scheduler.runTask(() -> {
            if (this.generation.get() == generation) {
                this.renderNow(crate);
            }
        });
    }

    private void renderNow(@NotNull Crate crate) {
        long generation = this.generation.get();
        this.createIfAbsent(crate);

        FakeDisplay display = this.getDisplay(crate);
        if (display == null) return;

        List<String> text = List.copyOf(Replacer.create().replace(crate.replacePlaceholders()).apply(crate.getHologramText().reversed()));
        if (text.isEmpty()) return;

        Map<UUID, Player> onlinePlayers = new HashMap<>();
        Bukkit.getOnlinePlayers().forEach(player -> onlinePlayers.put(player.getUniqueId(), player));

        for (FakeEntityGroup group : display.getGroups()) {
            if (group.isDisabled()) continue;

            WorldPos blockPosition = group.getBlockPosition();
            Location location = blockPosition.toLocation();
            if (location == null) continue;

            this.scheduler.runTask(location, () -> {
                if (this.generation.get() != generation) return;

                World world = location.getWorld();
                int chunkX = blockPosition.getX() >> 4;
                int chunkZ = blockPosition.getZ() >> 4;
                if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) {
                    this.discard(group); // Remove all viewers and send entity destroy packet.
                    return;
                }

                long groupGeneration = group.setAvailable(true);
                Set<Player> players = new HashSet<>(world.getPlayersSeeingChunk(chunkX, chunkZ));
                group.getViewerIds().stream().map(onlinePlayers::get).filter(Objects::nonNull).forEach(players::add);
                players.forEach(player -> this.scheduler.runTask(player, () -> this.renderForViewer(player, group, location, text, generation, groupGeneration)));
            });
        }
    }

    private void renderForViewer(@NotNull Player player,
                                 @NotNull FakeEntityGroup group,
                                 @NotNull Location location,
                                 @NotNull List<String> text,
                                 long generation,
                                 long groupGeneration) {
        if (this.generation.get() != generation || group.getAvailabilityGeneration() != groupGeneration) return;

        if (!group.isAvailable() || group.isDisabled() || group.isHiddenFor(player) || !CrateUtils.isInEffectRange(player, location)) {
            this.removeForViewer(player, group);
            return;
        }

        boolean needSpawn = !group.isViewer(player);
        List<FakeEntity> holograms = group.getEntities();
        if (holograms.isEmpty()) return;

        List<String> renderedText = new ArrayList<>(holograms.size());
        List<String> hologramText = Replacer.create().replacePlaceholderAPI(player).apply(text);

        for (int index = 0; index < holograms.size(); index++) {
            renderedText.add(index >= hologramText.size() ? "" : hologramText.get(index));
        }

        if (!needSpawn && group.hasRenderedText(player, renderedText)) return;

        HologramHandler handler = this.handler;
        if (handler == null) return;

        if (needSpawn && groupGeneration > 0) {
            handler.sendDestroyEntityPacket(player, group.getEntityIDs());
        }

        for (int index = 0; index < holograms.size(); index++) {
            handler.sendHologramPackets(player, holograms.get(index), needSpawn, renderedText.get(index));
        }

        group.addViewer(player);
        group.setRenderedText(player, renderedText);
    }

    private void createIfAbsent(@NotNull Crate crate) {
        if (!this.hasHandler()) return;
        if (this.displayMap.containsKey(crate.getId())) return;

        List<String> originText = crate.getHologramText();
        if (originText.isEmpty()) return;

        FakeDisplay display = new FakeDisplay();
        if (this.displayMap.putIfAbsent(crate.getId(), display) != null) return;

        double yOffset = crate.getHologramYOffset() + 0.2;
        double lineGap = Config.CRATE_HOLOGRAM_LINE_GAP.get();
        long generation = this.generation.get();

        boolean scheduled = false;
        for (WorldPos blockPos : crate.getBlockPositions()) {
            Location loc = blockPos.toLocation();
            if (loc == null) continue;

            scheduled = true;
            this.scheduler.runTask(loc, () -> {
                if (this.generation.get() != generation) return;

                Block block = loc.getBlock();

                double height = block.getBoundingBox().getHeight() / 2D + yOffset;

                // Allocate ID values for our fake entities, so there is no clash with new server entities.

                FakeEntityGroup group = display.getGroupOrCreate(blockPos);

                for (int index = 0; index < originText.size(); index++) {
                    double gap = lineGap * index;

                    Location location = LocationUtil.setCenter3D(block.getLocation()).add(0, height + gap, 0);
                    group.addEntity(FakeEntity.create(location));
                }
            });
        }

        if (!scheduled) {
            this.displayMap.remove(crate.getId(), display);
        }
    }
}
