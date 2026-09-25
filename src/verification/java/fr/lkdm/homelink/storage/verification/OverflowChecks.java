package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.blockentity.OverflowBlockEntity;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.inventory.StorageInsertion;
import fr.lkdm.homelink.storage.storage.inventory.StorageWithdrawal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real server: overflow chest destination, waiting delay, priorities and objects waiting in a Deposit. */
public final class OverflowChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        BlockPos base = new BlockPos(128, 5, 128); // Chunk (8, 8), away from other fixtures.
        level.getChunkAt(base); // Explicit fixture loading only.
        BlockPos linkPos = base.above(), barrelPos = base.east(), overflowPos = base.east(3), depositPos = base.south(3);
        var saved = player.getInventory().items.stream().map(ItemStack::copy).toList();
        level.setBlockAndUpdate(base, StorageRegistries.CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(linkPos, StorageRegistries.LINK.get().defaultBlockState());
        level.setBlockAndUpdate(barrelPos, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(overflowPos, StorageRegistries.OVERFLOW.get().defaultBlockState());
        level.setBlockAndUpdate(depositPos, StorageRegistries.DEPOSIT.get().defaultBlockState());
        var controller = (StorageBlockEntity) level.getBlockEntity(base);
        var link = (StorageBlockEntity) level.getBlockEntity(linkPos);
        var deposit = (DepositBlockEntity) level.getBlockEntity(depositPos);
        var barrel = (Container) level.getBlockEntity(barrelPos);
        var overflow = (OverflowBlockEntity) level.getBlockEntity(overflowPos);
        try {
            for (var device : java.util.List.of(controller, link, deposit)) device.setOwner(player.getUUID());
            controller.ensureHomeCore();
            check(link.bind(controller) && deposit.bind(controller), "fixture binding");
            barrel.setItem(0, new ItemStack(Items.DIAMOND, 10));
            controller.refreshIndex();
            check(controller.connections().values().stream().anyMatch(connection -> overflowPos.equals(connection.inventoryPos)),
                    "overflow chest not discovered as an inventory");
            check(controller.overflowDestinations().size() == 1, "overflow chest not recognised");

            var items = deposit.inventory();
            items.setStackInSlot(0, new ItemStack(Items.EMERALD, 5));
            items.setStackInSlot(1, new ItemStack(Items.DIAMOND, 3));
            check(StorageInsertion.deposit(controller, deposit, 0, false) == 0, "object without destination was moved by the ordinary pass");
            check(StorageInsertion.deposit(controller, deposit, 1, false) == 3 && barrel.countItem(Items.DIAMOND) == 13
                    && overflow.occupiedSlots() == 0, "known object did not go to its own inventory");
            check(StorageInsertion.deposit(controller, deposit, 0, true) == 5 && count(overflow, Items.EMERALD) == 5 && items.getStackInSlot(0).isEmpty(),
                    "overflow pass did not take the object");
            // Once in the overflow chest, the next emeralds follow the ordinary pass.
            items.setStackInSlot(0, new ItemStack(Items.EMERALD, 2));
            controller.refreshIndex();
            check(StorageInsertion.deposit(controller, deposit, 0, false) == 2 && count(overflow, Items.EMERALD) == 7, "overflow did not become the object's destination");
            // An ordinary inventory holding the same object keeps priority over the overflow chest.
            overflow.inventory().setStackInSlot(20, new ItemStack(Items.DIAMOND, 1));
            items.setStackInSlot(1, new ItemStack(Items.DIAMOND, 4));
            controller.refreshIndex();
            check(StorageInsertion.deposit(controller, deposit, 1, false) == 4 && barrel.countItem(Items.DIAMOND) == 17
                    && count(overflow, Items.DIAMOND) == 1, "overflow chest took priority over an ordinary inventory");

            // Real Deposit ticks: the overflow waits for the delay.
            items.setStackInSlot(2, new ItemStack(Items.GOLD_INGOT, 3));
            var state = level.getBlockState(depositPos);
            int ticks = 0;
            while (ticks < DepositBlockEntity.OVERFLOW_DELAY_TICKS - 20) { DepositBlockEntity.tick(level, depositPos, state, deposit); ticks++; }
            check(count(overflow, Items.GOLD_INGOT) == 0 && deposit.status() == DepositBlockEntity.Status.BLOCKED, "overflow used before the delay");
            while (ticks < DepositBlockEntity.OVERFLOW_DELAY_TICKS + 60 && count(overflow, Items.GOLD_INGOT) == 0) { DepositBlockEntity.tick(level, depositPos, state, deposit); ticks++; }
            check(count(overflow, Items.GOLD_INGOT) == 3 && items.getStackInSlot(2).isEmpty(), "overflow not used after the delay");

            // Full overflow chest: the object waits in the Deposit, visible to Terminals.
            for (int slot = 0; slot < OverflowBlockEntity.SLOTS; slot++)
                if (overflow.inventory().getStackInSlot(slot).isEmpty()) overflow.inventory().setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, 64));
            controller.refreshIndex();
            items.setStackInSlot(3, new ItemStack(Items.BLAZE_ROD, 6));
            check(StorageInsertion.deposit(controller, deposit, 3, true) == 0, "full overflow chest accepted an object");
            controller.reportDeposit(depositPos);
            var waiting = StorageMenu.pendingEntries(controller);
            check(waiting.size() == 1 && waiting.get(0).prototype().is(Items.BLAZE_ROD) && waiting.get(0).total() == 6
                    && waiting.get(0).deposits().get(depositPos) == 6L, "waiting object not listed: " + waiting);
            player.getInventory().items.replaceAll(stack -> ItemStack.EMPTY);
            ItemStack prototype = waiting.get(0).prototype();
            check(StorageWithdrawal.withdrawPending(player, controller, prototype, 0) == 0
                    && StorageWithdrawal.withdrawPending(player, controller, prototype, StorageWithdrawal.MAX_REQUEST + 1) == 0, "pending bounds");
            long revision = controller.pendingRevision();
            check(StorageWithdrawal.withdrawPending(player, controller, prototype, 2) == 2 && player.getInventory().countItem(Items.BLAZE_ROD) == 2
                    && items.getStackInSlot(3).getCount() == 4 && controller.pendingRevision() > revision, "pending withdrawal");
            check(StorageMenu.allowed("withdraw_pending", false) && !StorageMenu.allowed("withdraw_pending", true), "withdraw_pending role");
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_OVERFLOW_CHECKS_OK discovered=true ordinary_first=true delay=true full=true pending_listed=true pending_withdrawal=true");
        } finally {
            for (int i = 0; i < saved.size(); i++) player.getInventory().setItem(i, saved.get(i));
            deposit.inventory().setStackInSlot(0, ItemStack.EMPTY);
            for (int slot = 0; slot < deposit.inventory().getSlots(); slot++) deposit.inventory().setStackInSlot(slot, ItemStack.EMPTY);
            for (int slot = 0; slot < OverflowBlockEntity.SLOTS; slot++) overflow.inventory().setStackInSlot(slot, ItemStack.EMPTY);
            barrel.clearContent();
            for (BlockPos pos : java.util.List.of(depositPos, overflowPos, barrelPos, linkPos, base)) level.removeBlock(pos, false);
        }
    }

    private static int count(OverflowBlockEntity overflow, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < OverflowBlockEntity.SLOTS; slot++) {
            var stack = overflow.inventory().getStackInSlot(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
    private static void check(boolean value, String reason) { if (!value) throw new IllegalStateException("Overflow: " + reason); }
    private OverflowChecks() { }
}
