package su.nightexpress.excellentcrates.opening;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class RerollInventoryHolder implements InventoryHolder {

    private final AbstractOpening opening;
    private Inventory inventory;
    private final Set<Integer> confirmSlots;
    private final Set<Integer> rerollSlots;

    public RerollInventoryHolder(@NotNull AbstractOpening opening) {
        this.opening = opening;
        this.confirmSlots = new HashSet<>();
        this.rerollSlots = new HashSet<>();
    }

    @NotNull
    public AbstractOpening getOpening() {
        return this.opening;
    }

    @Override
    public Inventory getInventory() {
        return this.inventory;
    }

    public void setInventory(@NotNull Inventory inventory) {
        this.inventory = inventory;
    }

    public void setConfirmSlots(int[] slots) {
        this.confirmSlots.clear();
        Arrays.stream(slots).forEach(this.confirmSlots::add);
    }

    public void setRerollSlots(int[] slots) {
        this.rerollSlots.clear();
        Arrays.stream(slots).forEach(this.rerollSlots::add);
    }

    public boolean isConfirmSlot(int slot) {
        return this.confirmSlots.contains(slot);
    }

    public boolean isRerollSlot(int slot) {
        return this.rerollSlots.contains(slot);
    }
}
