package fr.lkdm.homelink.storage.logistics.transit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

/**
 * Item handler operations of the pipe transport, kept free of world state so their
 * conservation rules can be tested on plain handlers.
 *
 * <p>A simulation is never a reservation: another player, a hopper or a Deposit may change
 * the container afterwards. Only the real result of an operation decides what the cargo
 * holds. Anything a handler hands back, even malformed, is kept rather than discarded.</p>
 */
public final class TransferEngine {
    /** A handler returned a stack that breaks the contract; the stack is kept in {@link #stack()}. */
    public static final class Anomaly extends RuntimeException {
        private final transient ItemStack stack;
        public Anomaly(String message, ItemStack stack) { super(message); this.stack = stack; }
        public ItemStack stack() { return stack; }
    }

    public static boolean sameVariant(ItemStack first, ItemStack second) {
        return ItemStack.isSameItemSameComponents(first, second);
    }

    /**
     * Objects the destination may be expected to take, never more than the handler's own
     * simulation. Cargo of the same circuit already heading there is deducted: objects of the
     * same variant from the free room, other variants one empty slot each.
     */
    public static int room(IItemHandler destination, ItemStack offer, Collection<ItemStack> heading) {
        if (offer.isEmpty()) return 0;
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(destination, offer.copy(), true);
        if (!remainder.isEmpty() && (!sameVariant(offer, remainder) || remainder.getCount() > offer.getCount())) return 0;
        int simulated = offer.getCount() - remainder.getCount();
        if (simulated <= 0 || heading.isEmpty()) return Math.max(0, simulated);
        long same = 0;
        List<Integer> empty = new ArrayList<>();
        for (int slot = 0; slot < destination.getSlots(); slot++) {
            ItemStack present = destination.getStackInSlot(slot);
            ItemStack probe = offer.copyWithCount(offer.getMaxStackSize());
            ItemStack rest = destination.insertItem(slot, probe.copy(), true);
            if (!rest.isEmpty() && (!sameVariant(rest, probe) || rest.getCount() > probe.getCount())) return 0;
            int accepted = probe.getCount() - rest.getCount();
            if (present.isEmpty()) { if (accepted > 0) empty.add(accepted); }
            else if (sameVariant(present, offer)) same += accepted;
        }
        long reservedSame = 0;
        int otherSlots = 0;
        for (ItemStack cargo : heading) {
            if (cargo.isEmpty()) continue;
            if (sameVariant(cargo, offer)) reservedSame += cargo.getCount();
            else otherSlots += cargo.getCount(); // A restricted handler may accept only one object per slot.
        }
        // Assume other variants take the largest empty slots: an under-estimate, never an over-promise.
        empty.sort(Comparator.reverseOrder());
        long free = same - reservedSame;
        for (int i = otherSlots; i < empty.size(); i++) free += empty.get(i);
        return (int) Math.max(0, Math.min(simulated, free));
    }

    /**
     * Extracts for real. The result is the exact stack returned by the handler; a stack of
     * another variant or above the request is still returned inside an {@link Anomaly}.
     */
    public static ItemStack extract(IItemHandler source, int slot, int amount, ItemStack expected) {
        ItemStack extracted = source.extractItem(slot, amount, false);
        if (extracted.isEmpty()) return ItemStack.EMPTY;
        if (!sameVariant(extracted, expected) || extracted.getCount() > amount)
            throw new Anomaly("Extraction returned " + extracted + " instead of at most " + amount + " " + expected, extracted);
        return extracted;
    }

    /**
     * Inserts the whole cargo for real and returns what it still holds afterwards. Only the
     * accepted part leaves the cargo; an invalid remainder is kept inside an {@link Anomaly}.
     */
    public static ItemStack insert(IItemHandler destination, ItemStack carried) {
        if (carried.isEmpty()) return ItemStack.EMPTY;
        ItemStack remainder = ItemHandlerHelper.insertItemStacked(destination, carried.copy(), false);
        if (!remainder.isEmpty() && (!sameVariant(carried, remainder) || remainder.getCount() > carried.getCount()))
            throw new Anomaly("Insertion returned " + remainder + " for " + carried, remainder);
        return remainder;
    }

    private TransferEngine() { }
}
