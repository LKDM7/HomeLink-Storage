package fr.lkdm.homelink.storage.logistics.network;

import fr.lkdm.homelink.storage.logistics.filter.FlowMode;
import fr.lkdm.homelink.storage.logistics.filter.PipeFaceConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * One physical circuit: pipe segments joined side to side, with what they touch. The Controller
 * is never a segment, so two branches meeting only through a Controller are two circuits.
 * A component is immutable apart from its resolved container faces and cached routes; any change
 * of its segments replaces it with a new component and a new revision.
 */
public final class PipeComponent {
    /** A container face of this circuit, with its live settings and the verdict of the last check. */
    public static final class Face {
        public final PipeEndpoint endpoint;
        public final PipeFaceConfig config;
        public PipeStatus status = PipeStatus.NEEDS_CONFIGURATION;
        /** Armed for the managing Controller, by a player who still may, on a container it may reach. */
        public boolean authorized;
        /** Authorized, allowed by the port in its direction, and not part of a source/destination loop. */
        public boolean usable;

        Face(PipeEndpoint endpoint, PipeFaceConfig config) {
            this.endpoint = endpoint;
            this.config = config;
        }

        public boolean source() { return usable && config.mode() == FlowMode.EXTRACT; }
        public boolean destination() { return usable && config.mode() == FlowMode.INSERT; }
    }

    public final UUID id = UUID.randomUUID();
    public final long revision;
    public final Set<BlockPos> pipes;
    /** Controllers adjacent to at least one segment, by UUID. More than one is a conflict. */
    public final Map<UUID, BlockPos> controllers;
    /** A segment borders an unloaded chunk: a second Controller could hide behind it. */
    public final boolean incomplete;
    public final boolean tooLarge;
    /** Pipe sides whose neighbour may be a container, as (pipe, side). */
    public final List<Map.Entry<BlockPos, Direction>> candidates;
    final Map<PipeEndpoint.Key, Face> faces = new LinkedHashMap<>();
    private final Map<RouteKey, PipeGraph.Tree> routes = new LinkedHashMap<>();
    boolean valid = true;
    boolean facesDirty = true;
    long facesCheckedAt = Long.MIN_VALUE;
    boolean tooManyFaces;
    PipeStatus status = PipeStatus.UPDATING;
    PipeStatus.Lamp lamp;

    PipeComponent(long revision, Set<BlockPos> pipes, Map<UUID, BlockPos> controllers, boolean incomplete, boolean tooLarge,
                  List<Map.Entry<BlockPos, Direction>> candidates) {
        this.revision = revision;
        this.pipes = Set.copyOf(pipes);
        this.controllers = Map.copyOf(controllers);
        this.incomplete = incomplete;
        this.tooLarge = tooLarge;
        this.candidates = List.copyOf(candidates);
    }

    public boolean valid() { return valid; }
    public PipeStatus status() { return status; }
    public List<Face> faces() { return new ArrayList<>(faces.values()); }
    public @Nullable Face face(BlockPos pipe, Direction side) { return faces.get(new PipeEndpoint.Key(pipe, side)); }

    /** The single managing Controller, or null when there is none, several, or the topology is ambiguous. */
    public @Nullable UUID controller() {
        return controllers.size() == 1 && !incomplete && !tooLarge ? controllers.keySet().iterator().next() : null;
    }

    /** Cached shortest route between two segments of this circuit. */
    public @Nullable List<BlockPos> route(BlockPos from, BlockPos to, int maximum) {
        RouteKey key = new RouteKey(from.asLong(), 0, maximum);
        PipeGraph.Tree tree = routes.get(key);
        if (tree == null) {
            tree = PipeGraph.tree(pipes, from, maximum);
            if (routes.size() >= 16) routes.remove(routes.keySet().iterator().next());
            routes.put(key, tree);
        }
        return tree.to(to);
    }

    private record RouteKey(long from, long to, int maximum) { }
}
