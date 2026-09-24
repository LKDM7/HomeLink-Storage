package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Graph checks use explicitly loaded fixture chunks; production must never load the far sentinel. */
public final class CoverageChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        List<BlockPos> fixtures = new ArrayList<>();
        try {
            var controller = node(player, fixtures, new BlockPos(162, 80, 162), StorageRegistries.CONTROLLER.get());
            var root = node(player, fixtures, new BlockPos(161, 70, 161), StorageRegistries.LINK.get());
            BlockPos low = new BlockPos(174, 60, 174), high = new BlockPos(175, 110, 173);
            inventory(player, fixtures, low, 7); inventory(player, fixtures, high, 11);
            BlockPos neighbour = new BlockPos(178, 60, 162);
            inventory(player, fixtures, neighbour, 13);
            check(root.bind(controller), "Distant Link binding failed"); controller.refreshIndex();
            check(controller.index().inventoryCount() == 2 && controller.index().totalItems() == 18,
                    "Link must discover entire own chunk, excluding loaded neighbouring chunk");
            var inventoryId = controller.connections().values().stream().filter(c -> c.inventoryPos.equals(low)).findFirst().orElseThrow().linkId;
            check(!inventoryId.equals(root.id()), "Inventory identity must be independent from coverage node");
            controller.setInventoryName(inventoryId, "Distant barrel");
            var extra = node(player, fixtures, new BlockPos(163, 70, 163), StorageRegistries.LINK.get());
            check(extra.bind(controller), "Second same-chunk Link failed"); controller.refreshIndex();
            check(controller.index().totalItems() == 18 && controller.index().inventoryCount() == 2, "Overlapping Links duplicated inventories");
            check(controller.connections().containsKey(inventoryId), "Adding coverage changed persistent inventory UUID");
            level.destroyBlock(extra.getBlockPos(), false); controller.refreshIndex();
            check(controller.index().totalItems() == 18, "Removing redundant Link removed valid remaining coverage");

            var middle = node(player, fixtures, new BlockPos(177, 70, 161), StorageRegistries.REPEATER.get());
            var end = node(player, fixtures, new BlockPos(193, 70, 161), StorageRegistries.REPEATER.get());
            BlockPos endInventory = new BlockPos(200, 100, 170); inventory(player, fixtures, endInventory, 17);
            check(end.bind(controller), "Tail Repeater binding failed"); controller.refreshIndex();
            check(controller.coverageNodes().get(end.id()).status == Status.OFFLINE, "Isolated tail repeater became online");
            check(middle.bind(controller), "Middle Repeater binding failed"); controller.refreshIndex();
            check(controller.index().totalItems() == 48 && controller.index().inventoryCount() == 4, "Cardinal repeater chain did not propagate coverage");
            var diagonal = node(player, fixtures, new BlockPos(145, 70, 145), StorageRegistries.REPEATER.get());
            check(diagonal.bind(controller), "Diagonal repeater binding failed"); controller.refreshIndex();
            check(controller.coverageNodes().get(diagonal.id()).status == Status.OFFLINE, "Diagonal-only connection incorrectly enabled coverage");
            var cycle = new ArrayList<StorageBlockEntity>();
            for (BlockPos p : List.of(new BlockPos(225,70,225), new BlockPos(241,70,225), new BlockPos(241,70,241), new BlockPos(225,70,241))) {
                var repeater = node(player, fixtures, p, StorageRegistries.REPEATER.get()); cycle.add(repeater); check(repeater.bind(controller), "Cycle fixture binding failed");
            }
            controller.refreshIndex();
            check(cycle.stream().allMatch(n -> controller.coverageNodes().get(n.id()).status == Status.OFFLINE), "Rootless cycle powered itself");
            level.destroyBlock(middle.getBlockPos(), false); controller.refreshIndex();
            check(controller.index().totalItems() == 18 && controller.coverageNodes().get(end.id()).status == Status.OFFLINE,
                    "Broken chain retained reachable descendants or old quantities");
            middle = node(player, fixtures, new BlockPos(177,70,161), StorageRegistries.REPEATER.get());
            check(middle.bind(controller), "Replacement repeater failed"); controller.refreshIndex();
            check(controller.index().totalItems() == 48, "Repaired chain did not restore inventory index");

            BlockPos far = new BlockPos(1_000_000,70,1_000_000);
            check(!level.hasChunkAt(far), "Far sentinel unexpectedly loaded");
            CompoundTag saved = controller.saveWithFullMetadata(player.registryAccess());
            CompoundTag unloaded = new CompoundTag(); UUID unloadedId = UUID.randomUUID();
            unloaded.putUUID("Id", unloadedId); unloaded.putLong("Pos", far.asLong()); unloaded.putBoolean("Repeater", true);
            saved.getList("Coverage", 10).add(unloaded);
            controller.loadWithComponents(saved, player.registryAccess()); controller.refreshIndex();
            check(controller.coverageNodes().get(unloadedId).status == Status.UNLOADED && !level.hasChunkAt(far), "Coverage verification loaded absent node chunk");
            check(controller.connections().containsKey(inventoryId) && controller.connections().get(inventoryId).name.equals("Distant barrel"), "Inventory UUID/name did not survive metadata reload");
            check(controller.index().totalItems() == 48, "Graph/index failed to reconstruct after metadata reload");
            level.destroyBlock(low, false); controller.refreshIndex();
            check(controller.index().totalItems() == 41, "Destroyed automatically discovered inventory retained stale contribution");
            inventory(player, fixtures, low, 7); controller.refreshIndex(); check(controller.index().totalItems() == 48, "Replaced inventory was not rediscovered");
            BlockPos outsideHalf = new BlockPos(159, 90, 168), insideHalf = outsideHalf.east();
            level.getChunkAt(outsideHalf); fixtures.add(outsideHalf); fixtures.add(insideHalf);
            level.setBlock(outsideHalf, Blocks.CHEST.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH)
                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT), Block.UPDATE_CLIENTS);
            level.setBlock(insideHalf, Blocks.CHEST.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH)
                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT), Block.UPDATE_CLIENTS);
            ((Container) level.getBlockEntity(outsideHalf)).setItem(0, new ItemStack(Items.DIAMOND, 3));
            ((Container) level.getBlockEntity(insideHalf)).setItem(0, new ItemStack(Items.DIAMOND, 5));
            controller.refreshIndex();
            check(controller.index().totalItems() == 56 && controller.index().inventoryCount() == 5,
                    "Border double chest lost uncovered canonical half or was counted twice");
            level.destroyBlock(outsideHalf, false); controller.refreshIndex();
            check(controller.index().totalItems() == 53, "Split border chest retained destroyed half or duplicated surviving half");
            level.destroyBlock(insideHalf, false); controller.refreshIndex();
            check(controller.index().totalItems() == 48, "Border chest destruction left stale identity");
            var replacement = node(player, fixtures, new BlockPos(164,80,164), StorageRegistries.CONTROLLER.get());
            check(root.bind(replacement), "Root reassociation failed"); controller.refreshIndex(); replacement.refreshIndex();
            check(controller.index().totalItems() == 0 && replacement.index().totalItems() == 18, "Root reassociation left old descendant coverage");
            LogUtils.getLogger().info("STORAGE_COVERAGE_CHECKS_OK distant_same_chunk=true vertical=true neighbour_excluded=true duplicate_links=true inventory_uuid=true cardinal_chain=true diagonal_denied=true rootless_cycle=true break_repair=true unloaded_no_force=true nbt=true root_rebind=true");
        } finally { for (int i = fixtures.size() - 1; i >= 0; i--) level.destroyBlock(fixtures.get(i), false); }
    }
    private static StorageBlockEntity node(ServerPlayer player, List<BlockPos> fixtures, BlockPos pos, Block block) {
        player.serverLevel().getChunkAt(pos); // Fixture setup only, not production discovery.
        fixtures.add(pos); player.serverLevel().setBlockAndUpdate(pos, block.defaultBlockState());
        var entity = (StorageBlockEntity) player.serverLevel().getBlockEntity(pos); entity.setOwner(player.getUUID()); return entity;
    }
    private static void inventory(ServerPlayer player, List<BlockPos> fixtures, BlockPos pos, int count) {
        player.serverLevel().getChunkAt(pos); fixtures.add(pos); player.serverLevel().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        ((Container) player.serverLevel().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, count));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private CoverageChecks() {}
}
