package fr.lkdm.homelink.storage.blockentity;

import fr.lkdm.homelink.storage.menu.DepositMenu;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Uses the same persistent identity, owner, binding and permissions as other Storage devices. */
public final class DepositBlockEntity extends StorageBlockEntity {
    public enum Status { NOT_CONNECTED, CONTROLLER_OFFLINE, IDLE, SORTING, BLOCKED }
    private Status status = Status.NOT_CONNECTED;
    private int ticks;
    private int nextSlot;
    private boolean transferring;
    private final ItemStackHandler inventory = new ItemStackHandler(27) {
        @Override protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
        }
    };

    public DepositBlockEntity(BlockPos pos, BlockState state) { super(StorageRegistries.DEPOSIT_ENTITY.get(), pos, state); }
    public ItemStackHandler inventory() { return inventory; }
    public Status status() { return status; }
    @Override public boolean canAccess(net.minecraft.server.level.ServerPlayer player) {
        return permission(player, fr.lkdm.homecore.api.security.Permission.CONTROL);
    }
    public boolean beginTransfer() {
        if (transferring) return false;
        transferring = true;
        return true;
    }
    public void endTransfer() { transferring = false; }

    public static void tick(Level level, BlockPos pos, BlockState state, DepositBlockEntity deposit) {
        if (level.isClientSide || ++deposit.ticks < 20) return;
        deposit.ticks = 0;
        if (deposit.controllerPos() == null) { deposit.status = Status.NOT_CONNECTED; return; }
        var controller = deposit.controller();
        if (controller == null || !controller.automationAvailable()) { deposit.status = Status.CONTROLLER_OFFLINE; return; }
        for (int offset = 0; offset < 27; offset++) {
            int slot = (deposit.nextSlot + offset) % 27;
            if (deposit.inventory.getStackInSlot(slot).isEmpty()) continue;
            deposit.nextSlot = (slot + 1) % 27;
            deposit.setChanged();
            int accepted = fr.lkdm.homelink.storage.storage.inventory.StorageInsertion.deposit(controller, deposit, slot);
            deposit.status = accepted > 0 ? Status.SORTING : Status.BLOCKED;
            if (accepted > 0) level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ITEM_PICKUP,
                    net.minecraft.sounds.SoundSource.BLOCKS, 0.12F, 0.8F);
            return;
        }
        deposit.status = Status.IDLE;
    }

    @Override public int comparatorSignal() {
        float fullness = 0;
        int occupied = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                fullness += (float) stack.getCount() / Math.min(inventory.getSlotLimit(slot), stack.getMaxStackSize());
                occupied++;
            }
        }
        return (int) Math.floor(fullness / inventory.getSlots() * 14) + (occupied > 0 ? 1 : 0);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("DepositItems", inventory.serializeNBT(registries));
        tag.putInt("DepositNextSlot", nextSlot);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("DepositItems"));
        nextSlot = Math.floorMod(tag.getInt("DepositNextSlot"), 27);
        ticks = 0;
        status = controllerPos() == null ? Status.NOT_CONNECTED : Status.CONTROLLER_OFFLINE;
    }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new DepositMenu(id, inventory, this); }
}
