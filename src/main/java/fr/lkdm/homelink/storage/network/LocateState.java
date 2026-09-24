package fr.lkdm.homelink.storage.network;

import net.minecraft.core.BlockPos;

/** Data-only client marker state. No world reference is retained after disconnect. */
public final class LocateState {
    public record Target(BlockPos position, String dimension, long expiresAtNanos) {}
    private static Target target;
    public static void accept(BlockPos position, String dimension, int durationTicks) {
        target = new Target(position.immutable(), dimension, System.nanoTime() + Math.clamp(durationTicks, 1, 1200) * 50_000_000L);
    }
    public static Target current() {
        if (target != null && System.nanoTime() >= target.expiresAtNanos()) target = null;
        return target;
    }
    public static void clear() { target = null; }
    private LocateState() {}
}
