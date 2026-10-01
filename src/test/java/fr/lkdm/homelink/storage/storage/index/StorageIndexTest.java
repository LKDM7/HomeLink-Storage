package fr.lkdm.homelink.storage.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

class StorageIndexTest {
    private static final BlockPos CHEST_A = new BlockPos(0, 64, 0);
    private static final BlockPos CHEST_B = new BlockPos(5, 64, 0);

    private static ItemStackHandler inventory(ItemStack... stacks) {
        var handler = new ItemStackHandler(Math.max(1, stacks.length));
        for (int slot = 0; slot < stacks.length; slot++) handler.setStackInSlot(slot, stacks[slot]);
        return handler;
    }

    @Test void totalsAreAttributedToEachInventory() {
        var index = new StorageIndex();
        index.update(CHEST_A, inventory(new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.IRON_INGOT, 10)));
        index.update(CHEST_B, inventory(new ItemStack(Items.IRON_INGOT, 6)));
        var entry = index.find(new ItemStack(Items.IRON_INGOT));
        assertNotNull(entry);
        assertEquals(80, entry.total());
        assertEquals(74L, entry.locations().get(CHEST_A));
        assertEquals(6L, entry.locations().get(CHEST_B));
        assertEquals(80, index.totalItems());
        assertEquals(2, index.inventoryCount());
        assertEquals(2, index.fullInventories(), "every slot of both inventories is occupied");
    }

    @Test void componentsMakeDistinctVariantsOfOneItem() {
        var named = new ItemStack(Items.DIAMOND_SWORD);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
        var index = new StorageIndex();
        index.update(CHEST_A, inventory(new ItemStack(Items.DIAMOND_SWORD), named));
        assertEquals(2, index.variants());
        assertEquals(1, index.uniqueItems());
        assertEquals(1, index.find(named).total());
        assertTrue(StorageIndex.sameVariant(named, named.copy()));
    }

    @Test void unchangedScanDoesNotBumpTheRevision() {
        var index = new StorageIndex();
        index.update(CHEST_A, inventory(new ItemStack(Items.COAL, 3)));
        long revision = index.revision();
        index.update(CHEST_A, inventory(new ItemStack(Items.COAL, 3)));
        assertEquals(revision, index.revision());
        index.update(CHEST_A, inventory(new ItemStack(Items.COAL, 2)));
        assertTrue(index.revision() > revision);
        assertEquals(2, index.find(new ItemStack(Items.COAL)).total());
    }

    @Test void offlineInventoriesNeverCountAsStock() {
        var index = new StorageIndex();
        index.update(CHEST_A, inventory(new ItemStack(Items.WHEAT, 20)));
        index.update(CHEST_B, inventory(new ItemStack(Items.WHEAT, 5), new ItemStack(Items.CARROT, 7)));
        index.retain(Set.of(CHEST_A));
        assertEquals(20, index.find(new ItemStack(Items.WHEAT)).total());
        assertNull(index.find(new ItemStack(Items.CARROT)), "an emptied variant disappears");
        assertEquals(20, index.totalItems());
        index.remove(CHEST_A);
        assertEquals(0, index.totalItems());
        assertEquals(0, index.variants());
        assertEquals(0, index.totalSlots());
    }

    @Test void emptyStacksAreNeverFound() {
        assertNull(new StorageIndex().find(ItemStack.EMPTY));
    }
}
