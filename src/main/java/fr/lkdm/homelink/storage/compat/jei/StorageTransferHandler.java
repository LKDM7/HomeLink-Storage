package fr.lkdm.homelink.storage.compat.jei;

import fr.lkdm.homelink.storage.client.recipe.IngredientPlan;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.network.StoragePackets;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * JEI "+" button on any recipe while the Terminal is open: fetches the missing item inputs
 * into the player's inventory (shift: as many crafts as one stack of each allows).
 * Slots the network cannot fill are highlighted in red.
 */
final class StorageTransferHandler implements IUniversalRecipeTransferHandler<StorageMenu> {
    private final IRecipeTransferHandlerHelper helper;

    StorageTransferHandler(IRecipeTransferHandlerHelper helper) { this.helper = helper; }

    @Override public Class<? extends StorageMenu> getContainerClass() { return StorageMenu.class; }

    @Override public Optional<MenuType<StorageMenu>> getMenuType() { return Optional.of(StorageRegistries.STORAGE_MENU.get()); }

    @Override public IRecipeTransferError transferRecipe(StorageMenu menu, Object recipe, IRecipeSlotsView recipeSlots,
                                                         Player player, boolean maxTransfer, boolean doTransfer) {
        if (!menu.clientStats().connected()) return helper.createUserErrorWithTooltip(text("disconnected"));
        List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        List<List<ItemStack>> accepted = inputs.stream().map(view -> view.getItemStacks().toList()).toList();
        if (accepted.stream().allMatch(List::isEmpty)) return helper.createUserErrorWithTooltip(text("no_items"));
        IngredientPlan.Result plan = IngredientPlan.plan(accepted, menu.clientRows(), player.getInventory().items, maxTransfer);
        if (!plan.complete())
            return helper.createUserErrorForMissingSlots(text("missing"), plan.missingSlots().stream().map(inputs::get).toList());
        String encoded = plan.encode();
        if (plan.withdrawals().size() > StorageMenu.MAX_BATCH || encoded.length() > StoragePackets.MAX_VALUE)
            return helper.createUserErrorWithTooltip(text("too_many"));
        if (!doTransfer) return null;
        if (plan.nothingToFetch()) player.displayClientMessage(text("already_owned"), true);
        else menu.send("withdraw_batch", "", encoded);
        return null;
    }

    private static Component text(String key) { return Component.translatable("jei.homelink_storage." + key); }
}
