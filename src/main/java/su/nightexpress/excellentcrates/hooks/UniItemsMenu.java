package su.nightexpress.excellentcrates.hooks;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import su.nightexpress.excellentcrates.CratesAPI;
import su.nightexpress.excellentcrates.hooks.impl.UniItemsHook;
import su.nightexpress.nightcore.config.FileConfig;
import su.nightexpress.nightcore.ui.menu.data.ConfigBased;
import su.nightexpress.nightcore.ui.menu.data.MenuLoader;
import su.nightexpress.nightcore.ui.menu.item.MenuItem;

public interface UniItemsMenu extends ConfigBased {

    @Override
    default void load(@NotNull FileConfig config) {
        MenuLoader loader = new MenuLoader(this, config) {
            @Override
            protected MenuItem readItem(String path) {
                UniItemsHook hook = CratesAPI.plugin().getUniItemsHook();
                String materialPath = path + ".Item.Material";
                String material = config.getString(materialPath, Material.BARRIER.name()).trim();
                if (!hook.isProviderMaterial(material)) return super.readItem(path);

                config.set(materialPath, Material.BARRIER.name());
                MenuItem loaded;
                try {
                    loaded = super.readItem(path);
                }
                finally {
                    config.set(materialPath, material);
                }

                return new MenuItem(
                    hook.applyMaterial(material, loaded.getItem()),
                    loaded.getPriority(),
                    loaded.getSlots(),
                    loaded.getHandler()
                );
            }
        };

        loader.loadSettings();
        this.loadConfiguration(config, loader);
        loader.loadItems();
        loader.loadComments();
        config.saveChanges();
    }
}
