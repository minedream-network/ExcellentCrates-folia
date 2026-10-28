package su.nightexpress.excellentcrates.opening;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.Placeholders;
import su.nightexpress.excellentcrates.api.crate.Reward;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.menu.PreviewMenu;
import su.nightexpress.nightcore.config.ConfigValue;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.util.BukkitThing;
import su.nightexpress.nightcore.util.ItemUtil;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.text.NightMessage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RerollMenu {

    private static final String FILE_NAME = "crate_reroll.yml";

    private final CratesPlugin plugin;

    private String title;
    private int    size;

    private MenuItemSettings filler;
    private MenuItemSettings confirm;
    private MenuItemSettings reroll;
    private MenuItemSettings rerollUnavailable;
    private MenuItemSettings reward;

    public RerollMenu(@NotNull CratesPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(this.plugin.getDataFolder() + Config.DIR_UI, FILE_NAME);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (!file.exists()) {
            try {
                file.createNewFile();
            }
            catch (IOException exception) {
                throw new IllegalStateException("Could not create reroll GUI file: " + file, exception);
            }
        }

        FileConfig config = new FileConfig(file);

        this.title = ConfigValue.create("Title", "&2Confirm Reward").read(config);
        this.size = normalizeSize(ConfigValue.create("Size", 27).read(config));

        this.filler = MenuItemSettings.read(config, "Filler", Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), allSlots(), true, false);
        this.confirm = MenuItemSettings.read(config, "Confirm", Material.LIME_CONCRETE, "&aConfirm", List.of(
            "&7Click to receive this reward."
        ), new int[]{11}, true, false);
        this.reroll = MenuItemSettings.read(config, "Reroll.Available", Material.REDSTONE, "&eReroll", List.of(
            "&7Roll a different reward.",
            "&7Rerolls left: &f%rerolls_left%"
        ), new int[]{15}, true, false);
        this.rerollUnavailable = MenuItemSettings.read(config, "Reroll.Unavailable", Material.BARRIER, "&cNo Rerolls Left", List.of(
            "&7You can not reroll this reward anymore."
        ), new int[]{15}, true, false);
        this.reward = MenuItemSettings.read(config, "Reward", Material.CHEST, "&6%reward_name%", List.of(
            "&7Rarity: &f%reward_rarity_name%",
            "&7Chance: &f%reward_roll_chance%%"
        ), new int[]{13}, true, true);

        config.saveChanges();
    }

    public void open(@NotNull AbstractOpening opening) {
        RerollInventoryHolder holder = new RerollInventoryHolder(opening);
        Inventory inventory = Bukkit.createInventory(holder, this.size, this.replace(opening, this.title));
        holder.setInventory(inventory);

        this.render(inventory, opening);
        opening.getPlayer().openInventory(inventory);
    }

    public boolean refresh(@NotNull AbstractOpening opening) {
        Inventory inventory = opening.getPlayer().getOpenInventory().getTopInventory();
        if (!this.canRefresh(inventory, opening)) {
            return false;
        }

        inventory.clear();
        this.render(inventory, opening);
        opening.getPlayer().updateInventory();
        return true;
    }

    public boolean shouldReopen(@NotNull AbstractOpening opening) {
        Inventory inventory = opening.getPlayer().getOpenInventory().getTopInventory();
        if (!this.canRefresh(inventory, opening)) {
            return true;
        }

        String expectedTitle = this.replace(opening, this.title);
        String currentTitle = opening.getPlayer().getOpenInventory().getTitle();
        return !currentTitle.equals(expectedTitle);
    }

    private void render(@NotNull Inventory inventory, @NotNull AbstractOpening opening) {
        MenuItemSettings rerollSettings = opening.canReroll() ? this.reroll : this.rerollUnavailable;

        if (inventory.getHolder() instanceof RerollInventoryHolder holder) {
            holder.setConfirmSlots(this.confirm.enabled() ? this.confirm.slots() : new int[0]);
            holder.setRerollSlots(rerollSettings.enabled() ? rerollSettings.slots() : new int[0]);
        }

        if (this.filler.enabled()) {
            this.filler.place(inventory, this.createItem(opening, this.filler, null));
        }

        this.confirm.place(inventory, this.createItem(opening, this.confirm, null));
        rerollSettings.place(inventory, this.createItem(opening, rerollSettings, null));

        this.placeRewards(inventory, opening);
    }

    public boolean isConfirmSlot(int slot) {
        return this.confirm.hasSlot(slot);
    }

    public boolean isRerollSlot(int slot, boolean canReroll) {
        return (canReroll ? this.reroll : this.rerollUnavailable).hasSlot(slot);
    }

    private ItemStack createItem(@NotNull AbstractOpening opening, @NotNull MenuItemSettings settings, Reward reward) {
        ItemStack itemStack;
        if (reward != null && settings.useRewardPreview()) {
            PreviewMenu previewMenu = this.plugin.getCrateManager().getPreviewById(opening.getCrate().getPreviewId());
            if (previewMenu != null) {
                return previewMenu.createRewardItem(opening.getPlayer(), opening.getCrate(), reward);
            }

            itemStack = new ItemStack(this.getDisplayItem(reward));
        }
        else {
            NightItem fallback = NightItem.fromItemStack(settings.fallbackItem().clone());
            itemStack = this.plugin.getUniItemsHook().applyMaterial(settings.materialName(), fallback).getItemStack().clone();
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            String name = this.replace(opening, reward, settings.name());
            if (settings.useRewardPreview()) {
                meta.setDisplayName(null);
                meta.setItemName(null);
                if (meta.hasLocalizedName()) {
                    meta.setLocalizedName(null);
                }
                meta.setLore(null);
            }
            meta.setDisplayName(name);
            if (settings.useRewardPreview() || meta.hasItemName()) {
                meta.setItemName(name);
            }
            meta.setLore(settings.lore().stream().map(line -> this.replace(opening, reward, line)).toList());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private void placeRewards(@NotNull Inventory inventory, @NotNull AbstractOpening opening) {
        if (!this.reward.enabled()) return;

        List<Reward> rewards = opening.getRewards();
        if (rewards.isEmpty()) {
            this.reward.place(inventory, this.createItem(opening, this.reward, null));
            return;
        }

        if (rewards.size() == 1) {
            this.reward.place(inventory, this.createItem(opening, this.reward, rewards.getFirst()));
            return;
        }

        int[] slots = this.reward.slots();
        for (int index = 0; index < slots.length && index < rewards.size(); index++) {
            int slot = slots[index];
            if (slot < 0 || slot >= inventory.getSize()) continue;

            inventory.setItem(slot, this.createItem(opening, this.reward, rewards.get(index)));
        }
    }

    @NotNull
    private String replace(@NotNull AbstractOpening opening, @NotNull String text) {
        return this.replace(opening, null, text);
    }

    @NotNull
    private String replace(@NotNull AbstractOpening opening, Reward reward, @NotNull String text) {
        Reward placeholderReward = reward == null && !opening.getRewards().isEmpty() ? opening.getRewards().getFirst() : reward;
        String rewardName = placeholderReward == null ? "" : this.getRewardName(placeholderReward);
        String rewards = opening.getRewards().stream().map(this::getRewardName).collect(Collectors.joining(", "));

        String replaced = opening.getCrate().replacePlaceholders().apply(text)
            .replace(Placeholders.REWARD_NAME, rewardName)
            .replace(Placeholders.GENERIC_REWARDS, rewards)
            .replace("%rerolls_left%", String.valueOf(opening.getRerollsLeft()))
            .replace("%rerolls_used%", String.valueOf(opening.getRerollsUsed()))
            .replace("%rerolls_max%", String.valueOf(opening.getCrate().getRerollAttempts()));

        if (placeholderReward != null) {
            replaced = placeholderReward.replacePlaceholders().apply(replaced);
            replaced = replaced.replace(Placeholders.REWARD_NAME, rewardName);
        }
        return ChatColor.translateAlternateColorCodes('&', replaced);
    }

    @NotNull
    private String getRewardName(@NotNull Reward reward) {
        ItemStack displayItem = this.getDisplayItem(reward);
        ItemMeta meta = displayItem.getItemMeta();

        if (meta != null && meta.hasDisplayName()) {
            String displayName = this.cleanText(meta.getDisplayName());
            if (!displayName.isBlank()) {
                return displayName;
            }
        }

        if (meta != null && meta.hasItemName()) {
            String itemName = this.cleanText(meta.getItemName());
            if (!itemName.isBlank()) {
                return itemName;
            }
        }

        String itemName = this.cleanText(ItemUtil.getItemName(displayItem));
        if (!itemName.isBlank()) {
            return itemName;
        }

        String rewardName = this.cleanText(reward.getName());
        if (!rewardName.isBlank()) {
            return rewardName;
        }

        return BukkitThing.getValue(displayItem.getType());
    }

    @NotNull
    private ItemStack getDisplayItem(@NotNull Reward reward) {
        return reward.getPreviewItem();
    }

    @NotNull
    private String cleanText(String text) {
        if (text == null) {
            return "";
        }

        String cleaned = NightMessage.stripAll(text)
            .replaceAll("<[^>]+>", "")
            .trim();

        return ChatColor.stripColor(cleaned == null ? "" : cleaned).trim();
    }

    private static int normalizeSize(int size) {
        int rows = Math.max(1, Math.min(6, (int) Math.ceil(size / 9D)));
        return rows * 9;
    }

    private boolean canRefresh(@NotNull Inventory inventory, @NotNull AbstractOpening opening) {
        return inventory.getSize() == this.size &&
            inventory.getHolder() instanceof RerollInventoryHolder holder &&
            holder.getOpening() == opening;
    }

    private int[] allSlots() {
        int[] slots = new int[this.size];
        for (int slot = 0; slot < this.size; slot++) {
            slots[slot] = slot;
        }
        return slots;
    }

    private record MenuItemSettings(
        boolean enabled,
        String materialName,
        ItemStack fallbackItem,
        String name,
        List<String> lore,
        int[] slots,
        boolean useRewardPreview
    ) {

        @NotNull
        private static MenuItemSettings read(@NotNull FileConfig config,
                                             @NotNull String path,
                                             @NotNull Material defaultMaterial,
                                             @NotNull String defaultName,
                                             @NotNull List<String> defaultLore,
                                             int[] defaultSlots,
                                             boolean defaultEnabled,
                                             boolean defaultUseRewardPreview) {
            boolean enabled = ConfigValue.create(path + ".Enabled", defaultEnabled).read(config);
            String materialName = ConfigValue.create(path + ".Material", defaultMaterial.name()).read(config);
            String name = ConfigValue.create(path + ".Name", defaultName).read(config);
            List<String> lore = ConfigValue.create(path + ".Lore", defaultLore).read(config);
            int[] slots = ConfigValue.create(path + ".Slots", defaultSlots).read(config);
            boolean useRewardPreview = ConfigValue.create(path + ".Use_Reward_Preview", defaultUseRewardPreview).read(config);

            Material material = Material.matchMaterial(materialName);
            ItemStack fallbackItem = new ItemStack(material == null ? defaultMaterial : material);
            return new MenuItemSettings(enabled, materialName, fallbackItem, name, lore, slots, useRewardPreview);
        }

        private void place(@NotNull Inventory inventory, @NotNull ItemStack itemStack) {
            if (!this.enabled) return;

            Arrays.stream(this.slots).filter(slot -> slot >= 0 && slot < inventory.getSize()).forEach(slot -> inventory.setItem(slot, itemStack));
        }

        private boolean hasSlot(int slot) {
            return Arrays.stream(this.slots).anyMatch(value -> value == slot);
        }
    }
}
