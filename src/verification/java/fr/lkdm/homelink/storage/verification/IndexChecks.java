package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.index.StorageIndex;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Correctness checks with bounded synthetic handlers and real connected world inventories. */
public final class IndexChecks {
    public static void run(ServerPlayer player) {
        long started = System.nanoTime();
        StorageIndex index = new StorageIndex();
        var handlers = new ArrayList<ItemStackHandler>();
        for (int inventory = 0; inventory < 128; inventory++) {
            var handler = new ItemStackHandler(54);
            for (int slot = 0; slot < 54; slot++) handler.setStackInSlot(slot, new ItemStack(Items.DIAMOND, 64));
            handlers.add(handler);
            index.update(new BlockPos(inventory, 0, 0), handler);
        }
        check(index.inventoryCount() == 128 && index.totalSlots() == 6912 && index.occupiedSlots() == 6912, "Large index slot statistics mismatch");
        check(index.totalItems() == 442368L && index.fullInventories() == 128, "Large index quantities mismatch");
        check(index.uniqueItems() == 1 && index.variants() == 1, "Identical components failed to aggregate");
        var diamondEntry = index.entries().iterator().next();
        check(diamondEntry.locations().size() == 128, "Per-inventory locations missing");
        check(diamondEntry.locations().values().stream().mapToLong(Long::longValue).sum() == diamondEntry.total(), "Location quantities disagree with entry total");
        long revision = index.revision();
        for (int inventory = 0; inventory < handlers.size(); inventory++) index.update(new BlockPos(inventory, 0, 0), handlers.get(inventory));
        check(index.revision() == revision, "Unchanged consistency check changed the revision");

        ItemStackHandler first = handlers.getFirst();
        first.getStackInSlot(0).setCount(32);
        check(index.totalItems() == 442368L && diamondEntry.display().getCount() == 1, "Index retained an alias to a mutable input stack");
        index.update(BlockPos.ZERO, first);
        check(index.totalItems() == 442336L && index.revision() > revision, "Changed slot did not update incrementally");
        for (int slot = 0; slot < first.getSlots(); slot++) first.setStackInSlot(slot, ItemStack.EMPTY);
        first.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 12));
        index.update(BlockPos.ZERO, first);
        check(index.totalItems() == 438924L && index.uniqueItems() == 2, "Replacing an inventory left old quantities");
        check(index.occupiedSlots() == 6859 && index.fullInventories() == 127, "Occupied-slot capacity was not updated");
        index.retain(Set.of(BlockPos.ZERO));
        check(index.inventoryCount() == 1 && index.totalSlots() == 54 && index.occupiedSlots() == 1 && index.totalItems() == 12, "Offline inventory retention did not remove old statistics");
        check(index.uniqueItems() == 1 && index.variants() == 1 && index.entries().iterator().next().display().is(Items.IRON_INGOT), "Unused item entries survived removal");
        revision = index.revision();
        index.retain(Set.of(BlockPos.ZERO));
        check(index.revision() == revision, "Unchanged retain changed revision");
        index.retain(Set.of());
        check(index.totalItems() == 0 && index.entries().isEmpty() && index.totalSlots() == 0 && index.inventoryCount() == 0, "Empty index has stale data");

        variants();
        world(player);
        LogUtils.getLogger().info("STORAGE_INDEX_CHECKS_OK inventories=128 slots=6912 initial_items=442368 components=true incremental=true unchanged_revision=true offline_cleanup=true world_double_chest=true elapsed_ms={}", (System.nanoTime() - started) / 1_000_000);
    }

    private static void variants() {
        StorageIndex index = new StorageIndex();
        ItemStackHandler handler = new ItemStackHandler(3);
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("First sword"));
        ItemStack other = new ItemStack(Items.DIAMOND_SWORD);
        other.set(DataComponents.CUSTOM_NAME, Component.literal("Second sword"));
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND_SWORD));
        handler.setStackInSlot(1, named);
        handler.setStackInSlot(2, other);
        index.update(BlockPos.ZERO, handler);
        check(index.uniqueItems() == 1 && index.variants() == 3 && index.totalItems() == 3, "Data component variants were merged");
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Second sword"));
        check(index.entries().stream().anyMatch(entry -> Component.literal("First sword").equals(entry.display().get(DataComponents.CUSTOM_NAME))), "Prototype components alias the live source stack");
        index.update(BlockPos.ZERO, handler);
        check(index.variants() == 2 && index.totalItems() == 3, "Changed component identity did not merge matching variants");
        check(index.entries().stream().anyMatch(entry -> entry.total() == 2), "Merged variant has incorrect quantity");
    }

    private static void world(ServerPlayer player) {
        var level = player.serverLevel();
        BlockPos left = new BlockPos(0, 5, 8);
        BlockPos right = left.east();
        level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT), Block.UPDATE_CLIENTS);
        level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.RIGHT), Block.UPDATE_CLIENTS);
        ((ChestBlockEntity) level.getBlockEntity(left)).setItem(0, new ItemStack(Items.DIAMOND, 23));
        ((ChestBlockEntity) level.getBlockEntity(right)).setItem(0, new ItemStack(Items.IRON_INGOT, 12));
        StorageBlockEntity controller = storage(player, new BlockPos(0, 5, 12), StorageRegistries.CONTROLLER.get());
        StorageBlockEntity first = storage(player, left.above(), StorageRegistries.LINK.get());
        StorageBlockEntity second = storage(player, right.above(), StorageRegistries.LINK.get());
        check(first.bind(controller) && second.bind(controller), "Index world links failed to bind");
        controller.refreshConnections();
        controller.refreshIndex();
        check(controller.index().inventoryCount() == 1 && controller.index().totalSlots() == 54 && controller.index().totalItems() == 35, "Controller index duplicated a double chest");
        ((ChestBlockEntity) level.getBlockEntity(right)).setItem(0, new ItemStack(Items.IRON_INGOT, 20));
        controller.refreshIndex();
        check(controller.index().totalItems() == 43, "Controller failed to refresh changed chest contents");
        level.destroyBlock(left, false);
        level.destroyBlock(right, false);
        controller.refreshConnections();
        controller.refreshIndex();
        check(controller.index().inventoryCount() == 0 && controller.index().totalItems() == 0, "Destroyed inventories remain in controller index");
        for (var fixture : java.util.List.of(first, second, controller)) level.destroyBlock(fixture.getBlockPos(), false);
    }

    private static StorageBlockEntity storage(ServerPlayer player, BlockPos pos, Block block) {
        player.serverLevel().setBlockAndUpdate(pos, block.defaultBlockState().setValue(StorageBlock.TARGET, Direction.DOWN));
        StorageBlockEntity entity = (StorageBlockEntity) player.serverLevel().getBlockEntity(pos);
        check(entity != null, "Storage fixture missing");
        entity.setOwner(player.getUUID());
        return entity;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private IndexChecks() { }
}
