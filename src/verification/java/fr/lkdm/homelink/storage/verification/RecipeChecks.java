package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;

public final class RecipeChecks {
    public static void run(ServerPlayer player) {
        Item iron = Items.IRON_INGOT, copper = Items.COPPER_INGOT, redstone = Items.REDSTONE;
        Item board = fr.lkdm.homecore.registry.HomeCoreItems.HOMELINK_CIRCUIT_BOARD.get(), chip = fr.lkdm.homecore.registry.HomeCoreItems.HOMELINK_MICROPROCESSOR.get();
        Item comms = fr.lkdm.homecore.registry.HomeCoreItems.HOMELINK_COMMUNICATION_MODULE.get(), control = fr.lkdm.homecore.registry.HomeCoreItems.HOMELINK_CONTROL_MODULE.get();
        recipe(player, "storage_controller", List.of(iron, copper, iron, redstone, chip, redstone, iron, copper, iron), StorageRegistries.CONTROLLER.get().asItem(), 1);
        recipe(player, "storage_terminal", List.of(iron, Items.GLASS, iron, redstone, board, redstone, iron, copper, iron), StorageRegistries.TERMINAL.get().asItem(), 1);
        recipe(player, "storage_link", List.of(Items.AIR, copper, Items.AIR, redstone, comms, redstone, Items.AIR, iron, Items.AIR), StorageRegistries.LINK.get().asItem(), 4);
        recipe(player, "storage_repeater", List.of(Items.AIR, comms, Items.AIR, redstone, Items.REPEATER, redstone, iron, iron, iron), StorageRegistries.REPEATER.get().asItem(), 1);
        recipe(player, "storage_deposit", List.of(Items.AIR, Items.CHEST, Items.AIR, redstone, Items.HOPPER, redstone, Items.AIR, control, Items.AIR), StorageRegistries.DEPOSIT.get().asItem(), 1);
        recipe(player, "storage_overflow", List.of(Items.AIR, board, Items.AIR, Items.CHEST, chip, Items.CHEST, iron, iron, iron), StorageRegistries.OVERFLOW.get().asItem(), 1);
        gestures(player);
        LogUtils.getLogger().info("STORAGE_RECIPE_CHECKS_OK recipes=6 matches=true results=true binding_gesture=true unauthorized_binding_denied=true");
    }

    private static void recipe(ServerPlayer player, String path, List<Item> ingredients, Item result, int count) {
        var holder = player.serverLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("homelink_storage", path)).orElseThrow();
        check(holder.value() instanceof CraftingRecipe, "Recipe is not a crafting recipe: " + path);
        CraftingRecipe recipe = (CraftingRecipe) holder.value();
        CraftingInput input = CraftingInput.of(3, 3, ingredients.stream().map(Item::getDefaultInstance).toList());
        check(recipe.matches(input, player.serverLevel()), "Expected crafting grid does not match: " + path);
        ItemStack output = recipe.assemble(input, player.registryAccess());
        check(output.is(result) && output.getCount() == count, "Incorrect recipe result: " + path);
        CraftingInput invalid = CraftingInput.of(3, 3, java.util.Collections.nCopies(9, Items.DIRT.getDefaultInstance()));
        check(!recipe.matches(invalid, player.serverLevel()), "Recipe accepted an incorrect grid: " + path);
    }

    private static void gestures(ServerPlayer player) {
        StorageBlockEntity controller = place(player, new BlockPos(6, 5, 0), StorageRegistries.CONTROLLER.get(), player.getUUID());
        controller.ensureHomeCore();
        StorageBlockEntity link = place(player, new BlockPos(6, 5, 2), StorageRegistries.LINK.get(), player.getUUID());
        StorageBlockEntity denied = place(player, new BlockPos(6, 5, 4), StorageRegistries.LINK.get(), UUID.fromString("76b3dfea-0bb0-4fd0-97aa-8bec999192c8"));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        try {
            interact(player, controller.getBlockPos());
            interact(player, link.getBlockPos());
            check(link.controller() == controller, "Sneak-use gesture did not bind the link");
            interact(player, denied.getBlockPos());
            check(denied.controller() == null, "Sneak-use gesture bypassed ownership");
            // Only the Terminal browses storage: infrastructure blocks report state without any menu.
            player.setShiftKeyDown(false);
            StorageBlockEntity repeater = place(player, new BlockPos(6, 5, 6), StorageRegistries.REPEATER.get(), player.getUUID());
            try {
                for (var block : List.of(link, repeater)) {
                    interact(player, block.getBlockPos());
                    check(player.containerMenu == player.inventoryMenu, "Link or Repeater opened a menu: " + block.getBlockState().getBlock());
                    check(!new fr.lkdm.homelink.storage.menu.StorageMenu(0, player.getInventory(), block.getBlockPos()).stillValid(player),
                            "Storage menu accepted a Link or Repeater source: " + block.getBlockState().getBlock());
                }
                // The Controller opens its management screen; the Terminal only takes items.
                interact(player, controller.getBlockPos());
                check(player.containerMenu instanceof fr.lkdm.homelink.storage.menu.StorageMenu menu && menu.managesNetwork(),
                        "Controller did not open its management screen");
                player.closeContainer();
                for (String action : List.of("rename_controller", "rename_inventory", "set_zone", "create_zone", "forget_inventory", "refresh")) {
                    check(fr.lkdm.homelink.storage.menu.StorageMenu.allowed(action, true), "Controller refused " + action);
                    check(!fr.lkdm.homelink.storage.menu.StorageMenu.allowed(action, false), "Terminal accepted " + action);
                }
                for (String action : List.of("locate", "withdraw", "withdraw_any", "withdraw_batch")) {
                    check(fr.lkdm.homelink.storage.menu.StorageMenu.allowed(action, false), "Terminal refused " + action);
                    check(!fr.lkdm.homelink.storage.menu.StorageMenu.allowed(action, true), "Controller accepted " + action);
                }
                check(statusKey(repeater).endsWith("node_unbound"), "Unbound repeater status");
                var alone = fr.lkdm.homelink.storage.storage.network.StorageBinding.coverage(repeater).chunks();
                check(alone.size() == 1 && alone.get(0).focus() && !alone.get(0).active()
                        && alone.get(0).x() == (repeater.getBlockPos().getX() >> 4) && alone.get(0).z() == (repeater.getBlockPos().getZ() >> 4),
                        "Unbound repeater coverage zone");
                var bound = fr.lkdm.homelink.storage.storage.network.StorageBinding.coverage(link).chunks();
                check(bound.stream().filter(fr.lkdm.homelink.storage.network.CoverageState.Chunk::focus).count() == 1
                        && bound.stream().anyMatch(chunk -> chunk.focus() && chunk.node().equals(link.getBlockPos())), "Bound link coverage zone");
                check(!statusKey(link).endsWith("node_unbound"), "Bound link status");
            } finally { player.serverLevel().destroyBlock(repeater.getBlockPos(), false); }
            LogUtils.getLogger().info("STORAGE_TERMINAL_ONLY_CHECKS_OK controller_management=true link_menu=false repeater_menu=false server_menu_rejected=true role_actions=true status=true");
        } finally {
            player.setShiftKeyDown(false);
            player.getPersistentData().remove("HomeLinkStorageSelection");
            for (var fixture : List.of(link, denied, controller)) player.serverLevel().destroyBlock(fixture.getBlockPos(), false);
        }
    }

    private static String statusKey(StorageBlockEntity block) {
        return ((net.minecraft.network.chat.contents.TranslatableContents)
                fr.lkdm.homelink.storage.storage.network.StorageBinding.status(block).getContents()).getKey();
    }
    private static void interact(ServerPlayer player, BlockPos pos) {
        player.gameMode.useItemOn(player, player.serverLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
    }
    private static StorageBlockEntity place(ServerPlayer player, BlockPos pos, Block block, UUID owner) {
        player.serverLevel().setBlockAndUpdate(pos, block.defaultBlockState().setValue(StorageBlock.TARGET, Direction.DOWN));
        StorageBlockEntity entity = (StorageBlockEntity) player.serverLevel().getBlockEntity(pos);
        entity.setOwner(owner);
        return entity;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private RecipeChecks() { }
}
