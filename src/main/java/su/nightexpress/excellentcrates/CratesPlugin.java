package su.nightexpress.excellentcrates;

import org.bukkit.plugin.ServicePriority;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.api.addon.CratesAddon;
import su.nightexpress.excellentcrates.api.broadcast.RewardBroadcastFilterRegistry;
import su.nightexpress.excellentcrates.command.BaseCommands;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Keys;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.config.Perms;
import su.nightexpress.excellentcrates.crate.CrateManager;
import su.nightexpress.excellentcrates.crate.cost.type.impl.EcoCostType;
import su.nightexpress.excellentcrates.data.DataHandler;
import su.nightexpress.excellentcrates.data.DataManager;
import su.nightexpress.excellentcrates.dialog.DialogRegistry;
import su.nightexpress.excellentcrates.editor.EditorManager;
import su.nightexpress.excellentcrates.hologram.HologramManager;
import su.nightexpress.excellentcrates.hooks.impl.PlaceholderHook;
import su.nightexpress.excellentcrates.hooks.impl.UniItemsHook;
import su.nightexpress.excellentcrates.key.KeyManager;
import su.nightexpress.excellentcrates.opening.OpeningManager;
import su.nightexpress.excellentcrates.opening.ProviderRegistry;
import su.nightexpress.excellentcrates.opening.world.PrivateEntityVisibility;
import su.nightexpress.excellentcrates.registry.CratesRegistries;
import su.nightexpress.excellentcrates.user.UserManager;
import su.nightexpress.excellentcrates.util.CrateUtils;
import su.nightexpress.nightcore.NightPlugin;
import su.nightexpress.nightcore.commands.command.NightCommand;
import su.nightexpress.nightcore.config.PluginDetails;
import su.nightexpress.nightcore.util.Plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class CratesPlugin extends NightPlugin {

    private final List<CratesAddon> addons = new ArrayList<>();

    private DialogRegistry dialogRegistry;

    private DataHandler dataHandler;
    private DataManager dataManager;
    private UserManager userManager;

    private HologramManager hologramManager;
    private OpeningManager  openingManager;
    private KeyManager      keyManager;
    private CrateManager    crateManager;
    private EditorManager   editorManager;

    private CrateLogger crateLogger;
    private UniItemsHook uniItemsHook;
    private PrivateEntityVisibility privateEntityVisibility;
    private RewardBroadcastFilterRegistry rewardBroadcastFilterRegistry;

    @Override
    @NotNull
    protected PluginDetails getDefaultDetails() {
        return PluginDetails.create("Crates", new String[]{"crates", "ecrates", "excellentcrates", "crate", "case", "cases"})
            .setConfigClass(Config.class)
            .setPermissionsClass(Perms.class);
    }

    @Override
    protected boolean disableCommandManager() {
        return true;
    }

    @Override
    protected void onStartup() {
        CratesAPI.load(this);
        Keys.load(this);
    }

    @Override
    protected void addRegistries() {
        this.registerLang(Lang.class);
    }

    @Override
    public void enable() {
        CrateUtils.startCommandQueues();
        this.crateLogger = new CrateLogger(this);
        this.uniItemsHook = new UniItemsHook(this);
        this.privateEntityVisibility = new PrivateEntityVisibility();
        this.privateEntityVisibility.setup(this);
        this.dialogRegistry = new DialogRegistry(this);
        this.rewardBroadcastFilterRegistry = new RewardBroadcastFilterRegistry();
        this.getServer().getServicesManager().register(
            RewardBroadcastFilterRegistry.class,
            this.rewardBroadcastFilterRegistry,
            this,
            ServicePriority.Normal
        );

        ProviderRegistry.load();
        CratesRegistries.load(this);
        CratesRegistries.registerCostType(new EcoCostType(this, this.dialogRegistry));
        this.proceedAddons(CratesAddon::onInit);

        this.dataHandler = new DataHandler(this);
        this.dataHandler.setup();

        this.dataManager = new DataManager(this);
        this.dataManager.setup();

        this.userManager = new UserManager(this, this.dataHandler);
        this.userManager.setup();

        if (Config.HOLOGRAMS_ENABLED.get()) {
            this.hologramManager = new HologramManager(this);
            this.hologramManager.setup();
        }

        this.openingManager = new OpeningManager(this);
        this.openingManager.setup();

        this.keyManager = new KeyManager(this, this.dialogRegistry);
        this.keyManager.setup();

        this.crateManager = new CrateManager(this, this.dialogRegistry);
        this.crateManager.setup();

        this.editorManager = new EditorManager(this, this.dialogRegistry);
        this.editorManager.setup();

        this.dataHandler.updateRewardLimits();

        if (Plugins.hasPlaceholderAPI()) {
            PlaceholderHook.setup(this);
        }



        this.loadCommands();
        this.proceedAddons(CratesAddon::onLoad);
    }

    @Override
    public void disable() {
        CrateUtils.beginCommandQueueShutdown();
        if (this.editorManager != null) this.editorManager.shutdown();
        if (this.crateManager != null) this.crateManager.shutdown();
        if (this.openingManager != null) this.openingManager.shutdown();
        if (this.privateEntityVisibility != null) this.privateEntityVisibility.shutdown();
        if (this.keyManager != null) this.keyManager.shutdown();
        //if (this.menuManager != null) this.menuManager.shutdown();
        if (this.hologramManager != null) this.hologramManager.shutdown();
        if (this.userManager != null) this.userManager.shutdown();
        if (this.dataManager != null) this.dataManager.shutdown();
        if (this.dataHandler != null) this.dataHandler.shutdown();
        if (this.crateLogger != null) this.crateLogger.shutdown();
        if (this.dialogRegistry != null) this.dialogRegistry.clear();
        if (this.rewardBroadcastFilterRegistry != null) {
            this.getServer().getServicesManager().unregister(RewardBroadcastFilterRegistry.class, this.rewardBroadcastFilterRegistry);
            this.rewardBroadcastFilterRegistry.clear();
        }

        if (Plugins.hasPlaceholderAPI()) {
            PlaceholderHook.shutdown();
        }

        CrateUtils.shutdownCommandQueues(this);
        CratesRegistries.clear();
        ProviderRegistry.clear();
        this.rewardBroadcastFilterRegistry = null;
        this.privateEntityVisibility = null;
        this.uniItemsHook = null;
    }

    @Override
    protected void onShutdown() {
        super.onShutdown();
        Keys.clear();
        CratesAPI.clear();
    }

    private void loadCommands() {
        this.rootCommand = NightCommand.forPlugin(this, builder -> new BaseCommands(this).load(builder));
    }

    public void registerAddon(@NotNull CratesAddon addon) {
        this.addons.add(addon);
    }

    private void proceedAddons(@NotNull Consumer<CratesAddon> consumer) {
        this.addons.forEach(consumer);
    }

    @NotNull
    public List<CratesAddon> getAddons() {
        return this.addons;
    }

    public boolean hasHolograms() {
        return this.hologramManager != null && this.hologramManager.hasHandler();
    }

    @NotNull
    public Optional<HologramManager> getHologramManager() {
        return Optional.ofNullable(this.hologramManager);
    }

    @NotNull
    public CrateLogger getCrateLogger() {
        return this.crateLogger;
    }

    @NotNull
    public UniItemsHook getUniItemsHook() {
        return this.uniItemsHook;
    }

    @NotNull
    public PrivateEntityVisibility getPrivateEntityVisibility() {
        return this.privateEntityVisibility;
    }

    @NotNull
    public DataHandler getDataHandler() {
        return this.dataHandler;
    }

    @NotNull
    public DataManager getDataManager() {
        return this.dataManager;
    }

    @NotNull
    public UserManager getUserManager() {
        return this.userManager;
    }

    @NotNull
    public OpeningManager getOpeningManager() {
        return this.openingManager;
    }

    @NotNull
    public EditorManager getEditorManager() {
        return this.editorManager;
    }

    @NotNull
    public KeyManager getKeyManager() {
        return this.keyManager;
    }

    @NotNull
    public CrateManager getCrateManager() {
        return this.crateManager;
    }

    @NotNull
    public RewardBroadcastFilterRegistry getRewardBroadcastFilterRegistry() {
        return this.rewardBroadcastFilterRegistry;
    }
}
