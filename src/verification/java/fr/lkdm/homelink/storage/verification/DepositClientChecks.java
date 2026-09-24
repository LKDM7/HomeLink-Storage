package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.client.screen.DepositScreen;
import fr.lkdm.homelink.storage.menu.DepositMenu;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;

/** Real server menu transport, client rendering and player shift-click packet path. */
final class DepositClientChecks {
    private static final BlockPos POS = new BlockPos(2, 5, 2);
    static void prepare(ServerPlayer player) {
        player.teleportTo(2.5, 6, 3.5);
        player.serverLevel().setBlockAndUpdate(POS, StorageRegistries.DEPOSIT.get().defaultBlockState());
        var deposit = (DepositBlockEntity) player.serverLevel().getBlockEntity(POS);
        deposit.setOwner(player.getUUID());
        deposit.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 23));
        player.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, 11));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.gameMode.useItemOn(player, player.serverLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(POS.getCenter(), Direction.UP, POS, false));
        check(player.containerMenu instanceof DepositMenu, "Deposit server menu did not open");
    }
    static boolean ready(Minecraft client) {
        return client.screen instanceof DepositScreen && client.player.containerMenu instanceof DepositMenu menu
                && menu.getSlot(0).getItem().is(Items.DIAMOND) && menu.getSlot(0).getItem().getCount() == 23
                && menu.getSlot(27).getItem().is(Items.IRON_INGOT);
    }
    static void click(Minecraft client) {
        var menu = (DepositMenu) client.player.containerMenu;
        check(menu.slots.size() == 63 && menu.status() == 0, "Deposit slots or initial status incorrect");
        var block = StorageRegistries.DEPOSIT.get();
        for (var state : block.getStateDefinition().getPossibleStates())
            check(client.getBlockRenderer().getBlockModel(state) != client.getModelManager().getMissingModel(), "Deposit model missing: " + state);
        check(client.getItemRenderer().getModel(block.asItem().getDefaultInstance(), client.level, client.player, 0)
                != client.getModelManager().getMissingModel(), "Deposit item model missing");
        for (String key : new String[]{ "block.homelink_storage.storage_deposit", "screen.homelink_storage.deposit.not_connected", "screen.homelink_storage.deposit.connect_hint" })
            check(!Component.translatable(key).getString().equals(key), "Deposit translation missing: " + key);
        client.gameMode.handleInventoryMouseClick(menu.containerId, 27, 0, ClickType.QUICK_MOVE, client.player);
    }
    static boolean moved(Minecraft client) {
        return client.player.containerMenu instanceof DepositMenu menu && menu.getSlot(27).getItem().isEmpty()
                && menu.getSlot(1).getItem().is(Items.IRON_INGOT) && menu.getSlot(1).getItem().getCount() == 11;
    }
    static void verifyAndRemove(ServerPlayer player) {
        var deposit = (DepositBlockEntity) player.serverLevel().getBlockEntity(POS);
        check(deposit.inventory().getStackInSlot(0).getCount() == 23, "Original deposit stack changed");
        check(deposit.inventory().getStackInSlot(1).is(Items.IRON_INGOT)
                && deposit.inventory().getStackInSlot(1).getCount() == 11, "Server did not accept client shift-click");
        check(player.getInventory().getItem(9).isEmpty(), "Shift-click duplicated player items");
        player.serverLevel().destroyBlock(POS, false);
    }
    static void prepareModelPreview(ServerPlayer player) {
        var level = player.serverLevel();
        for (int x = 8; x <= 15; x++) for (int z = 8; z <= 13; z++)
            level.setBlockAndUpdate(new BlockPos(x, 4, z), net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(10, 5, 10), StorageRegistries.DEPOSIT.get().defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(12, 5, 10), StorageRegistries.TERMINAL.get().defaultBlockState());
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        player.teleportTo(level, 12.6, 6.6, 6.6, java.util.Set.of(), 24F, 27F);
    }
    static void showKeyPreview(Minecraft client) {
        var stack = StorageRegistries.LINK_KEY.get().getDefaultInstance();
        check(client.getItemRenderer().getModel(stack, client.level, client.player, 0) != client.getModelManager().getMissingModel(),
                "Link key model missing");
        client.setScreen(new net.minecraft.client.gui.screens.Screen(stack.getHoverName()) {
            @Override public boolean isPauseScreen() { return false; }
            @Override public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                graphics.fill(0, 0, width, height, 0xFF252729);
                graphics.drawCenteredString(font, title, width / 2, 18, 0xFFE7E5E0);
                graphics.pose().pushPose();
                graphics.pose().translate(width / 2F - 64, height / 2F - 64, 0);
                graphics.pose().scale(8, 8, 1);
                graphics.renderItem(stack, 0, 0);
                graphics.pose().popPose();
                graphics.renderItem(stack, width - 36, height - 36);
            }
        });
    }
    private static void check(boolean success, String message) { if (!success) throw new IllegalStateException(message); }
}
