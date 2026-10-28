package su.nightexpress.excellentcrates.opening;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.excellentcrates.api.opening.OpeningProvider;
import su.nightexpress.excellentcrates.api.opening.ProviderLoader;
import su.nightexpress.excellentcrates.api.opening.ProviderSupplier;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.impl.Crate;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.opening.world.provider.DummyProvider;
import su.nightexpress.excellentcrates.util.CrateUtils;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.bridge.paper.scheduler.FoliaScheduler;
import su.nightexpress.nightcore.util.FileUtil;
import su.nightexpress.nightcore.util.Players;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OpeningManager extends AbstractManager<CratesPlugin> {

    private final Map<String, OpeningProvider> providerByIdMap;
    private final Map<UUID, Opening>           openingByPlayerMap;
    private final Set<UUID>                    openingReservations;
    private final Set<UUID>                    pendingTicks;
    private final Set<UUID>                    recoveringRefunds;
    private final FoliaScheduler               scheduler;
    private final Object                       pendingRefundLock;

    private final DummyProvider dummyProvider;
    private final RerollMenu    rerollMenu;
    private FileConfig          pendingRefundConfig;

    public OpeningManager(@NotNull CratesPlugin plugin) {
        super(plugin);
        this.providerByIdMap = new HashMap<>();
        this.openingByPlayerMap = new ConcurrentHashMap<>();
        this.openingReservations = ConcurrentHashMap.newKeySet();
        this.pendingTicks = ConcurrentHashMap.newKeySet();
        this.recoveringRefunds = ConcurrentHashMap.newKeySet();
        this.scheduler = new FoliaScheduler(plugin);
        this.pendingRefundLock = new Object();
        this.dummyProvider = new DummyProvider(plugin);
        this.rerollMenu = new RerollMenu(plugin);
    }

    @Override
    protected void onLoad() {
        this.rerollMenu.load();
        this.loadDefaults();
        this.loadProviders();
        this.pendingRefundConfig = new FileConfig(new File(this.plugin.getDataFolder(), "pending-refunds.yml"));

        this.addListener(new OpeningListener(this.plugin, this));

        this.addTask(this::tickOpenings, 1L);
        this.plugin.runTask(() -> this.plugin.getServer().getOnlinePlayers().forEach(player -> {
            this.plugin.runTask(player, () -> {
                this.recoverPendingRefund(player);
                CrateUtils.resumeCommandQueue(this.plugin, player);
            });
        }));
    }

    @Override
    protected void onShutdown() {
        Map<UUID, Opening> openings = new HashMap<>(this.openingByPlayerMap);
        if (isFolia() && !openings.isEmpty()) {
            this.journalPendingRefunds(openings);
        }
        else {
            openings.values().forEach(this::forceStop);
        }
        this.providerByIdMap.clear();
        this.openingByPlayerMap.clear();
        this.openingReservations.clear();
        this.pendingTicks.clear();
        this.recoveringRefunds.clear();
        this.pendingRefundConfig = null;
    }

    @NotNull
    private String getDirectoryPath(@NotNull String dirName) {
        return this.plugin.getDataFolder() + dirName;
    }

    private void loadDefaults() {
        File dir = new File(this.getDirectoryPath(Config.DIR_OPENINGS));
        if (dir.exists()) return;

        this.loadProvider("csgo", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupCSGO);
        this.loadProvider("enclosing", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupEnclosing);
        this.loadProvider("mystery", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupMystery);
        this.loadProvider("roulette", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupRoulette);
        this.loadProvider("storm", Config.DIR_OPENINGS_INVENTORY, OpeningUtils::setupStorm);

        this.loadProvider("simple_roll", Config.DIR_OPENINGS_SIMPLE_ROLL, OpeningUtils::createSimpleRoll);

        this.loadProvider("selective_1", Config.DIR_OPENINGS_SELECTABLE, OpeningUtils::createSelectableSingle);
        this.loadProvider("selective_3", Config.DIR_OPENINGS_SELECTABLE, OpeningUtils::createSelectableTriple);
    }

    public void loadProviders() {
        // Load providers stored in the openings directory by native or externally added loaders.
        for (ProviderLoader loader : ProviderRegistry.getLoaders()) {
            this.loadProviders(loader);
        }

        // Load externally added independend opening providers.
        for (OpeningProvider provider : ProviderRegistry.getProviders()) {
            this.loadProvider(provider);
        }

        this.plugin.info("Loaded " + this.providerByIdMap.size() + " crate openings.");
    }

    public void loadProviders(@NotNull ProviderLoader loader) {
        String dirName = loader.getDirectory();
        ProviderSupplier supplier = loader.getSupplier();

        for (File file : FileUtil.getConfigFiles(this.getDirectoryPath(dirName))) {
            this.loadProvider(file, supplier);
        }
    }

    public void loadProvider(@NotNull String id, @NotNull String dirName, @NotNull ProviderSupplier supplier) {
        File file = new File(this.getDirectoryPath(dirName), FileConfig.withExtension(id));
        this.loadProvider(file, supplier);
    }

    public void loadProvider(@NotNull File file, @NotNull ProviderSupplier supplier) {
        FileConfig config = new FileConfig(file);
        String name = FileConfig.getName(file);

        OpeningProvider provider = supplier.supply(this.plugin, name);
        try {
            provider.load(config);
            config.saveChanges();
        }
        catch (IllegalArgumentException exception) {
            this.plugin.error("Opening provider '" + name + "' can not be loaded: " + exception.getMessage());
            return;
        }

        this.loadProvider(provider);
    }

    public void loadProvider(@NotNull OpeningProvider provider) {
        this.providerByIdMap.put(provider.getId(), provider);
    }

    @NotNull
    public Map<String, OpeningProvider> getProviderByIdMap() {
        return this.providerByIdMap;
    }

    @NotNull
    public Set<OpeningProvider> getProviders() {
        return new HashSet<>(this.providerByIdMap.values());
    }

    @NotNull
    public Set<String> getProviderIds() {
        return new HashSet<>(this.providerByIdMap.keySet());
    }

    @Nullable
    public OpeningProvider getProviderById(@NotNull String id) {
        return this.providerByIdMap.get(id.toLowerCase());
    }

    @NotNull
    public Map<UUID, Opening> getOpeningByPlayerIdMap() {
        return this.openingByPlayerMap;
    }

    @NotNull
    public Set<Opening> getOpenings() {
        return new HashSet<>(this.openingByPlayerMap.values());
    }

    @Nullable
    public Opening getOpening(@NotNull Player player) {
        return this.openingByPlayerMap.get(player.getUniqueId());
    }

    public void tickOpenings() {
        this.getOpenings().forEach(opening -> {
            UUID playerId = opening.getPlayer().getUniqueId();
            if (!this.pendingTicks.add(playerId)) return;

            var task = this.scheduler.runTask(opening.getPlayer(), () -> {
                try {
                    if (this.openingByPlayerMap.get(playerId) == opening) {
                        opening.tick();
                    }
                }
                catch (RuntimeException exception) {
                    this.plugin.error("Could not tick crate opening for '" + opening.getPlayer().getName() + "'.");
                    exception.printStackTrace();

                    if (opening instanceof AbstractOpening abstractOpening) {
                        this.abortStart(abstractOpening);
                    }
                    else {
                        opening.stop();
                        this.removeOpening(opening.getPlayer(), opening);
                    }
                }
                finally {
                    this.pendingTicks.remove(playerId);
                }
            });
            if (task == null) {
                this.pendingTicks.remove(playerId);
            }
        });
    }

    @NotNull
    public RerollMenu getRerollMenu() {
        return this.rerollMenu;
    }

    public boolean isOpening(@NotNull Player player) {
        return this.getOpening(player) != null;
    }

    public void stopOpening(@NotNull Player player) {
        Opening opening = this.getOpening(player);
        if (opening == null) return;

        this.forceStop(opening);
    }

    @Nullable
    public Opening removeOpening(@NotNull Player player) {
        return this.openingByPlayerMap.remove(player.getUniqueId());
    }

    public boolean removeOpening(@NotNull Player player, @NotNull Opening opening) {
        return this.openingByPlayerMap.remove(player.getUniqueId(), opening);
    }

    public boolean isOpeningAvailable(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        return !this.hasPendingRefund(playerId)
            && !this.openingReservations.contains(playerId)
            && !this.openingByPlayerMap.containsKey(playerId);
    }

    public boolean reserveOpening(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (this.hasPendingRefund(playerId) || this.openingByPlayerMap.containsKey(playerId)) return false;

        return this.openingReservations.add(playerId);
    }

    public void releaseOpeningReservation(@NotNull Player player) {
        this.openingReservations.remove(player.getUniqueId());
    }

    private void forceStop(@NotNull Opening opening) {
        if (opening instanceof AbstractOpening abstractOpening) {
            abstractOpening.forceStop();
        }
        else {
            opening.stop();
            this.removeOpening(opening.getPlayer(), opening);
        }
    }

    private void abortStart(@NotNull AbstractOpening opening) {
        try {
            opening.abortStart();
        }
        catch (RuntimeException exception) {
            this.plugin.error("Could not fully refund aborted crate opening for '" + opening.getPlayer().getName() + "'.");
            exception.printStackTrace();
        }
    }

    public void recoverPendingRefund(@NotNull Player player) {
        FileConfig config = this.pendingRefundConfig;
        UUID playerId = player.getUniqueId();
        if (config == null || !this.recoveringRefunds.add(playerId)) return;

        String path = "Pending." + playerId;
        try {
            Cost cost;
            ItemStack item;
            boolean costRefunded;
            boolean itemRefunded;
            int refundedCostEntries;
            synchronized (this.pendingRefundLock) {
                if (!config.getSection("Pending").contains(playerId.toString())) return;

                costRefunded = config.get(path + ".CostRefunded", Boolean.class, false);
                itemRefunded = config.get(path + ".ItemRefunded", Boolean.class, false);
                refundedCostEntries = config.get(path + ".RefundedCostEntries", Integer.class, 0);
                String costId = config.getString(path + ".CostId", "");
                if (!costRefunded && costId.isBlank()) {
                    throw new IllegalStateException("Pending cost refund data is missing.");
                }
                cost = costId.isBlank() ? null : Cost.read(config, path + ".Cost", costId);
                int costEntries = config.get(path + ".CostEntries", Integer.class, 0);
                if (cost != null && cost.getEntries().size() != costEntries) {
                    throw new IllegalStateException("Not all pending cost entries are available for refund.");
                }
                if (cost != null && cost.hasInvalids()) {
                    throw new IllegalStateException("A pending cost entry is currently unavailable for refund.");
                }
                item = config.getItemEncoded(path + ".Item");
                if (!itemRefunded && (item == null || item.getType().isAir())) {
                    throw new IllegalStateException("Pending item refund data is missing.");
                }
            }

            if (!costRefunded && cost != null) {
                for (int index = refundedCostEntries; index < cost.getEntries().size(); index++) {
                    cost.getEntries().get(index).refund(player);
                    synchronized (this.pendingRefundLock) {
                        config.set(path + ".RefundedCostEntries", index + 1);
                        config.save();
                    }
                }
                synchronized (this.pendingRefundLock) {
                    config.set(path + ".CostRefunded", true);
                    config.save();
                }
            }
            if (!itemRefunded && item != null && !item.getType().isAir()) {
                Players.addItem(player, item);
                synchronized (this.pendingRefundLock) {
                    config.set(path + ".ItemRefunded", true);
                    config.save();
                }
            }

            synchronized (this.pendingRefundLock) {
                config.remove(path);
                config.save();
            }
        }
        catch (RuntimeException exception) {
            this.plugin.error("Could not recover pending crate refund for '" + player.getName() + "'. It will be retried on the next join.");
            exception.printStackTrace();
        }
        finally {
            this.recoveringRefunds.remove(playerId);
        }
    }

    private boolean hasPendingRefund(@NotNull UUID playerId) {
        FileConfig config = this.pendingRefundConfig;
        if (config == null) return false;

        synchronized (this.pendingRefundLock) {
            return config.getSection("Pending").contains(playerId.toString());
        }
    }

    private void journalPendingRefunds(@NotNull Map<UUID, Opening> openings) {
        FileConfig config = this.pendingRefundConfig;
        if (config == null) return;

        synchronized (this.pendingRefundLock) {
            openings.forEach((playerId, opening) -> {
                String path = "Pending." + playerId;
                Cost cost = opening.getCost();
                ItemStack item = opening.getSource().getItem() == null ? null : opening.getCrate().getItemStack();

                config.set(path + ".CostRefunded", cost == null);
                config.set(path + ".RefundedCostEntries", 0);
                config.set(path + ".ItemRefunded", item == null);
                if (cost != null) {
                    config.set(path + ".CostId", cost.getId());
                    config.set(path + ".CostEntries", cost.getEntries().size());
                    config.set(path + ".Cost", cost);
                }
                if (item != null) {
                    config.setItemEncoded(path + ".Item", item);
                }
            });
            config.save();
        }
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        }
        catch (ClassNotFoundException exception) {
            return false;
        }
    }

    @NotNull
    public Opening createOpening(@NotNull Player player, @NotNull CrateSource source, @Nullable Cost cost) {
        Crate crate = source.getCrate();
        OpeningProvider provider = null;

        if (crate.isOpeningEnabled()) {
            provider = this.getProviderById(crate.getOpeningId());
        }
        if (provider == null) provider = this.dummyProvider;

        return provider.createOpening(player, source, cost);
    }

    public boolean startOpening(@NotNull Player player, @NotNull Opening opening, boolean instaRoll) {
        UUID playerId = player.getUniqueId();
        if (!this.openingReservations.remove(playerId) || this.openingByPlayerMap.putIfAbsent(playerId, opening) != null) {
            if (opening instanceof AbstractOpening abstractOpening) {
                this.abortStart(abstractOpening);
            }
            else {
                opening.stop();
            }
            return false;
        }

        if (opening instanceof AbstractOpening abstractOpening) {
            abstractOpening.setIgnoreAnimationOnReroll(instaRoll);
        }

        try {
            opening.start(); // Start ticking

            if (instaRoll) opening.instaRoll();
            return true;
        }
        catch (RuntimeException exception) {
            this.plugin.error("Could not start crate opening for '" + player.getName() + "'.");
            exception.printStackTrace();

            if (opening instanceof AbstractOpening abstractOpening) {
                this.abortStart(abstractOpening);
            }
            else {
                opening.stop();
                this.removeOpening(player, opening);
            }
            return false;
        }
    }
}
