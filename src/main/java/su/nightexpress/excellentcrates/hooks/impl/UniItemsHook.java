package su.nightexpress.excellentcrates.hooks.impl;

import io.github.projectunified.uniitem.all.AllItemProvider;
import io.github.projectunified.uniitem.api.ItemKey;
import io.github.projectunified.uniitem.api.ItemProvider;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.util.bukkit.NightItem;
import su.nightexpress.nightcore.util.placeholder.Replacer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class UniItemsHook {

    private final Consumer<String> warning;
    private final ItemProvider provider;
    private final Map<String, ItemStack> prototypes;
    private final Set<String> warnedKeys;

    public UniItemsHook(@NotNull CratesPlugin plugin) {
        this(plugin::warn, new AllItemProvider());
    }

    UniItemsHook(@NotNull Consumer<String> warning, @NotNull ItemProvider provider) {
        this.warning = warning;
        this.provider = provider;
        this.prototypes = new HashMap<>();
        this.warnedKeys = new HashSet<>();
    }

    @NotNull
    public NightItem readMaterial(@NotNull FileConfig config,
                                  @NotNull String itemPath,
                                  @NotNull Material fallbackMaterial,
                                  @NotNull Supplier<NightItem> reader) {
        String materialPath = itemPath + ".Material";
        String configured = config.getString(materialPath, fallbackMaterial.name()).trim();
        if (!isProviderMaterial(configured)) return reader.get();

        config.set(materialPath, fallbackMaterial.name());
        try {
            return this.apply(configured, reader.get());
        }
        finally {
            config.set(materialPath, configured);
        }
    }

    @NotNull
    public NightItem applyMaterial(@NotNull String configured, @NotNull NightItem fallback) {
        return isProviderMaterial(configured) ? this.apply(configured, fallback) : fallback;
    }

    @NotNull
    public NightItem apply(@NotNull String key, @NotNull NightItem fallback) {
        if (key.isBlank()) return fallback;

        NightItem item = NightItem.fromItemStack(this.resolveItem(key, fallback.getItemStack()))
            .setAmount(fallback.getAmount())
            .setItemName(fallback.getItemName())
            .setDisplayName(fallback.getDisplayName())
            .setLore(fallback.getLore());

        item.getMeta()
            .setHiddenComponents(fallback.getMeta().getHiddenComponents())
            .setHideTooltip(fallback.getMeta().isHideTooltip())
            .setEnchantGlint(fallback.getMeta().isEnchantGlint())
            .setPlaceholderContext(fallback.getMeta().getPlaceholderContext());

        Replacer replacer = fallback.getReplacer();
        if (replacer != null) item.setReplacer(new Replacer(replacer));

        return item;
    }

    @NotNull
    public synchronized ItemStack resolveItem(@NotNull String key, @NotNull ItemStack fallback) {
        String configured = key.trim();
        if (configured.isEmpty()) return fallback.clone();

        ItemStack prototype = this.prototypes.get(configured);
        if (prototype != null) return prototype.clone();
        String failure = "unknown provider or missing item";
        try {
            ItemKey itemKey = parseKey(configured);
            if (itemKey != null) {
                ItemStack resolved = this.provider.item(this.provider.normalize(itemKey));
                if (resolved != null && !resolved.getType().isAir() && resolved.getAmount() > 0) {
                    prototype = resolved.clone();
                    prototype.setAmount(1);
                    this.prototypes.put(configured, prototype);
                    return prototype.clone();
                }
            }
        }
        catch (RuntimeException | LinkageError exception) {
            failure = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }

        if (this.warnedKeys.add(configured)) {
            this.warning.accept("Cannot resolve UniItems menu material '" + configured + "' (" + failure + "); using the vanilla fallback.");
        }
        return fallback.clone();
    }

    public static boolean isProviderMaterial(@NotNull String configured) {
        String value = configured.trim();
        return value.indexOf(':') > 0 && !value.regionMatches(true, 0, "minecraft:", 0, "minecraft:".length());
    }

    static ItemKey parseKey(@NotNull String configured) {
        try {
            ItemKey key = ItemKey.fromString(configured);
            return key.type().isBlank() || key.id().isBlank() ? null : key;
        }
        catch (RuntimeException exception) {
            return null;
        }
    }
}
