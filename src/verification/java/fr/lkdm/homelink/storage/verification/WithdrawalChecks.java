package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.inventory.StorageWithdrawal;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;

public final class WithdrawalChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        BlockPos base = new BlockPos(96, 5, 96);
        level.getChunkAt(base); // Explicit fixture loading only.
        var saved = player.getInventory().items.stream().map(ItemStack::copy).toList();
        level.setBlockAndUpdate(base, StorageRegistries.CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(base.above(), StorageRegistries.LINK.get().defaultBlockState());
        level.setBlockAndUpdate(base.east(), Blocks.BARREL.defaultBlockState());
        var controller = (StorageBlockEntity) level.getBlockEntity(base);
        var link = (StorageBlockEntity) level.getBlockEntity(base.above());
        controller.setOwner(player.getUUID()); link.setOwner(player.getUUID()); link.bind(controller);
        var barrel = (Container) level.getBlockEntity(base.east());
        try {
            player.getInventory().items.replaceAll(stack -> ItemStack.EMPTY);
            barrel.setItem(0, new ItemStack(Items.DIAMOND, 50));
            barrel.setItem(1, new ItemStack(Items.DIAMOND, 50));
            controller.refreshIndex();
            UUID inventory = controller.connections().keySet().iterator().next();
            long row = controller.index().entries().iterator().next().id();
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, -1) == 0, "negative amount");
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 65) == 0, "oversized amount");
            check(StorageWithdrawal.withdraw(player, controller, UUID.randomUUID(), row, 1) == 0, "foreign inventory");
            controller.setOwner(UUID.randomUUID());
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 1) == 0, "non-owner withdrawal");
            controller.setOwner(player.getUUID());
            controller.ensureHomeCore();
            var viewer = new ServerPlayer(player.server, level,
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "StorageViewer"),
                    net.minecraft.server.level.ClientInformation.createDefault());
            fr.lkdm.homecore.api.DashboardAPI.networks(player.server).setMember(controller.networkId(), viewer.getUUID(),
                    fr.lkdm.homecore.api.network.NetworkRole.VIEWER);
            check(controller.canAccess(viewer), "VIEWER cannot inspect fixture");
            check(StorageWithdrawal.withdraw(viewer, controller, inventory, row, 1) == 0 && barrel.countItem(Items.DIAMOND) == 100,
                    "HomeCore VIEWER extracted objects");
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 64) == 64, "multi-slot withdrawal");
            check(barrel.countItem(Items.DIAMOND) == 36 && player.getInventory().countItem(Items.DIAMOND) == 64
                    && controller.index().totalItems() == 36, "source/destination/index conservation");
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 64) == 36, "remaining stock limit");
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 64) == 0, "stale row duplicated items");
            check(player.getInventory().countItem(Items.DIAMOND) == 100 && barrel.isEmpty(), "repeat conservation");
            barrel.setItem(0, new ItemStack(Items.DIAMOND, 10)); controller.refreshIndex();
            row = controller.index().entries().iterator().next().id();
            player.getInventory().items.replaceAll(stack -> new ItemStack(Items.COBBLESTONE, 64));
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 10) == 0 && barrel.countItem(Items.DIAMOND) == 10, "full inventory consumed source");
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 62));
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 10) == 2 && barrel.countItem(Items.DIAMOND) == 8, "partial destination capacity");
            player.getInventory().items.replaceAll(stack -> ItemStack.EMPTY);
            ItemStack named = new ItemStack(Items.DIAMOND, 8);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Different variant")); barrel.setItem(0, named);
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 8) == 0 && barrel.countItem(Items.DIAMOND) == 8, "variant mismatch");
            row = controller.index().entries().iterator().next().id();
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 1) == 1
                    && ItemStack.isSameItemSameComponents(player.getInventory().getItem(0), named)
                    && barrel.countItem(Items.DIAMOND) == 7, "exact variant components not preserved");
            level.destroyBlock(base.east(), false);
            check(StorageWithdrawal.withdraw(player, controller, inventory, row, 8) == 0, "destroyed source");
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_WITHDRAWAL_CHECKS_OK conservation=true multi_slot=true stale=true capacity=true components=true authorization=true viewer_denied=true bounds=true destroyed=true");
        } finally {
            for (int i=0;i<saved.size();i++) player.getInventory().setItem(i,saved.get(i));
            level.removeBlock(base.above(), false); level.removeBlock(base.east(), false); level.removeBlock(base, false);
        }
    }
    public static void request(StorageScreen screen) {
        screen.setSearchForTest("minecraft:diamond");
        screen.withdraw(1);
    }
    public static boolean received(StorageMenu menu) { return menu.clientStats().items() == 160; }
    public static void verify(ServerPlayer player) {
        Container chest = (Container) player.serverLevel().getBlockEntity(new BlockPos(0,5,3));
        check(chest.countItem(Items.DIAMOND) == 30 && player.getInventory().countItem(Items.DIAMOND) == 1,
                "real GUI request did not conserve diamonds");
        com.mojang.logging.LogUtils.getLogger().info("STORAGE_WITHDRAWAL_CLIENT_OK real_packet=true source=30 player=1 delta=160");
    }
    private static void check(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
}
