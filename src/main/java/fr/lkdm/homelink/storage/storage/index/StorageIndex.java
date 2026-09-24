package fr.lkdm.homelink.storage.storage.index;

import fr.lkdm.homelink.storage.config.StorageConfig;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** Server-thread-owned index. Contains no world, block entity or inventory handler references. */
public final class StorageIndex {
    private record ItemVariant(Item item, DataComponentPatch components) {
        static ItemVariant of(ItemStack stack) {
            return new ItemVariant(stack.getItem(), stack.getComponentsPatch());
        }
    }

    private record Snapshot(int slots, int occupied, Map<ItemVariant, Long> counts) {
        boolean full() {
            return slots > 0 && occupied == slots;
        }
    }

    /** A live read-only view. The display prototype must not be mutated by consumers. */
    public static final class Entry {
        private final long id;
        private final ItemStack display;
        private final Map<BlockPos, Long> locations = new HashMap<>();
        private final Map<BlockPos, Long> locationsView = Collections.unmodifiableMap(locations);
        private long total;
        private long revision;

        private Entry(long id, ItemStack source) {
            this.id = id;
            this.display = source.copyWithCount(1);
        }

        public long id() { return id; }

        /** Count-one prototype owned by the index; callers must treat it as immutable. */
        public ItemStack display() { return display; }

        public long total() { return total; }
        public long revision() { return revision; }

        public Map<BlockPos, Long> locations() { return locationsView; }
    }

    private final Map<BlockPos, Snapshot> inventories = new HashMap<>();
    private final Map<ItemVariant, Entry> variants = new LinkedHashMap<>();
    private final Collection<Entry> entriesView = Collections.unmodifiableCollection(variants.values());
    private final Map<Item, Integer> variantsPerItem = new HashMap<>();
    private long nextEntryId = 1;
    private long revision;
    private long totalItems;
    private int totalSlots;
    private int occupiedSlots;
    private int fullInventories;

    /** Scans one selected inventory; applies only its changed contributions to the central index. */
    public void update(BlockPos identity, IItemHandler handler) {
        BlockPos position = identity.immutable();
        int slots = handler.getSlots();
        if (slots < 0 || slots > StorageConfig.maxSlots()) {
            throw new IllegalArgumentException("Inventory slot limit exceeded");
        }
        int occupied = 0;
        Map<ItemVariant, Long> counts = new HashMap<>();
        Map<ItemVariant, ItemStack> newPrototypes = new HashMap<>();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            occupied++;
            ItemVariant key = ItemVariant.of(stack);
            counts.merge(key, (long) stack.getCount(), Long::sum);
            if (!variants.containsKey(key)) {
                newPrototypes.putIfAbsent(key, stack);
            }
        }
        Snapshot updated = new Snapshot(slots, occupied, Map.copyOf(counts));
        Snapshot previous = inventories.get(position);
        int projectedVariants = variants.size();
        if (previous != null) {
            for (ItemVariant key : previous.counts().keySet()) {
                if (!counts.containsKey(key) && variants.get(key).locations.size() == 1) projectedVariants--;
            }
        }
        for (ItemVariant key : counts.keySet()) if (!variants.containsKey(key)) projectedVariants++;
        if (projectedVariants > StorageConfig.maxVariants()) {
            throw new IllegalArgumentException("Index variant limit exceeded");
        }
        if (updated.equals(previous)) {
            return;
        }

        if (previous != null) {
            for (Map.Entry<ItemVariant, Long> old : previous.counts().entrySet()) {
                long currentCount = counts.getOrDefault(old.getKey(), 0L);
                if (currentCount != old.getValue()) {
                    changeContribution(old.getKey(), position, currentCount - old.getValue(), currentCount, null);
                }
            }
        }
        for (Map.Entry<ItemVariant, Long> current : counts.entrySet()) {
            if (previous == null || !previous.counts().containsKey(current.getKey())) {
                changeContribution(current.getKey(), position, current.getValue(), current.getValue(),
                        newPrototypes.get(current.getKey()));
            }
        }
        totalSlots += slots - (previous == null ? 0 : previous.slots());
        occupiedSlots += occupied - (previous == null ? 0 : previous.occupied());
        fullInventories += (updated.full() ? 1 : 0) - (previous != null && previous.full() ? 1 : 0);
        inventories.put(position, updated);
        revision++;
    }

    private void changeContribution(ItemVariant key, BlockPos position, long delta, long inventoryCount,
                                    ItemStack prototype) {
        Entry entry = variants.get(key);
        if (entry == null) {
            if (prototype == null) {
                throw new IllegalStateException("Missing prototype for a new variant");
            }
            entry = new Entry(nextEntryId++, prototype);
            variants.put(key, entry);
            variantsPerItem.merge(key.item(), 1, Integer::sum);
        }
        entry.total += delta;
        entry.revision++;
        totalItems += delta;
        if (inventoryCount == 0) {
            entry.locations.remove(position);
        } else {
            entry.locations.put(position, inventoryCount);
        }
        if (entry.total == 0) {
            variants.remove(key);
            variantsPerItem.compute(key.item(), (item, count) -> count == 1 ? null : count - 1);
        }
    }

    /** Removes unavailable inventories; stale counts never appear as available stock. */
    public void retain(Set<BlockPos> onlineIdentities) {
        for (BlockPos position : new HashSet<>(inventories.keySet())) {
            if (!onlineIdentities.contains(position)) {
                remove(position);
            }
        }
    }

    public void remove(BlockPos identity) {
        Snapshot previous = inventories.remove(identity);
        if (previous == null) {
            return;
        }
        previous.counts().forEach((key, count) -> changeContribution(key, identity, -count, 0, null));
        totalSlots -= previous.slots();
        occupiedSlots -= previous.occupied();
        fullInventories -= previous.full() ? 1 : 0;
        revision++;
    }

    public long revision() { return revision; }
    /** Uses exactly the identity used when indexing, including the component patch. */
    public Entry find(ItemStack stack) { return stack.isEmpty() ? null : variants.get(ItemVariant.of(stack)); }
    public static boolean sameVariant(ItemStack first, ItemStack second) {
        return !first.isEmpty() && !second.isEmpty() && ItemVariant.of(first).equals(ItemVariant.of(second));
    }
    public long totalItems() { return totalItems; }
    public int occupiedSlots() { return occupiedSlots; }
    public int totalSlots() { return totalSlots; }
    public int inventoryCount() { return inventories.size(); }
    public int fullInventories() { return fullInventories; }
    public int uniqueItems() { return variantsPerItem.size(); }
    public int variants() { return variants.size(); }
    public Collection<Entry> entries() { return entriesView; }
}
