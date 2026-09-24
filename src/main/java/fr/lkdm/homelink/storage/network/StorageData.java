package fr.lkdm.homelink.storage.network;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

public final class StorageData {
    public record Row(long id, ItemStack stack, long count, Map<BlockPos, Long> locations) {}
    public record Location(UUID linkId, BlockPos position, String name, String zone, String status) {}
    public record Stats(long items, int unique, int inventories, int occupied, int slots, int full, boolean connected) {}
    private StorageData() {}
}
