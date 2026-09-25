package fr.lkdm.homelink.storage.storage.inventory;

import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.storage.index.StorageIndex;
import fr.lkdm.homelink.storage.storage.network.InventoryConnection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.PlayerMainInvWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-thread transfer: the index selects a variant; only the live handler supplies objects. */
public final class StorageWithdrawal {
    /** Largest network-wide request: a full main inventory of 64-item stacks. */
    public static final int MAX_REQUEST = 36 * 64;

    /** Withdraws from one explicit inventory, at most one stack per request. */
    public static int withdraw(ServerPlayer player, StorageBlockEntity controller, UUID inventoryId, long entryId, int amount) {
        if (amount < 1 || amount > 64 || !authorized(player, controller)) return 0;
        var entry = entry(controller, entryId);
        if (entry == null) return 0;
        controller.refreshConnections();
        var connection = controller.connections().get(inventoryId);
        if (!usable(controller, connection, entry)) return 0;
        return transfer(player, controller, connection, entry.display().copy(), amount);
    }

    /**
     * Withdraws from every online inventory holding the variant, the preferred one first,
     * until the amount is reached, the stock is exhausted or the player inventory is full.
     */
    public static int withdrawAny(ServerPlayer player, StorageBlockEntity controller, UUID preferred, long entryId, int amount) {
        if (amount < 1 || amount > MAX_REQUEST || !authorized(player, controller)) return 0;
        var entry = entry(controller, entryId);
        if (entry == null) return 0;
        ItemStack requested = entry.display().copy();
        controller.refreshConnections();
        List<InventoryConnection> sources = new ArrayList<>();
        var first = preferred == null ? null : controller.connections().get(preferred);
        if (usable(controller, first, entry)) sources.add(first);
        for (var connection : controller.connections().values())
            if (connection != first && usable(controller, connection, entry)) sources.add(connection);
        var destination = new PlayerMainInvWrapper(player.getInventory());
        int transferred = 0;
        for (var connection : sources) {
            if (transferred >= amount || !ItemHandlerHelper.insertItemStacked(destination, requested.copyWithCount(1), true).isEmpty()) break;
            transferred += transfer(player, controller, connection, requested, amount - transferred);
        }
        return transferred;
    }

    /**
     * Takes objects still waiting in the Deposits bound to this Controller: the real Deposit
     * slots supply them, bounded by the amount, the stock and the player's free space.
     */
    public static int withdrawPending(ServerPlayer player, StorageBlockEntity controller, ItemStack prototype, int amount) {
        if (amount < 1 || amount > MAX_REQUEST || prototype.isEmpty() || !authorized(player, controller)) return 0;
        var destination = new PlayerMainInvWrapper(player.getInventory());
        int transferred = 0;
        try {
            for (var deposit : controller.pendingDeposits()) {
                var inventory = deposit.inventory();
                for (int slot = 0; slot < inventory.getSlots() && transferred < amount; slot++) {
                    if (!ItemStack.isSameItemSameComponents(prototype, inventory.getStackInSlot(slot))) continue;
                    ItemStack simulated = inventory.extractItem(slot, Math.min(amount - transferred, prototype.getMaxStackSize()), true);
                    if (simulated.isEmpty()) continue;
                    int accepted = simulated.getCount() - ItemHandlerHelper.insertItemStacked(destination, simulated, true).getCount();
                    if (accepted <= 0) return transferred;
                    ItemStack extracted = inventory.extractItem(slot, accepted, false);
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(destination, extracted, false);
                    if (!remainder.isEmpty()) player.drop(remainder, false);
                    transferred += extracted.getCount();
                }
                if (transferred >= amount) break;
            }
        } finally {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        return transferred;
    }

    private static boolean authorized(ServerPlayer player, StorageBlockEntity controller) {
        return controller.getLevel() == player.serverLevel() && controller.canAccess(player) && controller.permission(player, Permission.CONTROL);
    }

    private static StorageIndex.Entry entry(StorageBlockEntity controller, long entryId) {
        return controller.index().entries().stream().filter(row -> row.id() == entryId).findFirst().orElse(null);
    }

    private static boolean usable(StorageBlockEntity controller, InventoryConnection connection, StorageIndex.Entry entry) {
        return connection != null && connection.status == StorageInventoryAdapter.Status.ONLINE
                && controller.isCoverageActive(connection.sourceId)
                && entry.locations().containsKey(connection.inventoryPos);
    }

    private static int transfer(ServerPlayer player, StorageBlockEntity controller, InventoryConnection connection,
                                ItemStack requested, int amount) {
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
                // A slot may still hold more than one stack's worth: revisit it.
                if (transferred < amount && !handler.getStackInSlot(slot).isEmpty()) slot--;
            }
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("Storage withdrawal from {} failed: {}", connection.linkId, failure.toString());
        } finally {
            controller.refreshInventory(connection.linkId);
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
        }
        return transferred;
    }
    private StorageWithdrawal() { }
}
