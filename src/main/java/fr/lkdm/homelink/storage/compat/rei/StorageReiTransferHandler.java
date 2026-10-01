package fr.lkdm.homelink.storage.compat.rei;

import fr.lkdm.homelink.storage.client.recipe.IngredientPlan;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.network.StoragePackets;
import java.util.List;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * REI "+" button on any recipe while the Terminal is open: fetches the missing item inputs into
 * the player's inventory (shift: as many crafts as one stack of each allows), exactly like JEI.
 */
final class StorageReiTransferHandler implements TransferHandler {
    @Override public Result handle(Context context) {
        if (!(context.getMenu() instanceof StorageMenu menu)) return Result.createNotApplicable();
        if (!menu.clientStats().connected()) return Result.createFailed(text("disconnected"));
        var player = context.getMinecraft().player;
        if (player == null) return Result.createNotApplicable();
        List<EntryIngredient> inputs = context.getDisplay().getInputEntries();
        List<List<ItemStack>> accepted = inputs.stream().map(ingredient -> ingredient.stream()
                .filter(entry -> entry.getType() == VanillaEntryTypes.ITEM)
                .map(entry -> (ItemStack) entry.castValue()).toList()).toList();
        if (accepted.stream().allMatch(List::isEmpty)) return Result.createFailed(text("no_items"));
        IngredientPlan.Result plan = IngredientPlan.plan(accepted, menu.clientRows(), player.getInventory().items, context.isStackedCrafting());
        if (!plan.complete())
            return Result.createFailed(text("missing")).tooltipMissing(plan.missingSlots().stream().map(inputs::get).toList());
        String encoded = plan.encode();
        if (plan.withdrawals().size() > StorageMenu.MAX_BATCH || encoded.length() > StoragePackets.MAX_VALUE)
            return Result.createFailed(text("too_many"));
        if (!context.isActuallyCrafting()) return Result.createSuccessful().blocksFurtherHandling();
        if (plan.nothingToFetch()) player.displayClientMessage(text("already_owned"), true);
        else menu.send("withdraw_batch", "", encoded);
        // Stay on REI's screen like JEI does: the items arrive in the inventory, nothing is crafted.
        return Result.createSuccessful().blocksFurtherHandling();
    }

    private static Component text(String key) { return Component.translatable("jei.homelink_storage." + key); }
}
