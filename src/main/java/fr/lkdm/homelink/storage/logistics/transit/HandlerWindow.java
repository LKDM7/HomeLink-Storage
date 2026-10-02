package fr.lkdm.homelink.storage.logistics.transit;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Bounded rotating slice of a live handler; preserves the original face and slot restrictions. */
public record HandlerWindow(IItemHandler delegate, int start, int size) implements IItemHandler {
    public HandlerWindow {
        if (delegate.getSlots() <= 0 || size <= 0 || size > delegate.getSlots()) throw new IllegalArgumentException("Invalid slot window");
    }
    private int slot(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
        return Math.floorMod(start + index, delegate.getSlots());
    }
    @Override public int getSlots() { return size; }
    @Override public ItemStack getStackInSlot(int index) { return delegate.getStackInSlot(slot(index)); }
    @Override public ItemStack insertItem(int index, ItemStack stack, boolean simulate) { return delegate.insertItem(slot(index),stack,simulate); }
    @Override public ItemStack extractItem(int index,int amount,boolean simulate) { return delegate.extractItem(slot(index),amount,simulate); }
    @Override public int getSlotLimit(int index) { return delegate.getSlotLimit(slot(index)); }
    @Override public boolean isItemValid(int index,ItemStack stack) { return delegate.isItemValid(slot(index),stack); }
}
