package su.nightexpress.excellentcrates.crate;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.Placeholders;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.api.event.CrateObtainRewardEvent;
import su.nightexpress.excellentcrates.api.event.CrateOpenEvent;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.config.Keys;
import su.nightexpress.excellentcrates.config.Lang;
import su.nightexpress.excellentcrates.crate.cost.Cost;
import su.nightexpress.excellentcrates.crate.cost.CostDialogs;
import su.nightexpress.excellentcrates.crate.cost.entry.impl.KeyCostEntry;
import su.nightexpress.excellentcrates.crate.effect.CrateEffect;
import su.nightexpress.excellentcrates.crate.effect.EffectId;
import su.nightexpress.excellentcrates.crate.impl.*;
import su.nightexpress.excellentcrates.crate.listener.CrateListener;
import su.nightexpress.excellentcrates.crate.menu.MilestonesMenu;
import su.nightexpress.excellentcrates.crate.menu.OpeningAmountMenu;
import su.nightexpress.excellentcrates.crate.menu.OpeningCostMenu;
import su.nightexpress.excellentcrates.crate.menu.PreviewMenu;
import su.nightexpress.excellentcrates.crate.reward.RewardDialogs;
import su.nightexpress.excellentcrates.crate.reward.impl.CommandReward;
import su.nightexpress.excellentcrates.data.crate.GlobalCrateData;
import su.nightexpress.excellentcrates.data.crate.UserCrateData;
import su.nightexpress.excellentcrates.dialog.DialogRegistry;
import su.nightexpress.excellentcrates.dialog.cost.CostCreationDialog;
import su.nightexpress.excellentcrates.dialog.cost.CostEntryCreationDialog;
import su.nightexpress.excellentcrates.dialog.cost.CostNameDialog;
import su.nightexpress.excellentcrates.dialog.crate.*;
import su.nightexpress.excellentcrates.dialog.reward.*;
import su.nightexpress.excellentcrates.hologram.HologramTemplate;
import su.nightexpress.excellentcrates.registry.CratesRegistries;
import su.nightexpress.excellentcrates.user.CrateUser;
import su.nightexpress.excellentcrates.util.CrateUtils;
import su.nightexpress.excellentcrates.util.InteractType;
import su.nightexpress.excellentcrates.util.ItemHelper;
import su.nightexpress.excellentcrates.util.pos.WorldPos;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.bridge.paper.scheduler.FoliaScheduler;
import su.nightexpress.nightcore.core.config.CoreLang;
import su.nightexpress.nightcore.locale.message.LangMessage;
import su.nightexpress.nightcore.locale.message.MessageData;
import su.nightexpress.nightcore.locale.message.impl.ChatMessage;
import su.nightexpress.nightcore.manager.AbstractManager;
import su.nightexpress.nightcore.util.*;
import su.nightexpress.nightcore.util.text.night.wrapper.TagWrappers;
import su.nightexpress.nightcore.util.time.TimeFormatType;
import su.nightexpress.nightcore.util.time.TimeFormats;
import su.nightexpress.nightcore.util.wrapper.UniParticle;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class CrateManager extends AbstractManager<CratesPlugin> {

    private static final long EDITOR_SAVE_DELAY_TICKS    = 20L;
    private static final int  MASS_OPENINGS_PER_TICK      = 1;
    private static final int  BROADCASTS_PER_PLAYER_TICK = 8;
    private static final boolean REGIONIZED_SERVER = isRegionizedServer();

    private final DialogRegistry dialogs;

    private final Map<String, Rarity>      rarityByIdMap;
    private final Map<String, Crate>       crateByIdMap;
    private final Map<WorldPos, Crate>     crateByPosMap;
    private final Map<String, PreviewMenu> previewByIdMap;
    private final Map<UUID, Long>          previewCooldown;
    private final Map<UUID, MassOpenJob>              massOpenJobs;
    private final Set<UUID>                           pendingMassOpenings;
    private final Map<UUID, Queue<RewardBroadcast>>    rewardBroadcasts;
    private final Set<UUID>                           pendingRewardBroadcasts;
    private final AtomicBoolean                       saveQueued;
    private final FoliaScheduler                      scheduler;

    private OpeningCostMenu   costMenu;
    private OpeningAmountMenu amountMenu;
    private MilestonesMenu    milestonesMenu;

    public CrateManager(@NotNull CratesPlugin plugin, @NotNull DialogRegistry dialogs) {
        super(plugin);
        this.dialogs = dialogs;

        this.rarityByIdMap = new HashMap<>();
        this.crateByIdMap = new HashMap<>();
        this.crateByPosMap = new HashMap<>();
        this.previewByIdMap = new HashMap<>();
        this.previewCooldown = new HashMap<>();
        this.massOpenJobs = new ConcurrentHashMap<>();
        this.pendingMassOpenings = ConcurrentHashMap.newKeySet();
        this.rewardBroadcasts = new ConcurrentHashMap<>();
        this.pendingRewardBroadcasts = ConcurrentHashMap.newKeySet();
        this.saveQueued = new AtomicBoolean();
        this.scheduler = new FoliaScheduler(plugin);
    }

    @Override
    protected void onLoad() {
        this.updateHologramTemplates();

        this.loadRarities();
        this.loadPreviews();
        this.loadCrates();
        this.loadUI();
        this.loadDialogs();
        this.plugin.runTask(this::reportProblems); // After everything is loaded.

        this.addListener(new CrateListener(this.plugin, this));

        this.addTask(this::playCrateEffects, 1L);
        this.addTask(this::tickMassOpenings, 1L);
        this.addAsyncTask(this::saveCrates, Config.CRATE_SAVE_INTERVAL.get());
    }

    @Override
    protected void onShutdown() {
        this.saveCrates();

        if (this.milestonesMenu != null) this.milestonesMenu.clear();

        this.previewByIdMap.values().forEach(PreviewMenu::clear);
        this.previewByIdMap.clear();
        this.massOpenJobs.clear();
        this.pendingMassOpenings.clear();
        this.rewardBroadcasts.clear();
        this.pendingRewardBroadcasts.clear();
        this.crateByIdMap.clear();
        this.crateByPosMap.clear();
        this.rarityByIdMap.clear();
    }

    private void updateHologramTemplates() {
        FileConfig config = this.plugin.getConfig();
        if (!config.contains("Crate.Holograms.Templates")) return;

        config.remove("Crate.Holograms.TemplateList"); // Remove newly generated stuff.
        config.getSection("Crate.Holograms.Templates").forEach(sId -> {
            List<String> text = config.getStringList("Crate.Holograms.Templates." + sId);
            HologramTemplate template = new HologramTemplate(sId, text);
            template.write(config, "Crate.Holograms.TemplateList." + sId);
        });
        config.remove("Crate.Holograms.Templates"); // Remove old shit

        Config.CRATE_HOLOGRAM_TEMPLATES.read(config); // Re-read
    }

    private void loadRarities() {
        FileConfig config = this.plugin.getConfig();
        
        if (config.getSection("Rewards.Rarities").isEmpty()) {
            Set<Rarity> rarities = new HashSet<>();

            File oldFile = new File(plugin.getDataFolder(), "rarity.yml");
            if (oldFile.exists()) {
                FileConfig oldConfig = new FileConfig(oldFile);
                for (String id : oldConfig.getSection("")) {
                    rarities.add(Rarity.read(plugin, oldConfig, id, id));
                }
                oldFile.delete();
            }
            if (rarities.isEmpty()) {
                rarities.add(new Rarity(this.plugin, "common", TagWrappers.WHITE.wrap("Common"), 70));
                rarities.add(new Rarity(this.plugin, "rare", TagWrappers.GREEN.wrap("Rare"), 25));
                rarities.add(new Rarity(this.plugin, "mythic", TagWrappers.SOFT_PURPLE.wrap("Mythic"), 5));
            }

            rarities.forEach(rarity -> rarity.write(config, "Rewards.Rarities." + rarity.getId()));
        }

        config.getSection("Rewards.Rarities").forEach(rarityId -> {
            Rarity rarity = Rarity.read(this.plugin, config, "Rewards.Rarities." + rarityId, rarityId);
            this.rarityByIdMap.put(rarity.getId(), rarity);
        });

        this.plugin.info("Loaded " + this.rarityByIdMap.size() + " rarities!");
    }

    private void loadPreviews() {
        File dir = new File(plugin.getDataFolder().getAbsolutePath(), Config.DIR_PREVIEWS);
        if (!dir.exists() && dir.mkdirs()) {
            new PreviewMenu(plugin, FileConfig.loadOrExtract(plugin, Config.DIR_PREVIEWS, Placeholders.DEFAULT + ".yml"));
        }

        for (FileConfig config : FileConfig.loadAll(plugin.getDataFolder() + Config.DIR_PREVIEWS, false)) {
            PreviewMenu menu = new PreviewMenu(plugin, config);
            String id = config.getFile().getName().replace(".yml", "").toLowerCase();
            this.previewByIdMap.put(id, menu);
        }
    }

    private void loadUI() {
        this.costMenu = this.addMenu(new OpeningCostMenu(this.plugin, this), Config.DIR_UI, "crate_open_costs.yml");
        this.amountMenu = this.addMenu(new OpeningAmountMenu(this.plugin, this), Config.DIR_UI, "crate_open_amount.yml");

        if (Config.isMilestonesEnabled()) {
            this.milestonesMenu = new MilestonesMenu(this.plugin);
        }
    }

    private void loadCrates() {
        for (File file : FileUtil.getFiles(plugin.getDataFolder() + Config.DIR_CRATES, false)) {
            String id = Strings.varStyle(FileConfig.getName(file)).orElseThrow(); // TODO Handle

            Crate crate = new Crate(plugin, file.toPath(), id);
            this.loadCrate(crate);
        }
        this.plugin.info("Loaded " + this.crateByIdMap.size() + " crates.");
    }

    private void loadCrate(@NotNull Crate crate) {
        try {
            crate.load();
            this.crateByIdMap.put(crate.getId(), crate);
            this.addCratePositions(crate);
        }
        catch (IllegalStateException exception) {
            this.plugin.error("Crate '" + crate.getPath() + "' can not be loaded.");
            exception.printStackTrace();
        }
    }

    private void loadDialogs() {
        this.dialogs.register(CrateDialogs.CRATE_CREATION, CrateCreationDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_NAME, CrateNameDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_DESCRIPTION, CrateDescriptionDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_ITEM, CrateItemDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_PREVIEW, () -> new CratePreviewDialog(this.plugin));
        this.dialogs.register(CrateDialogs.CRATE_OPENING, () -> new CrateOpeningDialog(this.plugin));
        this.dialogs.register(CrateDialogs.CRATE_OPENING_LIMITS, CrateOpeningLimitsDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_REROLL, CrateRerollDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_EFFECT, () -> new CrateEffectDialog(this.dialogs));
        this.dialogs.register(CrateDialogs.CRATE_PARTICLE, CrateParticleDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_HOLOGRAM, CrateHologramDialog::new);
        this.dialogs.register(CrateDialogs.CRATE_POST_OPEN_COMMANDS, CratePostOpenCommandsDialog::new);

        this.dialogs.register(RewardDialogs.CREATION, () -> new RewardCreationDialog(this.plugin));
        this.dialogs.register(RewardDialogs.SORTING, RewardSortingDialog::new);
        this.dialogs.register(RewardDialogs.PREVIEW, RewardPreviewDialog::new);
        this.dialogs.register(RewardDialogs.ITEM, RewardItemDialog::new);
        this.dialogs.register(RewardDialogs.COMMANDS, RewardCommandsDialog::new);
        this.dialogs.register(RewardDialogs.NAME, RewardNameDialog::new);
        this.dialogs.register(RewardDialogs.DESCRIPTION, RewardDescriptionDialog::new);
        this.dialogs.register(RewardDialogs.WEIGHT, () -> new RewardWeightDialog(this.plugin));
        this.dialogs.register(RewardDialogs.PERMISSIONS, RewardPermissionsDialog::new);
        this.dialogs.register(RewardDialogs.LIMITS, RewardLimitsDialog::new);

        this.dialogs.register(CostDialogs.CREATION, CostCreationDialog::new);
        this.dialogs.register(CostDialogs.NAME, CostNameDialog::new);
        this.dialogs.register(CostDialogs.ENTRY_CREATION, CostEntryCreationDialog::new);
    }

    private void reportProblems() {
        this.getCrates().forEach(crate -> crate.collectProblems().print(this.plugin.getLogger()));
    }

    private synchronized void saveCrates() {
        this.getCrates().forEach(Crate::saveIfDirty);
    }

    public synchronized void saveCratesForce() {
        this.getCrates().forEach(Crate::saveForce);
    }

    public void queueSave() {
        if (!this.saveQueued.compareAndSet(false, true)) return;

        this.plugin.runTaskLaterAsync(() -> {
            this.saveQueued.set(false);
            this.saveCrates();
        }, EDITOR_SAVE_DELAY_TICKS);
    }

    public int countCrates() {
        return this.crateByIdMap.size();
    }

    public boolean hasCrate(@NotNull String id) {
        return this.crateByIdMap.containsKey(id);
    }

    @NotNull
    public Map<String, Rarity> getRarityByIdMap() {
        return this.rarityByIdMap;
    }

    @NotNull
    public Set<Rarity> getRarities() {
        return new HashSet<>(this.rarityByIdMap.values());
    }

    @Nullable
    public Rarity getRarity(@NotNull String id) {
        return this.rarityByIdMap.get(id.toLowerCase());
    }

    @NotNull
    public Set<String> getRarityIds() {
        return new HashSet<>(this.rarityByIdMap.keySet());
    }

    @NotNull
    public Rarity getMostCommonRarity() {
        return this.getRarities().stream().max(Comparator.comparing(Rarity::getWeight)).orElseThrow();
    }

    @NotNull
    public Map<String, PreviewMenu> getPreviewByIdMap() {
        return this.previewByIdMap;
    }

    @Nullable
    public PreviewMenu getPreviewById(@NotNull String id) {
        return this.previewByIdMap.get(id.toLowerCase());
    }

    @NotNull
    public Set<PreviewMenu> getPreviews() {
        return new HashSet<>(this.previewByIdMap.values());
    }

    @NotNull
    public List<String> getPreviewNames() {
        return new ArrayList<>(this.previewByIdMap.keySet());
    }

    public void openMilestones(@NotNull Player player, @NotNull CrateSource source) {
        if (this.milestonesMenu != null) {
            this.milestonesMenu.open(player, source);
        }
    }

    @NotNull
    public List<String> getCrateIds() {
        return new ArrayList<>(this.crateByIdMap.keySet());
    }

    @NotNull
    public Map<String, Crate> getCratesMap() {
        return this.crateByIdMap;
    }

    @NotNull
    public Set<Crate> getCrates() {
        return new HashSet<>(this.crateByIdMap.values());
    }

    public boolean isCrate(@NotNull ItemStack item) {
        return this.getCrateByItem(item) != null;
    }

    @Nullable
    public Crate getCrateById(@NotNull String id) {
        return this.crateByIdMap.get(id.toLowerCase());
    }

    @Nullable
    public Crate getCrateByItem(@NotNull ItemStack item) {
        String id = PDCUtil.getString(item, Keys.crateId).orElse(null);
        return id != null ? this.getCrateById(id) : null;
    }

    @Nullable
    public Crate getCrateByBlock(@NotNull Block block) {
        return this.getCrateByLocation(block.getLocation());
    }

    @Nullable
    public Crate getCrateByLocation(@NotNull Location location) {
        WorldPos pos = WorldPos.from(location);
        return this.crateByPosMap.get(pos);
    }

    public void removeCratePositions(@NotNull Crate crate) {
        crate.getBlockPositions().forEach(this.crateByPosMap::remove);
    }

    public void addCratePositions(@NotNull Crate crate) {
        crate.getBlockPositions().forEach(pos -> this.crateByPosMap.put(pos, crate));
    }

    public void createCrate(@NotNull String id) {
        Path path = Path.of(this.plugin.getDataFolder() + Config.DIR_CRATES, FileConfig.withExtension(id));
        FileUtil.createFileIfNotExists(path);

        Crate crate = new Crate(this.plugin, path, id);
        crate.setName(StringUtil.capitalizeUnderscored(id) + " Crate");
        crate.setDescription(new ArrayList<>());
        crate.setItem(ItemHelper.vanilla(CrateUtils.getDefaultItem(crate)));
        crate.setOpeningEnabled(true);
        crate.setOpeningId(Placeholders.DEFAULT);
        crate.setPreviewEnabled(true);
        crate.setPreviewId(Placeholders.DEFAULT);
        crate.setPushbackEnabled(true);
        crate.setHologramEnabled(true);
        crate.setHologramTemplateId(Placeholders.DEFAULT);
        crate.setEffectType(EffectId.HELIX);
        crate.setEffectParticle(UniParticle.of(Particle.CLOUD));
        crate.saveForce();

        this.loadCrate(crate);
    }

    public boolean delete(@NotNull Crate crate) {
        try {
            if (!Files.deleteIfExists(crate.getPath())) return false;
        }
        catch (IOException exception) {
            exception.printStackTrace();
            return false;
        }

        crate.removeHologram();

        this.plugin.getDataManager().handleCrateRemoval(crate);
        this.crateByIdMap.remove(crate.getId());
        this.crateByPosMap.values().removeIf(stored -> stored == crate);
        return true;
    }

    public void giveLinkTool(@NotNull Player player, @NotNull Crate crate) {
        ItemStack itemStack = Config.CRATE_LINK_TOOL.get().getItemStack();
        PDCUtil.set(itemStack, Keys.linkToolCrateId, crate.getId());
        Players.addItem(player, itemStack);
    }

    public boolean handleLinkToolInteraction(@NotNull Player player, @NotNull Block block, @NotNull ItemStack itemStack, @NotNull PlayerInteractEvent event) {
        String crateId = PDCUtil.getString(itemStack, Keys.linkToolCrateId).orElse(null);
        if (crateId == null) return false;

        itemStack.setAmount(0);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Crate crate = this.getCrateById(crateId);
        if (crate != null) {
            crate.clearBlockPositions();
            crate.addBlockPosition(block.getLocation());
            crate.recreateHologram();
            crate.markDirty();
            this.plugin.getEditorManager().openOptionsMenu(player, crate);
        }

        return true;
    }

    public boolean dropCrateItem(@NotNull Crate crate, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        world.dropItemNaturally(location, crate.getItemStack());
        return true;
    }

    public void giveCrateItem(@NotNull Player player, @NotNull Crate crate, int amount) {
        amount = Math.max(1, amount);

        ItemStack crateItem = crate.getItemStack();
        Players.addItem(player, crateItem, amount);
    }

    public void openCostMenu(@NotNull Player player, @NotNull CrateSource source) {
        this.costMenu.open(player, source);
    }

    public void openAmountMenu(@NotNull Player player, @NotNull CrateSource source, @Nullable Cost cost) {
        this.amountMenu.open(player, source, cost);
    }

    public void previewCrate(@NotNull Player player, @NotNull CrateSource source) {
        Crate crate = source.getCrate();
        if (!crate.isPreviewEnabled()) return;

        PreviewMenu menu = this.getPreviewById(crate.getPreviewId());
        if (menu == null) return;

        menu.open(player, source);
    }

    public void interactCrate(@NotNull Player player, @NotNull Crate crate, @NotNull InteractType action, @Nullable ItemStack item, @Nullable Block block) {
        CrateSource source = new CrateSource(crate, item, block);

        if (action == InteractType.CRATE_PREVIEW) {
            this.previewCrate(player, source);
            return;
        }

        this.preOpenCrate(player, source);
    }

    public void preOpenCrate(@NotNull Player player, @NotNull CrateSource source) {
        Crate crate = source.getCrate();
        boolean ignoreAnimation = Config.OPENING_SNEAK_RIGHT_CLICK_IGNORE_ANIMATION.get() && player.isSneaking();

        if (this.isAlreadyOpening(player)) {
            return;
        }

        // Check if it's possible for a player to open crates.
        if (!this.testRestrictions(player, crate)) {
            this.pushback(player, source);
            return;
        }

        if (!crate.hasCost()) {
            if (Config.MASS_OPENING_ALLOW_FOR_NO_COST.get()) {
                if (ignoreAnimation) {
                    this.openCrate(player, source, OpenOptions.of(OpenOptions.Option.IGNORE_ANIMATION), null);
                }
                else if (Config.MASS_OPENING_SNEAK_TO_USE.get() && player.isSneaking()) {
                    this.multiOpenCrate(player, source, OpenOptions.empty(), null, Config.MASS_OPENING_LIMIT.get());
                }
                else {
                    this.openAmountMenu(player, source, null);
                }
            }
            else {
                this.openCrate(player, source, OpenOptions.empty(), null);
            }
            return;
        }

        if (crate.hasMultipleCosts() || Config.OPENING_CONFIRM_FOR_SINGLE_COST.get()) {
            this.openCostMenu(player, source);
            return;
        }

        Cost cost = crate.getFirstCost().orElse(null);
        OpenOptions options = this.createOpenOptions(player, cost, ignoreAnimation);

        if (options.has(OpenOptions.Option.IGNORE_ANIMATION)) {
            this.openCrate(player, source, options, cost);
        }
        else if (Config.MASS_OPENING_SNEAK_TO_USE.get() && player.isSneaking()) {
            this.multiOpenCrate(player, source, options, cost, crate.countMaxOpenings(player));
        }
        else {
            this.openCrate(player, source, options, cost);
        }
    }

    @NotNull
    public OpenOptions createOpenOptions(@NotNull Player player, @Nullable Cost cost, boolean ignoreAnimation) {
        OpenOptions options = OpenOptions.empty();

        if (ignoreAnimation || this.shouldIgnoreAnimationForSingleKey(player, cost)) {
            options.with(OpenOptions.Option.IGNORE_ANIMATION);
        }

        return options;
    }

    private boolean shouldIgnoreAnimationForSingleKey(@NotNull Player player, @Nullable Cost cost) {
        return Config.OPENING_SINGLE_KEY_IGNORE_ANIMATION.get()
            && cost != null
            && cost.getEntries().stream().anyMatch(KeyCostEntry.class::isInstance)
            && cost.countMaxOpenings(player) == 1;
    }

    public void multiOpenCrate(@NotNull Player player, @NotNull CrateSource source, @NotNull OpenOptions options, @Nullable Cost cost, int amount) {
        int massLimit = Config.MASS_OPENING_LIMIT.get();
        int openings = Math.clamp(amount, 1, massLimit);
        options.with(OpenOptions.Option.IGNORE_ANIMATION);

        if (openings <= 1) {
            this.openCrate(player, source, options, cost);
            return;
        }

        UUID playerId = player.getUniqueId();
        MassOpenJob job = new MassOpenJob(player, source, options, cost, openings);
        if (this.massOpenJobs.putIfAbsent(playerId, job) != null) {
            Lang.CRATE_OPEN_ERROR_ALREADY.message().send(player);
        }
    }

    private void tickMassOpenings() {
        this.massOpenJobs.forEach((playerId, job) -> {
            Player player = job.player();
            if (!this.pendingMassOpenings.add(playerId)) return;

            var task = this.scheduler.runTask(player, () -> {
                try {
                    if (this.massOpenJobs.get(playerId) == job) {
                        this.runMassOpenings(job);
                    }
                }
                finally {
                    this.pendingMassOpenings.remove(playerId);
                }
            });
            if (task == null) {
                this.pendingMassOpenings.remove(playerId);
                this.massOpenJobs.remove(playerId, job);
            }
        });
    }

    private void runMassOpenings(@NotNull MassOpenJob job) {
        Player player = job.player();
        UUID playerId = player.getUniqueId();

        if (!player.isOnline()) {
            this.massOpenJobs.remove(playerId, job);
            return;
        }

        for (int count = 0; count < MASS_OPENINGS_PER_TICK && job.remaining() > 0; count++) {
            if (!this.plugin.getOpeningManager().isOpeningAvailable(player)) return;

            CrateSource source = this.resolveMassSource(player, job.source());
            if (source == null || !this.openCrate(player, source, job.options(), job.cost())) {
                this.massOpenJobs.remove(playerId, job);
                return;
            }

            job.decrement();
        }

        if (job.remaining() <= 0) {
            this.massOpenJobs.remove(playerId, job);
        }
    }

    @Nullable
    private CrateSource resolveMassSource(@NotNull Player player, @NotNull CrateSource original) {
        if (!original.hasItem()) return original;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (this.getCrateByItem(item) != original.getCrate()) return null;

        return new CrateSource(original.getCrate(), item, original.getBlockPos());
    }

    private boolean isAlreadyOpening(@NotNull Player player) {
        if (this.plugin.getOpeningManager().isOpeningAvailable(player)) {
            return false;
        }

        Lang.CRATE_OPEN_ERROR_ALREADY.message().send(player);
        return true;
    }

    private boolean testRestrictions(@NotNull Player player, @NotNull Crate crate) {
        // Wait until crate datas and reward limits are loaded.
        if (!this.plugin.getDataManager().isDataLoaded()) {
            Lang.ERROR_DATA_IS_LOADING.message().send(player);
            return false;
        }

        if (player.getInventory().firstEmpty() == -1) {
            Lang.CRATE_OPEN_ERROR_INVENTORY_SPACE.message().send(player);
            return false;
        }

        if (crate.getRewards(player).isEmpty()) {
            Lang.CRATE_OPEN_ERROR_NO_REWARDS.message().send(player, replacer -> replacer.replace(crate.replacePlaceholders()));
            return false;
        }

        return true;
    }

    public boolean openCrate(@NotNull Player player, @NotNull CrateSource source, @NotNull OpenOptions options, @Nullable Cost cost) {
        Crate crate = source.getCrate();
        CrateUser user = plugin.getUserManager().getOrFetch(player);
        UserCrateData userCrate = user.getCrateData(crate);
        Cost realCost = options.has(OpenOptions.Option.IGNORE_COST) ? null : cost;
        Opening opening = null;
        boolean reserved = false;
        boolean costTaken = false;
        boolean itemTaken = false;

        if (this.isAlreadyOpening(player)) {
            return false;
        }

        if (!this.plugin.getOpeningManager().reserveOpening(player)) {
            Lang.CRATE_OPEN_ERROR_ALREADY.message().send(player);
            return false;
        }
        reserved = true;

        try {
            if (!this.testRestrictions(player, crate)) {
                this.pushback(player, source);
                return false;
            }

            // Ensure the inventory item used to open a crate does still exist.
            if (source.getItem() != null && source.getItem().getAmount() <= 0) {
                return false;
            }

            if (!options.has(OpenOptions.Option.IGNORE_PERMISSION) && !crate.hasPermission(player)) {
                CoreLang.ERROR_NO_PERMISSION.withPrefix(this.plugin).send(player);
                this.pushback(player, source);
                return false;
            }

            if (!options.has(OpenOptions.Option.IGNORE_COOLDOWN) && crate.isOpeningCooldownEnabled() && userCrate.isOnCooldown()) {
                int openLimit = crate.getOpeningLimitAmount();
                int openStreak = userCrate.queryOpeningStreak();
                int remaining = openLimit - openStreak;

                if (remaining <= 0) {
                    (userCrate.isCooldownPermanent() ? Lang.CRATE_OPEN_ERROR_COOLDOWN_ONE_TIMED : Lang.CRATE_OPEN_ERROR_COOLDOWN_TEMPORARY).message().send(player, replacer -> replacer
                        .replace(Placeholders.GENERIC_TIME, TimeFormats.formatDuration(userCrate.getCooldownTimestamp(), TimeFormatType.LITERAL))
                        .replace(crate.replacePlaceholders())
                    );
                    this.pushback(player, source);
                    return false;
                }
            }

            if (realCost != null && !realCost.canAfford(player)) {
                Lang.CRATE_OPEN_TOO_EXPENSIVE.message().send(player, replacer -> replacer
                    .replace(crate.replacePlaceholders())
                    .replace(Placeholders.GENERIC_COSTS, () -> realCost.formatInline(", ")) // TODO Delimiter lang
                );
                this.pushback(player, source);
                return false;
            }

            CrateOpenEvent openEvent = new CrateOpenEvent(crate, player);
            plugin.getPluginManager().callEvent(openEvent);
            if (openEvent.isCancelled()) {
                this.pushback(player, source);
                return false;
            }

            player.closeInventory(); // Cheat clients must die

            opening = this.plugin.getOpeningManager().createOpening(player, source, realCost);

            if (realCost != null) {
                if (!realCost.takeAll(player)) {
                    this.plugin.getDataManager().releaseRewards(player, opening.getRewards());
                    Lang.CRATE_OPEN_TOO_EXPENSIVE.message().send(player, replacer -> replacer
                        .replace(crate.replacePlaceholders())
                        .replace(Placeholders.GENERIC_COSTS, () -> realCost.formatInline(", "))
                    );
                    return false;
                }
                costTaken = true;
            }

            ItemStack item = source.getItem();
            if (item != null) {
                item.setAmount(item.getAmount() - 1);
                itemTaken = true;
            }

            reserved = false; // startOpening consumes the reservation and owns refund/finalization from here.
            return this.plugin.getOpeningManager().startOpening(player, opening, options.has(OpenOptions.Option.IGNORE_ANIMATION));
        }
        catch (RuntimeException exception) {
            if (opening != null) {
                this.plugin.getDataManager().releaseRewards(player, opening.getRewards());
            }
            if (costTaken && realCost != null) {
                realCost.refundAll(player);
            }
            if (itemTaken) {
                Players.addItem(player, crate.getItemStack());
            }

            this.plugin.error("Could not prepare crate opening for '" + player.getName() + "'.");
            exception.printStackTrace();
            return false;
        }
        finally {
            if (reserved) {
                this.plugin.getOpeningManager().releaseOpeningReservation(player);
            }
        }
    }

    private void pushback(@NotNull Player player, @NotNull CrateSource source) {
        if (source.hasBlock() && source.getCrate().isPushbackEnabled()) {
            player.setVelocity(player.getEyeLocation().getDirection().setY(Config.CRATE_PUSHBACK_Y.get()).multiply(Config.CRATE_PUSHBACK_MULTIPLY.get()));
        }
    }

    public boolean triggerMilestones(@NotNull Player player, @NotNull Crate crate, int progress) {
        if (!crate.hasMilestones()) return false;

        int maxProgress = crate.getMaxMilestone();
        if (!crate.isMilestonesRepeatable() && progress > maxProgress) return false;

        Milestone milestone = crate.getMilestone(progress);
        if (milestone == null) return false;

        Reward reward = milestone.getReward();
        if (reward == null) return false;

        reward.giveContent(player);

        Lang.CRATE_OPEN_MILESTONE_COMPLETED.message().send(player, replacer -> replacer
            .replace(crate.replacePlaceholders())
            .replace(Placeholders.MILESTONE_OPENINGS, NumberUtil.format(progress))
            .replace(reward.replacePlaceholders())
        );

        return true;
    }

    public void giveReward(@NotNull Player player, @NotNull Reward reward) {
        if (!this.plugin.getDataManager().commitReward(player, reward)) return;

        this.giveRewardContent(player, reward);
    }

    public void giveReservedReward(@NotNull Player player, @NotNull Reward reward) {
        this.giveReservedReward(player, reward, null);
    }

    public void giveReservedReward(@NotNull Player player, @NotNull Reward reward, @Nullable List<String> commandQueue) {
        if (!this.plugin.getDataManager().commitReservedReward(player, reward)
            && !this.plugin.getDataManager().commitReward(player, reward)) return;

        this.giveRewardContent(player, reward, commandQueue);
    }

    private void giveRewardContent(@NotNull Player player, @NotNull Reward reward) {
        this.giveRewardContent(player, reward, null);
    }

    private void giveRewardContent(@NotNull Player player, @NotNull Reward reward, @Nullable List<String> commandQueue) {
        if (commandQueue != null && reward instanceof CommandReward commandReward) {
            commandQueue.addAll(commandReward.renderCommands(player));
        }
        else {
            reward.giveContent(player);
        }

        Crate crate = reward.getCrate();
        GlobalCrateData globalData = this.plugin.getDataManager().getCrateDataOrCreate(crate);

        globalData.setLatestReward(reward);
        globalData.setDirty(true);

        if (reward.isBroadcast()) {
            LangMessage message = Lang.CRATE_OPEN_REWARD_BROADCAST.message();
            if (!Config.REWARD_BROADCAST_SOUND_ENABLED.get()) {
                message = this.withoutSound(message);
            }
            LangMessage broadcastMessage = this.renderRewardBroadcast(player, crate, reward, message);

            for (Player target : this.plugin.getServer().getOnlinePlayers()) {
                this.queueRewardBroadcast(target, new RewardBroadcast(player, reward, broadcastMessage));
            }
        }

        this.plugin.getCrateLogger().logReward(player, reward);

        CrateObtainRewardEvent event = new CrateObtainRewardEvent(reward, player);
        this.plugin.getPluginManager().callEvent(event);
    }

    private void queueRewardBroadcast(@NotNull Player target, @NotNull RewardBroadcast broadcast) {
        UUID targetId = target.getUniqueId();
        Queue<RewardBroadcast> queue = this.rewardBroadcasts.computeIfAbsent(targetId, ignored -> new ConcurrentLinkedQueue<>());
        queue.add(broadcast);
        this.scheduleRewardBroadcasts(target, targetId, queue);
    }

    private void scheduleRewardBroadcasts(@NotNull Player target,
                                          @NotNull UUID targetId,
                                          @NotNull Queue<RewardBroadcast> queue) {
        if (!this.pendingRewardBroadcasts.add(targetId)) return;

        var task = this.scheduler.runTask(target, () -> {
            try {
                for (int count = 0; count < BROADCASTS_PER_PLAYER_TICK; count++) {
                    RewardBroadcast broadcast = queue.poll();
                    if (broadcast == null) break;

                    try {
                        if (target.isOnline() && this.canReceiveRewardBroadcast(target, broadcast)) {
                            broadcast.message().send(target);
                        }
                    }
                    catch (RuntimeException exception) {
                        this.plugin.error("Could not broadcast reward '" + broadcast.reward().getId() + "' to '" + target.getName() + "'.");
                        exception.printStackTrace();
                    }
                }
            }
            finally {
                this.pendingRewardBroadcasts.remove(targetId);
                if (queue.isEmpty()) {
                    this.rewardBroadcasts.compute(targetId, (ignored, current) -> current == queue && queue.isEmpty() ? null : current);
                }
                else {
                    this.scheduleRewardBroadcasts(target, targetId, queue);
                }
            }
        });

        if (task == null) {
            this.pendingRewardBroadcasts.remove(targetId);
            this.rewardBroadcasts.remove(targetId, queue);
        }
    }

    private boolean canReceiveRewardBroadcast(@NotNull Player target, @NotNull RewardBroadcast broadcast) {
        var registry = this.plugin.getRewardBroadcastFilterRegistry();
        if (!REGIONIZED_SERVER || target.getUniqueId().equals(broadcast.opener().getUniqueId())) {
            return registry.shouldReceive(target, broadcast.opener(), broadcast.reward());
        }

        // shortcut: Legacy filters need two live Players; fail closed cross-region until a snapshot filter API exists.
        return !registry.hasFilters();
    }

    private static boolean isRegionizedServer() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        }
        catch (ClassNotFoundException exception) {
            return false;
        }
    }

    @NotNull
    private LangMessage renderRewardBroadcast(@NotNull Player opener,
                                              @NotNull Crate crate,
                                              @NotNull Reward reward,
                                              @NotNull LangMessage message) {
        MessageData data = this.getMessageData(message);
        if (data == null) return message;

        Replacer replacer = Replacer.create();
        this.replaceRewardBroadcast(replacer, opener, crate, reward);

        LangMessage rendered = LangMessage.createFromData(replacer.apply(message.getText()), data);
        String prefix = this.getMessagePrefix(message);
        return prefix != null && rendered instanceof ChatMessage chatMessage ? chatMessage.withPrefix(prefix) : rendered;
    }

    @NotNull
    private LangMessage withoutSound(@NotNull LangMessage message) {
        MessageData data = this.getMessageData(message);
        if (data == null) return message;

        MessageData silentData = new MessageData(data.type(), data.usePrefix(), data.replacePlaceholders(), null, data.titleTimes());
        LangMessage silentMessage = LangMessage.createFromData(message.getText(), silentData);

        String prefix = this.getMessagePrefix(message);
        return prefix != null && silentMessage instanceof ChatMessage chatMessage ? chatMessage.withPrefix(prefix) : silentMessage;
    }

    @Nullable
    private MessageData getMessageData(@NotNull LangMessage message) {
        try {
            Field field = LangMessage.class.getDeclaredField("data");
            field.setAccessible(true);
            return (MessageData) field.get(message);
        }
        catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    @Nullable
    private String getMessagePrefix(@NotNull LangMessage message) {
        if (!(message instanceof ChatMessage)) return null;

        try {
            Field field = ChatMessage.class.getDeclaredField("prefix");
            field.setAccessible(true);
            return (String) field.get(message);
        }
        catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private void replaceRewardBroadcast(@NotNull Replacer replacer, @NotNull Player opener, @NotNull Crate crate, @NotNull Reward reward) {
        replacer
            .replace(Placeholders.forPlayerWithPAPI(opener))
            .replace(crate.replacePlaceholders())
            .replace(reward.replacePlaceholders());
    }

    public void addRollCount(@NotNull Player player, @NotNull Reward reward) {
        this.plugin.getDataManager().commitReward(player, reward);
    }

    public void setPreviewCooldown(@NotNull Player player) {
        long timestamp = System.currentTimeMillis() + Config.CRATE_PREVIEW_COOLDOWN.get();
        this.previewCooldown.put(player.getUniqueId(), timestamp);
    }

    public long getPreviewCooldown(@NotNull Player player) {
        long timestamp = this.previewCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (System.currentTimeMillis() < timestamp) {
            return timestamp;
        }

        this.removePreviewCooldown(player);
        return 0L;
    }

    public boolean hasPreviewCooldown(@NotNull Player player) {
        return this.getPreviewCooldown(player) > 0L;
    }

    public void removePreviewCooldown(@NotNull Player player) {
        this.previewCooldown.remove(player.getUniqueId());
    }

    public void playCrateEffects() {
        this.getCrates().forEach(crate -> {
            if (!crate.isEffectEnabled()) return;

            CrateEffect effect = crate.getEffect();
            if (effect.isDummy()) return;

            int step = effect.getStep();
            if (step < 0) return;

            UniParticle particle = crate.getEffectParticle();

            crate.getBlockPositions().forEach(worldPos -> {
                Location location = worldPos.toLocation();
                if (location == null) return;

                CrateUtils.forEachPlayerInEffectRange(this.plugin, location, player -> effect.playStep(location, particle, step, player));
            });
        });

        CratesRegistries.getEffects().forEach(CrateEffect::addTickCount);
    }

    private static final class MassOpenJob {

        private final Player      player;
        private final CrateSource source;
        private final OpenOptions options;
        private final Cost        cost;

        private int remaining;

        private MassOpenJob(@NotNull Player player,
                            @NotNull CrateSource source,
                            @NotNull OpenOptions options,
                            @Nullable Cost cost,
                            int remaining) {
            this.player = player;
            this.source = source;
            this.options = options;
            this.cost = cost;
            this.remaining = remaining;
        }

        @NotNull
        private Player player() {
            return this.player;
        }

        @NotNull
        private CrateSource source() {
            return this.source;
        }

        @NotNull
        private OpenOptions options() {
            return this.options;
        }

        @Nullable
        private Cost cost() {
            return this.cost;
        }

        private int remaining() {
            return this.remaining;
        }

        private void decrement() {
            this.remaining--;
            assert this.remaining >= 0 : "Mass opening remaining count became negative";
        }
    }

    private record RewardBroadcast(@NotNull Player opener, @NotNull Reward reward, @NotNull LangMessage message) {}
}
