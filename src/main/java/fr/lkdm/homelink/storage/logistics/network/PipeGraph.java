package fr.lkdm.homelink.storage.logistics.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/** Shortest routes over the pipe segments of one circuit. Pure: no world access. */
public final class PipeGraph {
    /** One bounded breadth-first traversal serves all destinations for a given departure segment. */
    public record Tree(BlockPos root, Map<BlockPos, BlockPos> previous, Map<BlockPos, Integer> depth) {
        public @Nullable List<BlockPos> to(BlockPos target) {
            return depth.containsKey(target) ? unwind(previous, root, target) : null;
        }
    }
    public static Tree tree(Set<BlockPos> pipes, BlockPos from, int maximum) {
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        if (!pipes.contains(from) || maximum < 1) return new Tree(from,previous,depth);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(from); depth.put(from,1);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst(); int length = depth.get(current);
            if (length >= maximum) continue;
            for (Direction side : Direction.values()) {
                BlockPos next = current.relative(side);
                if (!pipes.contains(next) || depth.containsKey(next)) continue;
                previous.put(next,current); depth.put(next,length+1); queue.addLast(next);
            }
        }
        return new Tree(from,previous,depth);
    }
    /**
     * Breadth-first shortest route, visiting sides in {@link Direction} order so equal circuits
     * always give the same route. Each segment appears once, so no route loops.
     *
     * @param pipes segments of the circuit
     * @param from first segment (touching the source)
     * @param to last segment (touching the destination)
     * @param maximum most segments allowed in the route
     * @return segments from {@code from} to {@code to} inclusive, or null if unreachable or too long
     */
    public static @Nullable List<BlockPos> route(Set<BlockPos> pipes, BlockPos from, BlockPos to, int maximum) {
        if (!pipes.contains(from) || !pipes.contains(to) || maximum < 1) return null;
        if (from.equals(to)) return List.of(from);
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(from);
        depth.put(from, 1);
        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            int length = depth.get(current);
            if (length >= maximum) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (!pipes.contains(next) || depth.containsKey(next)) continue;
                previous.put(next, current);
                depth.put(next, length + 1);
                if (next.equals(to)) return unwind(previous, from, to);
                queue.addLast(next);
            }
        }
        return null;
    }

    private static List<BlockPos> unwind(Map<BlockPos, BlockPos> previous, BlockPos from, BlockPos to) {
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos at = to; at != null; at = at.equals(from) ? null : previous.get(at)) path.add(at);
        Collections.reverse(path);
        return List.copyOf(path);
    }

    /** Whether consecutive positions are adjacent pipes of the circuit (a still valid route). */
    public static boolean valid(Set<BlockPos> pipes, List<BlockPos> route, int from) {
        for (int i = Math.max(0, from); i < route.size(); i++) {
            if (!pipes.contains(route.get(i))) return false;
            if (i > from && route.get(i).distManhattan(route.get(i - 1)) != 1) return false;
        }
        return true;
    }

    /** Side of {@code from} facing the adjacent position {@code to}. */
    public static @Nullable Direction side(BlockPos from, BlockPos to) {
        for (Direction direction : Direction.values()) if (from.relative(direction).equals(to)) return direction;
        return null;
    }

    private PipeGraph() { }
}
