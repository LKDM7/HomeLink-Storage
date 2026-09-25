package fr.lkdm.homelink.storage.network;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public final class StorageData {
    public record Row(long id, ItemStack stack, long count, Map<BlockPos, Long> locations) {}
    public record Location(UUID linkId, BlockPos position, String name, String zone, String status) {}
    /** Object waiting in the Deposits of the network, identified by its index in the last server snapshot. */
    public record Pending(int index, ItemStack stack, long count, Map<BlockPos, Long> deposits) {}
    public record Stats(long items, int unique, int inventories, int occupied, int slots, int full, boolean connected) {}
    private StorageData() {}
}
