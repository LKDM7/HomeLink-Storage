package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Server-side adapter checks executed inside the connected-client verification world. */
public final class InventoryChecks {
    public static void run(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<Block> types = List.of(Blocks.CHEST, Blocks.BARREL, Blocks.SHULKER_BOX, Blocks.TRAPPED_CHEST);
        for (int index = 0; index < types.size(); index++) {
            BlockPos position = new BlockPos(8 + index * 2, 5, 8);
            level.setBlockAndUpdate(position, types.get(index).defaultBlockState());
            StorageInventoryAdapter adapter = online(level, position);
            check(adapter.slots() == 27, "Expected 27 slots for " + types.get(index));
            check(adapter.identity().equals(position), "Unstable single-inventory identity");
            check(adapter.position().equals(position), "Incorrect inventory location");
            ItemStack remainder = adapter.handler().insertItem(0, new ItemStack(Items.DIAMOND, 7), false);
            check(remainder.isEmpty(), "Vanilla handler rejected insertion");
            check(online(level, position).handler().getStackInSlot(0).getCount() == 7, "Adapter did not read current inventory contents");
            level.destroyBlock(position, false);
            check(StorageInventoryAdapter.resolve(level, position, Direction.UP).status() == Status.INVENTORY_MISSING, "Destroyed inventory remained online");
            level.setBlockAndUpdate(position, types.get(index).defaultBlockState());
            check(online(level, position).slots() == 27, "Replaced inventory did not reconnect");
        }

        BlockPos left = new BlockPos(8, 5, 12);
        BlockPos right = left.east();
        level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT), Block.UPDATE_CLIENTS);
        level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.RIGHT), Block.UPDATE_CLIENTS);
        ((ChestBlockEntity) level.getBlockEntity(left)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        ((ChestBlockEntity) level.getBlockEntity(right)).setItem(0, new ItemStack(Items.DIAMOND, 11));
        StorageInventoryAdapter first = online(level, left);
        StorageInventoryAdapter second = online(level, right);
        check(first.slots() == 54 && second.slots() == 54, "Double chest did not expose 54 slots");
        check(first.identity().equals(second.identity()), "Double chest halves have different logical identities");
        var logicalInventories = new LinkedHashMap<BlockPos, StorageInventoryAdapter>();
        logicalInventories.putIfAbsent(first.identity(), first);
        logicalInventories.putIfAbsent(second.identity(), second);
        check(logicalInventories.size() == 1, "Double chest counted twice");
        int diamonds = 0;
        for (StorageInventoryAdapter inventory : logicalInventories.values()) {
            for (int slot = 0; slot < inventory.slots(); slot++) {
                ItemStack stack = inventory.handler().getStackInSlot(slot);
                if (stack.is(Items.DIAMOND)) diamonds += stack.getCount();
            }
        }
        check(diamonds == 18, "Double chest quantities were missing or duplicated: " + diamonds);

        BlockPos far = new BlockPos(1_000_000, 5, 1_000_000);
        check(!level.hasChunkAt(far), "Unloaded test position unexpectedly loaded");
        check(StorageInventoryAdapter.resolve(level, far, Direction.UP).status() == Status.UNLOADED, "Unloaded chunk was not reported");
        check(!level.hasChunkAt(far), "Adapter forced a chunk load");
        BlockPos outside = new BlockPos(8, level.getMaxBuildHeight(), 8);
        check(StorageInventoryAdapter.resolve(level, outside, Direction.UP).status() == Status.INVALID, "Out-of-world position accepted");
        BlockPos empty = new BlockPos(8, 8, 8);
        level.setBlockAndUpdate(empty, Blocks.AIR.defaultBlockState());
        check(StorageInventoryAdapter.resolve(level, empty, Direction.UP).status() == Status.INVENTORY_MISSING, "Air accepted as an inventory");
        for (int i = 0; i < types.size(); i++) level.destroyBlock(new BlockPos(8 + i * 2, 5, 8), false);
        level.destroyBlock(left, false); level.destroyBlock(right, false);
        CoverageChecks.run(player);
        LogUtils.getLogger().info("STORAGE_INVENTORY_CHECKS_OK vanilla_types=4 destroy_reconnect=4 double_slots=54 double_items=18 logical_inventories=1 unloaded_no_force=true");
    }

    private static StorageBlockEntity storage(ServerPlayer player, BlockPos position, Block block) {
        player.serverLevel().setBlockAndUpdate(position, block.defaultBlockState().setValue(StorageBlock.TARGET, Direction.DOWN));
        check(player.serverLevel().getBlockEntity(position) instanceof StorageBlockEntity, "Storage block entity missing");
        StorageBlockEntity entity = (StorageBlockEntity) player.serverLevel().getBlockEntity(position);
        entity.setOwner(player.getUUID());
        return entity;
    }

    private static StorageInventoryAdapter online(ServerLevel level, BlockPos position) {
        var resolution = StorageInventoryAdapter.resolve(level, position, Direction.UP);
        check(resolution.status() == Status.ONLINE && resolution.adapter() != null, "Inventory unavailable at " + position + ": " + resolution.status());
        return resolution.adapter();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private InventoryChecks() { }
}
