package fr.lkdm.homelink.storage.client.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Every registered item of the game and its mods, for picking filter entries without owning
 * them. Names are read once per language; searches are cached. No recipe viewer is needed.
 */
public final class ItemCatalog {
    public record Entry(ResourceLocation id, Item item, ItemStack icon, String name, String search) { }

    private static List<Entry> entries = List.of();
    private static Map<ResourceLocation, Entry> index = Map.of();
    private static String language = "";
    private static final Map<String, List<Entry>> SEARCHES = new LinkedHashMap<>(16, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Entry>> eldest) { return size() > 32; }
    };

    public static List<Entry> all() {
        String current = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!current.equals(language) || entries.isEmpty()) {
            List<Entry> list = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                ItemStack icon = new ItemStack(item);
                String name = icon.getHoverName().getString();
                list.add(new Entry(id, item, icon, name, (name + " " + id).toLowerCase(Locale.ROOT)));
            }
            list.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(entry -> entry.id().toString()));
            entries = List.copyOf(list);
            Map<ResourceLocation, Entry> map = new java.util.HashMap<>();
            for (Entry entry : entries) map.put(entry.id(), entry);
            index = map;
            language = current;
            SEARCHES.clear();
        }
        return entries;
    }

    /** Matches the localized name or the ID; "@namespace" keeps one mod's items. */
    public static List<Entry> search(String query) {
        List<Entry> all = all();
        String text = query.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) return all;
        return SEARCHES.computeIfAbsent(text, key -> {
            String mod = null;
            String rest = key;
            if (key.startsWith("@")) {
                int space = key.indexOf(' ');
                mod = space < 0 ? key.substring(1) : key.substring(1, space);
                rest = space < 0 ? "" : key.substring(space + 1).trim();
            }
            List<Entry> found = new ArrayList<>();
            for (Entry entry : all) {
                if (mod != null && !entry.id().getNamespace().startsWith(mod)) continue;
                if (!rest.isEmpty() && !entry.search().contains(rest)) continue;
                found.add(entry);
            }
            return List.copyOf(found);
        });
    }

    /** Catalog entry of an ID, or null for an item that is no longer registered. */
    public static Entry byId(ResourceLocation id) {
        all();
        return index.get(id);
    }

    private ItemCatalog() { }
}
