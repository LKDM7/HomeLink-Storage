package fr.lkdm.homelink.storage.network;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Data-only client state for the coverage display of a Link or Repeater: the chunk it
 * covers and the chunks of the other nodes of its network. No world reference is kept.
 */
public final class CoverageState {
    /** Display duration after a right-click on a node. */
    public static final int DURATION_TICKS = 30 * 20;

    /** One covered chunk column. {@code focus} marks the node that was clicked. */
    public record Chunk(int x, int z, BlockPos node, boolean active, boolean repeater, boolean focus) {}
    public record View(String dimension, List<Chunk> chunks, long expiresAtNanos) {}

    private static View view;
    /** Node hidden locally by a second click: its imminent server answer must not show it again. */
    private static BlockPos suppressed;
    private static long suppressedUntilNanos;

    /** @return false when the zone was just hidden by the player, so this answer is ignored */
    public static boolean accept(String dimension, List<Chunk> chunks) {
        BlockPos focus = chunks.stream().filter(Chunk::focus).map(Chunk::node).findFirst().orElse(null);
        if (suppressed != null && System.nanoTime() < suppressedUntilNanos && suppressed.equals(focus)) {
            suppressed = null;
            return false;
        }
        view = new View(dimension, List.copyOf(chunks), System.nanoTime() + DURATION_TICKS * 50_000_000L);
        return true;
    }

    /**
     * Client-side right-click on a Link or Repeater. If that node's zone is on screen it is
     * hidden immediately, without waiting for the server; otherwise the server answer shows it.
     */
    public static void clickedLocally(BlockPos node) {
        View current = current();
        if (current == null || current.chunks().stream().noneMatch(chunk -> chunk.focus() && chunk.node().equals(node))) return;
        view = null;
        suppressed = node.immutable();
        suppressedUntilNanos = System.nanoTime() + 2_000_000_000L;
    }

    public static boolean showing(BlockPos node) {
        View current = current();
        return current != null && current.chunks().stream().anyMatch(chunk -> chunk.focus() && chunk.node().equals(node));
    }

    public static View current() {
        if (view != null && System.nanoTime() >= view.expiresAtNanos()) view = null;
        return view;
    }

    public static void clear() { view = null; }
    private CoverageState() {}
}
