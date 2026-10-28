package su.nightexpress.excellentcrates.opening;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.opening.Opening;
import su.nightexpress.excellentcrates.opening.inventory.InventoryOpening;
import su.nightexpress.excellentcrates.util.CrateUtils;
import su.nightexpress.nightcore.manager.AbstractListener;

public class OpeningListener extends AbstractListener<CratesPlugin> {

    private final OpeningManager manager;

    public OpeningListener(@NotNull CratesPlugin plugin, @NotNull OpeningManager manager) {
        super(plugin);
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        this.manager.recoverPendingRefund(event.getPlayer());
        CrateUtils.resumeCommandQueue(this.plugin, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        try {
            Opening opening = this.manager.getOpening(event.getPlayer());
            if (opening instanceof AbstractOpening abstractOpening && abstractOpening.isAwaitingRewardConfirmation()) {
                abstractOpening.confirmRewards();
                return;
            }

            this.manager.stopOpening(event.getPlayer());
        }
        finally {
            CrateUtils.pauseCommandQueue(this.plugin, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onDeath(PlayerDeathEvent event) {
        Opening opening = this.manager.getOpening(event.getEntity());
        if (opening instanceof AbstractOpening abstractOpening && abstractOpening.isAwaitingRewardConfirmation()) {
            abstractOpening.confirmRewards();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInvOpeningClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        AbstractOpening rerollOpening = this.getRerollOpening(event.getView().getTopInventory());
        if (rerollOpening != null) {
            rerollOpening.handleRerollClick(event);
            return;
        }

        Opening opening = this.manager.getOpening(player);

        if (!(opening instanceof InventoryOpening inventoryOpening)) return;

        inventoryOpening.onClick(event);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInvOpeningDrag(InventoryDragEvent event) {
        if (this.getRerollOpening(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInvOpeningClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        AbstractOpening rerollOpening = this.getRerollOpening(event.getInventory());
        if (rerollOpening != null) {
            rerollOpening.handleRerollClose();
            return;
        }

        Opening opening = this.manager.getOpening(player);

        if (!(opening instanceof InventoryOpening inventoryOpening)) return;

        if (inventoryOpening.isLaunched() && !opening.isCompleted()) {
            if (inventoryOpening.canSkip()) {
                opening.instaRoll();
            }
//            else  {
//                inventoryOpening.setPopupNextTick(true);
//            }
        }
        else {
            inventoryOpening.setCloseTicks(0);
            opening.stop();
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInvOpeningOpen(InventoryOpenEvent event) {
        Player player = (Player) event.getPlayer();
        Opening opening = this.manager.getOpening(player);
        if (opening instanceof AbstractOpening abstractOpening && abstractOpening.isAwaitingRewardConfirmation()) {
            if (!AbstractOpening.isRerollMenu(event.getView().getTopInventory())) {
                event.setCancelled(true);
                abstractOpening.reopenRerollMenu();
            }
            return;
        }

        if (!(opening instanceof InventoryOpening inventoryOpening)) return;
        if (!inventoryOpening.isLaunched()) return;

        if (inventoryOpening.getView() != event.getView()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        this.handleAwaitingReroll(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        this.handleAwaitingReroll(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        this.handleAwaitingReroll(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        this.handleAwaitingReroll(event.getPlayer(), event);
    }

    private void handleAwaitingReroll(@NotNull Player player, @NotNull Cancellable event) {
        Opening opening = this.manager.getOpening(player);
        if (!(opening instanceof AbstractOpening abstractOpening) || !abstractOpening.isAwaitingRewardConfirmation()) {
            return;
        }

        event.setCancelled(true);
        abstractOpening.reopenRerollMenu();
    }

    @Nullable
    private AbstractOpening getRerollOpening(@NotNull org.bukkit.inventory.Inventory inventory) {
        if (inventory.getHolder() instanceof RerollInventoryHolder holder) {
            return holder.getOpening();
        }
        return null;
    }
}
