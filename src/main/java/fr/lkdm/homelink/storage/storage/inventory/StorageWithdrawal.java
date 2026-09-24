package fr.lkdm.homelink.storage.storage.inventory;

import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import java.util.UUID;

/** Server-thread transfer: the index selects a variant; only the live handler supplies objects. */
public final class StorageWithdrawal {
    public static int withdraw(ServerPlayer player, StorageBlockEntity controller, UUID inventoryId, long entryId, int amount) {
        if (amount < 1 || amount > 64 || controller.getLevel() != player.serverLevel()
                || !controller.canAccess(player) || !controller.permission(player, Permission.CONTROL)) return 0;
        var entry = controller.index().entries().stream().filter(row -> row.id() == entryId).findFirst().orElse(null);
        if (entry == null) return 0;
        ItemStack requested = entry.display().copy();
        controller.refreshConnections();
        var connection = controller.connections().get(inventoryId);
        if (connection == null || connection.status != StorageInventoryAdapter.Status.ONLINE
                || !controller.isCoverageActive(connection.sourceId)
                || !entry.locations().containsKey(connection.inventoryPos)) return 0;
        int transferred = 0;
        try {
            var resolved = StorageInventoryAdapter.resolveAny(player.serverLevel(), connection.accessPos);
            if (resolved.adapter() == null || !resolved.adapter().identity().equals(connection.inventoryPos)) return 0;
            var handler = resolved.adapter().extractionHandler();
            var destination = new PlayerMainInvWrapper(player.getInventory());
            for (int slot = 0; slot < handler.getSlots() && transferred < amount; slot++) {
                if (!ItemStack.isSameItemSameComponents(requested, handler.getStackInSlot(slot))) continue;
                int wanted = Math.min(amount - transferred, requested.getMaxStackSize());
                ItemStack simulated = handler.extractItem(slot, wanted, true);
                if (simulated.isEmpty() || !ItemStack.isSameItemSameComponents(requested, simulated)) continue;
                int accepted = simulated.getCount() - ItemHandlerHelper.insertItemStacked(destination, simulated, true).getCount();
                if (accepted <= 0) break;
                ItemStack extracted = handler.extractItem(slot, Math.min(wanted, accepted), false);
                if (extracted.isEmpty()) continue;
                int count = extracted.getCount();
                // Deliver only the real extraction result, never a client stack or a simulated copy.
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(destination, extracted, false);
                // A nonstandard handler may change the player's inventory during extraction.
                // Preserve any overflow physically instead of deleting it or inventing rollback items.
                if (!remainder.isEmpty()) player.drop(remainder, false);
                transferred += count;
            }
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Storage withdrawal from {} failed: {}", inventoryId, failure.toString());
        } finally {
            controller.refreshInventory(inventoryId);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        return transferred;
    }
    private StorageWithdrawal() { }
}
