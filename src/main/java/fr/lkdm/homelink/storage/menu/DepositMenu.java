package fr.lkdm.homelink.storage.menu;

import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

/** Vanilla container synchronization; all access and mutations remain server-authorized. */
public final class DepositMenu extends AbstractContainerMenu {
    private final DepositBlockEntity source;
    private final BlockPos pos;
    private final String controllerName;
    private final DataSlot status = DataSlot.standalone();

    public DepositMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) {
        this(id, inventory, data.readBlockPos(), data.readUtf(256), null);
    }

    public DepositMenu(int id, Inventory inventory, DepositBlockEntity source) {
        this(id, inventory, source.getBlockPos(), source.controller() == null ? "" : source.controller().logicalName(), source);
    }

    private DepositMenu(int id, Inventory inventory, BlockPos pos, String name, DepositBlockEntity source) {
        super(StorageRegistries.DEPOSIT_MENU.get(), id);
        this.source = source;
        this.pos = pos.immutable();
        this.controllerName = name;
        ItemStackHandler handler = source == null ? new ItemStackHandler(27) : source.inventory();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new SlotItemHandler(handler, col + row * 9, 17 + col * 18, 51 + row * 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 17 + col * 18, 116 + row * 18));
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 17 + col * 18, 174));
        addDataSlot(status);
        if (source != null) status.set(source.status().ordinal());
    }

    public int status() { return status.get(); }
    public String controllerName() { return controllerName; }

    @Override public void broadcastChanges() {
        if (source != null) status.set(source.status().ordinal());
        super.broadcastChanges();
    }

    @Override public boolean stillValid(Player player) {
        if (source == null) return player.level().isClientSide;
        return !source.isRemoved() && player.level().getBlockEntity(pos) == source
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64
                && player instanceof ServerPlayer server && source.canAccess(server);
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player)) return;
        super.clicked(slot, button, type, player);
        // Vanilla can mutate an existing stack without calling ItemStackHandler.setStackInSlot.
        if (source != null) {
            source.setChanged();
            player.level().updateNeighbourForOutputSignal(pos, source.getBlockState().getBlock());
        }
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < 27) {
            if (!moveItemStackTo(stack, 27, slots.size(), true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 0, 27, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        slot.onTake(player, stack);
        if (source != null) source.setChanged();
        return original;
    }
}
