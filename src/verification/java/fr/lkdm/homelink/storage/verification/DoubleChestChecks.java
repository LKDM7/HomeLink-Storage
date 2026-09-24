package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter.Status;
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

/** Executed on the real integrated server: reads and withdrawals use vanilla capability handlers. */
public final class DoubleChestChecks {
    public static void run(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos left = new BlockPos(495, 90, 488), right = left.east();
        // Only fixture creation is allowed to load chunks.
        level.getChunkAt(left);
        level.getChunkAt(right);
        try {
            half(level, left, ChestType.LEFT);
            half(level, right, ChestType.RIGHT);
            ChestBlockEntity leftChest = (ChestBlockEntity) level.getBlockEntity(left);
            ChestBlockEntity rightChest = (ChestBlockEntity) level.getBlockEntity(right);
            leftChest.setItem(0, new ItemStack(Items.DIAMOND, 19));
            rightChest.setItem(26, new ItemStack(Items.DIAMOND, 37));
            var a = online(level, left);
            var b = online(level, right);
            check(a.slots() == 54 && b.slots() == 54, "Border chest must expose all 54 slots from either half");
            check(a.identity().equals(b.identity()), "Border chest halves have different identities");
            check(count(a) == 56 && count(b) == 56, "Border chest lost a half or doubled its quantities");
            int simulated = 0;
            for (int slot = 0; slot < a.slots(); slot++) {
                simulated += a.extractionHandler().extractItem(slot, 64, true).getCount();
            }
            check(simulated == 56 && count(online(level, right)) == 56,
                    "Simulated withdrawal mutated chest or failed to include both halves");
            int withdrawn = 0;
            for (int slot = 0; slot < b.slots(); slot++) {
                withdrawn += b.extractionHandler().extractItem(slot, 64, false).getCount();
            }
            check(withdrawn == 56 && leftChest.isEmpty() && rightChest.isEmpty(),
                    "Actual withdrawal failed to remove items from both physical chest halves");
            check(count(online(level, left)) == 0, "Fresh adapter retained stale items after withdrawal");
            level.setBlock(right, Blocks.BARREL.defaultBlockState(), Block.UPDATE_CLIENTS);
            check(online(level, left).slots() == 27, "A legitimately split chest must expose its remaining 27 slots");
            // Vanilla neighbour shape updates correctly turn the survivor into SINGLE.
            // Deliberately restore an inconsistent saved state to exercise validation.
            half(level, left, ChestType.LEFT);
            check(StorageInventoryAdapter.resolveAny(level, left).status() == Status.INVALID,
                    "Broken double chest was exposed as an accessible partial inventory");
        } finally {
            level.setBlock(left, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(right, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        // Construct a real loaded chest half facing an absent FULL chunk. The fixture
        // preserves its saved paired state without neighbour shape updates. No mock predicates.
        BlockPos isolated = new BlockPos(400_015, 90, 400_008);
        BlockPos absent = isolated.east();
        level.getChunkAt(isolated);
        check(level.getChunkSource().getChunkNow(absent.getX() >> 4, absent.getZ() >> 4) == null,
                "Unloaded partner fixture unexpectedly has a loaded FULL chunk");
        try {
            half(level, isolated, ChestType.LEFT);
            ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(isolated);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 23));
            for (Direction face : Direction.values()) {
                var resolution = StorageInventoryAdapter.resolve(level, isolated, face);
                check(resolution.status() == Status.UNLOADED && resolution.adapter() == null,
                        "Unloaded partner exposed a partial extraction handler on " + face);
            }
            var resolution = StorageInventoryAdapter.resolveAny(level, isolated);
            check(resolution.status() == Status.UNLOADED && resolution.adapter() == null,
                    "Automatic face probing bypassed unloaded double-chest protection");
            check(chest.getItem(0).getCount() == 23, "Unavailable chest contents changed");
            check(level.getChunkSource().getChunkNow(absent.getX() >> 4, absent.getZ() >> 4) == null,
                    "Double-chest resolution forced the partner chunk to load");
        } finally {
            level.setBlock(isolated, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        LogUtils.getLogger().info("STORAGE_DOUBLE_CHEST_CHECKS_OK border_slots=54 both_access_points=true simulation_no_mutation=true extracted_both_halves=56 invalid_partner_denied=true unloaded_partner_denied=true no_forced_chunk=true");
    }

    private static void half(ServerLevel level, BlockPos pos, ChestType type) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, type), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static StorageInventoryAdapter online(ServerLevel level, BlockPos pos) {
        var result = StorageInventoryAdapter.resolveAny(level, pos);
        check(result.status() == Status.ONLINE, "Expected online double chest at " + pos + ": " + result.status());
        return result.adapter();
    }

    private static int count(StorageInventoryAdapter adapter) {
        int total = 0;
        for (int slot = 0; slot < adapter.slots(); slot++) total += adapter.handler().getStackInSlot(slot).getCount();
        return total;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private DoubleChestChecks() { }
}
