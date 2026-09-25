package fr.lkdm.homelink.storage.storage.inventory;

import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.storage.index.StorageIndex;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Server-thread insertion. Each successful destination slot is debited immediately, never from a simulation. */
public final class StorageInsertion {
    /** Ordinary pass: only inventories already holding this variant. */
    public static int deposit(StorageBlockEntity controller, DepositBlockEntity source, int sourceSlot) {
        return deposit(controller, source, sourceSlot, false);
    }

    /**
     * @param overflow false: only inventories already holding this variant; true: only the
     *                 overflow chests of the network, which accept any object
     */
    public static int deposit(StorageBlockEntity controller, DepositBlockEntity source, int sourceSlot, boolean overflow) {
        if (!(controller.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || source.getLevel() != level || source.isRemoved() || source.controller() != controller
                || !controller.automationAvailable() || sourceSlot < 0 || sourceSlot >= source.inventory().getSlots()) return 0;
        if (!source.beginTransfer()) return 0;
        try { return transfer(controller, source, sourceSlot, level, overflow); }
        finally { source.endTransfer(); }
    }

    private static int transfer(StorageBlockEntity controller, DepositBlockEntity source, int sourceSlot, ServerLevel level, boolean overflow) {
        ItemStack requested = source.inventory().getStackInSlot(sourceSlot).copy();
        if (requested.isEmpty()) return 0;
        int moved = 0;
        // An overflow chest accepts any object: it does not need to hold the variant already.
        boolean requireVariant = !overflow;
        for (var connection : overflow ? controller.overflowDestinations() : controller.depositDestinations(requested)) {
            if (!controller.depositConnectionAvailable(connection)) continue;
            try {
                var resolution = StorageInventoryAdapter.resolveAny(level, connection.accessPos);
                var adapter = resolution.adapter();
                if (adapter == null || !adapter.identity().equals(connection.inventoryPos)
                        || requireVariant && !contains(adapter.handler(), requested)) continue;
                // The original capability preserves sided insertion restrictions; the combined read view may not.
                IItemHandler handler = adapter.extractionHandler();
                var destination = level.getBlockEntity(connection.accessPos);
                if (destination instanceof StorageBlockEntity) continue;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack available = source.inventory().getStackInSlot(sourceSlot);
                    if (available.isEmpty() || !StorageIndex.sameVariant(requested, available)) break;
                    ItemStack offer = available.copyWithCount(Math.min(available.getCount(), requested.getCount() - moved));
                    int accepted = offer.getCount() - remainderCount(offer, handler.insertItem(slot, offer.copy(), true));
                    if (accepted <= 0) continue;
                    // Simulation is not a reservation. Re-resolve loaded capabilities before committing.
                    var live = StorageInventoryAdapter.resolveAny(level, connection.accessPos).adapter();
                    if (live == null || !live.identity().equals(connection.inventoryPos)
                            || level.getBlockEntity(connection.accessPos) != destination
                            || requireVariant && !contains(live.handler(), requested) || source.isRemoved()
                            || source.controller() != controller || !controller.automationAvailable()) break;
                    var liveHandler = live.extractionHandler();
                    if (slot >= liveHandler.getSlots()) break;
                    // A capability callback can change the source during simulation or resolution.
                    ItemStack current = source.inventory().getStackInSlot(sourceSlot);
                    if (current.isEmpty() || !StorageIndex.sameVariant(requested, current)) break;
                    accepted = Math.min(accepted, current.getCount());
                    ItemStack commit = current.copyWithCount(accepted);
                    int inserted = accepted - remainderCount(commit, liveHandler.insertItem(slot, commit.copy(), false));
                    if (inserted > 0) {
                        source.inventory().extractItem(sourceSlot, inserted, false);
                        moved += inserted;
                    }
                    if (moved >= requested.getCount()) break;
                }
            } catch (RuntimeException failure) {
                com.mojang.logging.LogUtils.getLogger().warn("Storage deposit insertion at {} failed: {}", connection.accessPos, failure.toString());
            } finally {
                // Update only this candidate, including stale entries whose last matching object was removed.
                controller.refreshInventory(connection.linkId);
            }
            // One destination may accept only part of the stack; another is tried on the next turn.
            if (moved > 0) return moved;
        }
        return moved;
    }

    private static boolean contains(IItemHandler handler, ItemStack requested) {
        for (int slot = 0; slot < handler.getSlots(); slot++)
            if (StorageIndex.sameVariant(requested, handler.getStackInSlot(slot))) return true;
        return false;
    }

    private static int remainderCount(ItemStack offered, ItemStack remainder) {
        if (remainder.isEmpty()) return 0;
        if (!StorageIndex.sameVariant(offered, remainder) || remainder.getCount() > offered.getCount())
            throw new IllegalStateException("Item capability returned an invalid insertion remainder");
        return remainder.getCount();
    }
    private StorageInsertion() { }
}
