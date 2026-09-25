package fr.lkdm.homelink.storage.client.recipe;

import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.network.StorageData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.world.item.ItemStack;

/**
 * Plans which storage rows must be withdrawn so the player's inventory holds a recipe's inputs.
 * Pure client-side arithmetic over the synchronized cache: the server still validates every row.
 */
public final class IngredientPlan {
    /** Result: rows to fetch (id to count), input slots nothing can fill, and planned crafts. */
    public record Result(Map<Long, Integer> withdrawals, List<Integer> missingSlots, int crafts) {
        public boolean complete() { return missingSlots.isEmpty(); }
        public boolean nothingToFetch() { return complete() && withdrawals.isEmpty(); }
        public int total() { return withdrawals.values().stream().mapToInt(Integer::intValue).sum(); }
        /** Encoded "row:amount;row:amount" value for the {@code withdraw_batch} command. */
        public String encode() {
            return withdrawals.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue()).collect(Collectors.joining(";"));
        }
    }

    /**
     * @param slots accepted stacks per recipe input slot; empty lists are ignored
     * @param rows synchronized storage rows
     * @param inventory the player's main inventory, counted before fetching anything
     * @param maxTransfer plan as many crafts as the smallest ingredient stack allows, instead of one
     */
    public static Result plan(List<List<ItemStack>> slots, Collection<StorageData.Row> rows, List<ItemStack> inventory, boolean maxTransfer) {
        List<Integer> used = new ArrayList<>();
        List<List<StorageData.Row>> candidates = new ArrayList<>();
        int limit = 64;
        for (int i = 0; i < slots.size(); i++) {
            List<ItemStack> accepted = slots.get(i).stream().filter(stack -> !stack.isEmpty()).toList();
            if (accepted.isEmpty()) { candidates.add(List.of()); continue; }
            used.add(i);
            limit = Math.min(limit, accepted.get(0).getMaxStackSize());
            // Plain variants first: a recipe should not silently consume a renamed or enchanted item.
            List<StorageData.Row> matching = new ArrayList<>();
            for (StorageData.Row row : rows) if (row.id() >= 0 && matches(accepted, row.stack())) matching.add(row);
            matching.sort((a, b) -> Boolean.compare(!a.stack().isComponentsPatchEmpty(), !b.stack().isComponentsPatchEmpty()));
            candidates.add(matching);
        }
        Result single = attempt(slots, used, candidates, inventory, 1);
        if (!maxTransfer || !single.complete()) return single;
        int low = 1, high = Math.max(1, limit);
        Result best = single;
        while (low < high) {
            int middle = (low + high + 1) / 2;
            Result candidate = attempt(slots, used, candidates, inventory, middle);
            if (candidate.complete() && candidate.withdrawals().size() <= StorageMenu.MAX_BATCH) { best = candidate; low = middle; }
            else high = middle - 1;
        }
        return best;
    }

    private static Result attempt(List<List<ItemStack>> slots, List<Integer> used, List<List<StorageData.Row>> candidates,
                                  List<ItemStack> inventory, int crafts) {
        int[] owned = new int[inventory.size()];
        for (int i = 0; i < owned.length; i++) owned[i] = inventory.get(i).getCount();
        Map<Long, Long> remaining = new HashMap<>();
        Map<Long, Integer> withdrawals = new LinkedHashMap<>();
        Set<Integer> missing = new LinkedHashSet<>();
        for (int craft = 0; craft < crafts; craft++) {
            for (int slot : used) {
                List<ItemStack> accepted = slots.get(slot);
                if (take(accepted, inventory, owned)) continue;
                boolean found = false;
                for (StorageData.Row row : candidates.get(slot)) {
                    long left = remaining.computeIfAbsent(row.id(), id -> row.count());
                    if (left <= 0) continue;
                    remaining.put(row.id(), left - 1);
                    withdrawals.merge(row.id(), 1, Integer::sum);
                    found = true;
                    break;
                }
                if (!found) missing.add(slot);
            }
        }
        return new Result(withdrawals, List.copyOf(missing), crafts);
    }

    private static boolean take(List<ItemStack> accepted, List<ItemStack> inventory, int[] owned) {
        for (int i = 0; i < owned.length; i++) {
            if (owned[i] > 0 && matches(accepted, inventory.get(i))) { owned[i]--; return true; }
        }
        return false;
    }

    /** Exact variant shown by the recipe, or the plain item when the recipe shows a plain item. */
    static boolean matches(List<ItemStack> accepted, ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (ItemStack option : accepted) {
            if (!stack.is(option.getItem())) continue;
            if (ItemStack.isSameItemSameComponents(option, stack) || stack.isComponentsPatchEmpty()) return true;
        }
        return false;
    }

    private IngredientPlan() { }
}
