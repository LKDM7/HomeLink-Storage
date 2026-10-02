package fr.lkdm.homelink.storage.logistics.filter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Item-type filter of one pipe face. It selects types by registry ID only; data components
 * never widen or narrow the choice. An empty whitelist lets nothing pass, an empty blacklist
 * lets everything pass. An ID whose item no longer exists stays in the set: it matches nothing,
 * so it can neither open a whitelist nor close a blacklist on other items.
 */
public record PipeFilter(FilterMode mode, Set<ResourceLocation> items) {
    public static final PipeFilter OPEN = new PipeFilter(FilterMode.BLACKLIST, Set.of());

    public PipeFilter {
        Objects.requireNonNull(mode, "mode");
        items = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(items));
    }

    public boolean allows(ResourceLocation item) {
        boolean selected = items.contains(item);
        return mode == FilterMode.WHITELIST ? selected : !selected;
    }

    public boolean allows(ItemStack stack) {
        return !stack.isEmpty() && allows(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** Outcome of validating a list received from a client. */
    public enum Problem { NONE, INVALID_ID, UNKNOWN_ID, TOO_MANY }

    public record Validation(Problem problem, Set<ResourceLocation> items, String detail) {
        public boolean ok() { return problem == Problem.NONE; }
    }

    /**
     * Parses a client selection. Duplicates collapse into one entry. A new ID must name a
     * registered item; an unknown ID is accepted only when the face already stored it, so a
     * missing mod item survives an edit without anyone being able to add arbitrary text.
     *
     * @param raw IDs sent by the client, in display order
     * @param stored IDs the face already holds
     * @param known whether an ID names a registered item
     * @param maximum most entries allowed after duplicates collapse
     */
    public static Validation validate(List<String> raw, Collection<ResourceLocation> stored,
                                      Predicate<ResourceLocation> known, int maximum) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (String text : raw) {
            ResourceLocation id = text == null || text.length() > 256 ? null : ResourceLocation.tryParse(text);
            if (id == null) return new Validation(Problem.INVALID_ID, Set.of(), String.valueOf(text));
            if (!known.test(id) && !stored.contains(id)) return new Validation(Problem.UNKNOWN_ID, Set.of(), id.toString());
            result.add(id);
            if (result.size() > maximum) return new Validation(Problem.TOO_MANY, Set.of(), Integer.toString(maximum));
        }
        return new Validation(Problem.NONE, result, "");
    }

    /** Whether an ID names a registered item other than air. */
    public static boolean registered(ResourceLocation id) {
        return BuiltInRegistries.ITEM.containsKey(id) && !id.equals(BuiltInRegistries.ITEM.getDefaultKey());
    }
}
