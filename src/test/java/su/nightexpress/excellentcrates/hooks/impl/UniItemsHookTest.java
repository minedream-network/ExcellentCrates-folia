package su.nightexpress.excellentcrates.hooks.impl;

import io.github.projectunified.uniitem.api.ItemKey;
import io.github.projectunified.uniitem.api.Item;
import io.github.projectunified.uniitem.api.ItemProvider;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniItemsHookTest {

    @Test
    void keepsProviderIdsWithExtraColons() {
        assertEquals(
            new ItemKey("itemsadder", "namespace:next_page"),
            UniItemsHook.parseKey("itemsadder:namespace:next_page")
        );
    }

    @Test
    void rejectsMalformedProviderKeys() {
        assertNull(UniItemsHook.parseKey("nexo:"));
        assertNull(UniItemsHook.parseKey(":missing"));
        assertNull(UniItemsHook.parseKey("missing_separator"));
    }

    @Test
    void distinguishesProviderKeysFromVanillaMaterials() {
        assertTrue(UniItemsHook.isProviderMaterial("nexo:next_page"));
        assertTrue(UniItemsHook.isProviderMaterial("itemsadder:namespace:next_page"));
        assertFalse(UniItemsHook.isProviderMaterial("STONE"));
        assertFalse(UniItemsHook.isProviderMaterial("minecraft:stone"));
    }

    @Test
    void retriesItemsThatWereUnavailableDuringStartup() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        ItemProvider provider = new ItemProvider() {
            @Override
            public List<String> availableTypes() {
                return List.of("test");
            }

            @Override
            public Item wrap(ItemStack itemStack) {
                return Item.INVALID;
            }

            @Override
            public Item wrap(ItemKey itemKey) {
                return Item.INVALID;
            }

            @Override
            public ItemStack item(ItemKey itemKey) {
                attempts.incrementAndGet();
                return null;
            }
        };

        UniItemsHook hook = new UniItemsHook(warnings::add, provider);
        ItemStack fallback = createTestItem(Material.STONE);

        assertEquals(Material.STONE, hook.resolveItem("test:icon", fallback).getType());
        assertEquals(Material.STONE, hook.resolveItem("test:icon", fallback).getType());
        assertEquals(2, attempts.get());
        assertEquals(1, warnings.size());
    }

    private static ItemStack createTestItem(Material material) {
        try {
            return new ItemStack(material);
        }
        catch (IllegalStateException | LinkageError exception) {
            Assumptions.assumeTrue(false, "Bukkit registry is unavailable in this API-only test runtime");
            throw new AssertionError(exception);
        }
    }
}
