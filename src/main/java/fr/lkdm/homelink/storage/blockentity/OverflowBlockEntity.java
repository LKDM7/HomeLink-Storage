package fr.lkdm.homelink.storage.blockentity;

import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Plain 54-slot inventory. Links discover it like any chest; Deposits send it the objects
 * that no other inventory of the network accepts. It deliberately is not a Storage device.
 */
public final class OverflowBlockEntity extends BlockEntity {
    public static final int SLOTS = 54;
    private final ItemStackHandler inventory = new ItemStackHandler(SLOTS) {
        @Override protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    };

    public OverflowBlockEntity(BlockPos pos, BlockState state) { super(StorageRegistries.OVERFLOW_ENTITY.get(), pos, state); }
    public ItemStackHandler inventory() { return inventory; }

    public int occupiedSlots() {
        int occupied = 0;
        for (int slot = 0; slot < SLOTS; slot++) if (!inventory.getStackInSlot(slot).isEmpty()) occupied++;
        return occupied;
    }

    public int comparatorSignal() {
        int occupied = occupiedSlots();
        return occupied == 0 ? 0 : 1 + occupied * 14 / SLOTS;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("OverflowItems", inventory.serializeNBT(registries));
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("OverflowItems"));
    }
}
