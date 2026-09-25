package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.client.recipe.IngredientPlan;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.network.StorageData;
import fr.lkdm.homelink.storage.storage.inventory.StorageWithdrawal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Recipe-viewer planning and Terminal click shortcuts, checked without a viewer installed. */
public final class IngredientPlanChecks {
    public static void run() {
        ItemStack named = new ItemStack(Items.DIAMOND);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Heirloom"));
        List<StorageData.Row> rows = List.of(
                row(1, new ItemStack(Items.DIAMOND), 5), row(2, new ItemStack(Items.STICK), 100),
                row(3, named, 10), row(4, new ItemStack(Items.SPRUCE_PLANKS), 8));
        List<List<ItemStack>> pickaxe = List.of(of(Items.DIAMOND), of(Items.DIAMOND), of(Items.DIAMOND), List.of(), of(Items.STICK), List.of(), of(Items.STICK));
        List<ItemStack> empty = Collections.nCopies(36, ItemStack.EMPTY);

        var plan = IngredientPlan.plan(pickaxe, rows, empty, false);
        check(plan.complete() && plan.withdrawals().equals(Map.of(1L, 3, 2L, 2)) && plan.crafts() == 1, "single craft plan");
        check(plan.encode().equals("1:3;2:2"), "batch encoding");

        List<ItemStack> owned = new ArrayList<>(empty);
        owned.set(5, new ItemStack(Items.DIAMOND, 2)); owned.set(9, new ItemStack(Items.STICK, 2));
        plan = IngredientPlan.plan(pickaxe, rows, owned, false);
        check(plan.complete() && plan.withdrawals().equals(Map.of(1L, 1)), "inventory counted before fetching");
        owned.set(5, new ItemStack(Items.DIAMOND, 3));
        check(IngredientPlan.plan(pickaxe, rows, owned, false).nothingToFetch(), "nothing to fetch");

        // Five plain diamonds allow one craft only: the renamed variant is never used implicitly.
        plan = IngredientPlan.plan(pickaxe, rows, empty, true);
        check(plan.complete() && plan.crafts() == 1 && plan.withdrawals().get(1L) == 3 && !plan.withdrawals().containsKey(3L), "max transfer bounded by plain stock");
        plan = IngredientPlan.plan(List.of(of(Items.STICK)), rows, empty, true);
        check(plan.crafts() == 64 && plan.total() == 64, "max transfer bounded by stack size");

        plan = IngredientPlan.plan(List.of(of(Items.STICK), of(Items.EMERALD)), rows, empty, false);
        check(!plan.complete() && plan.missingSlots().equals(List.of(1)), "missing slot reported");
        plan = IngredientPlan.plan(List.of(List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.SPRUCE_PLANKS))), rows, empty, false);
        check(plan.complete() && plan.withdrawals().equals(Map.of(4L, 1)), "tag alternatives");
        plan = IngredientPlan.plan(List.of(List.of(named.copy())), List.of(row(3, named, 10)), empty, false);
        check(plan.complete() && plan.withdrawals().equals(Map.of(3L, 1)), "explicit variant");
        check(IngredientPlan.plan(List.of(List.of(), List.of()), rows, empty, false).nothingToFetch(), "empty slots ignored");

        check(StorageScreen.clickAmount(0, false, false, false, 500, 64) == 0, "plain click only selects");
        check(StorageScreen.clickAmount(0, false, false, true, 500, 64) == 64
                && StorageScreen.clickAmount(0, true, false, false, 500, 16) == 16, "stack shortcuts");
        check(StorageScreen.clickAmount(1, false, false, false, 500, 64) == 32
                && StorageScreen.clickAmount(1, false, false, false, 5, 64) == 3
                && StorageScreen.clickAmount(1, false, false, false, 1, 64) == 1, "half stack");
        check(StorageScreen.clickAmount(0, true, true, false, 500, 64) == 1, "control takes one");
        check(StorageScreen.clickAmount(2, false, false, false, 500, 64) == StorageWithdrawal.MAX_REQUEST, "middle click fills");
        com.mojang.logging.LogUtils.getLogger().info("STORAGE_INGREDIENT_PLAN_CHECKS_OK inventory=true max_transfer=true missing=true tags=true variants=true clicks=true");
    }

    private static StorageData.Row row(long id, ItemStack stack, long count) { return new StorageData.Row(id, stack, count, Map.of()); }
    private static List<ItemStack> of(net.minecraft.world.item.Item item) { return List.of(new ItemStack(item)); }
    private static void check(boolean value, String reason) { if (!value) throw new IllegalStateException("Ingredient plan: " + reason); }
    private IngredientPlanChecks() { }
}
