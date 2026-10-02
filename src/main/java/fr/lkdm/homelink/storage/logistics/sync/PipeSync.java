package fr.lkdm.homelink.storage.logistics.sync;

import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.logistics.transit.TransitPacket;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends pictures of cargo to the players tracking the chunks they cross. Messages are sent on
 * departure, route change, pause, resume and end only; clients interpolate in between. Starts are
 * budgeted per tick (a skipped picture changes nothing on the server); ends are always sent.
 */
public final class PipeSync {
    private static final int STARTS_PER_TICK = 64;
    private static final int SNAPSHOT_PER_CHUNK = 32;
    private final ServerLevel level;
    private int budget = STARTS_PER_TICK;
    private int snapshotCursor;

    public PipeSync(ServerLevel level) { this.level = level; }

    public void tick(Collection<TransitPacket> packets) {
        budget = STARTS_PER_TICK;
        // Leased snapshots also cover walking into range, revocations and long/paused journeys.
        if (level.getGameTime() % 20 == 0) {
            var live = new java.util.HashSet<java.util.UUID>();
            var ordered = List.copyOf(packets);
            for (int i = 0; i < ordered.size(); i++) {
                TransitPacket packet = ordered.get(Math.floorMod(snapshotCursor + i, ordered.size()));
                live.add(packet.id);
                if (packet.position() != null) start(packet, packet.state != TransitPacket.State.ACTIVE
                        || packet.reason != fr.lkdm.homelink.storage.logistics.network.PipeStatus.TRANSFERRING);
            }
            snapshotCursor = ordered.isEmpty() ? 0 : Math.floorMod(snapshotCursor + STARTS_PER_TICK, ordered.size());
            for (var id : java.util.List.copyOf(recipients.keySet())) if (!live.contains(id)) forget(id, false);
        }
    }

    private final java.util.Map<java.util.UUID, Set<ServerPlayer>> recipients = new java.util.HashMap<>();

    private boolean allowed(ServerPlayer player, TransitPacket packet) {
        if (packet.autonomousOwner != null) return player.level()==level && !player.isSpectator()
                && player.getUUID().equals(packet.autonomousOwner) && packet.position()!=null
                && player.distanceToSqr(packet.position().getCenter()) <= 64*64;
        var controller = fr.lkdm.homelink.storage.logistics.network.PipeNetworkManager.controllerAt(level, packet.controller, packet.controllerPos);
        return controller != null && player.level() == level && !player.isSpectator()
                && packet.position() != null && player.distanceToSqr(packet.position().getCenter()) <= 64 * 64
                && controller.permission(player, fr.lkdm.homecore.api.security.Permission.VIEW);
    }

    private List<BlockPos> visible(ServerPlayer player, TransitPacket packet) {
        java.util.ArrayList<BlockPos> result = new java.util.ArrayList<>();
        for (BlockPos pos : remaining(packet)) {
            if (result.size() >= 8 || !level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos), false).contains(player)) break;
            result.add(pos);
        }
        return result;
    }

    private static List<BlockPos> remaining(TransitPacket packet) {
        List<BlockPos> route = packet.route;
        int end = Math.min(route.size(), packet.index + PipePayloads.MAX_VISUAL_ROUTE);
        return route.subList(packet.index, end);
    }

    private Set<ServerPlayer> watchers(List<BlockPos> route) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos pos : route) chunks.add(new ChunkPos(pos));
        Set<ServerPlayer> players = new LinkedHashSet<>();
        for (ChunkPos chunk : chunks) players.addAll(level.getChunkSource().chunkMap.getPlayers(chunk, false));
        return players;
    }

    private void send(Set<ServerPlayer> players, CustomPacketPayload payload) {
        for (ServerPlayer player : players) PacketDistributor.sendToPlayer(player, payload);
    }

    /** One-item picture; heavy component data (book pages, container contents) is not sent. */
    public static ItemStack picture(ServerLevel level, ItemStack stack) {
        ItemStack picture = stack.copyWithCount(1);
        int limit = Math.min(2048, StorageConfig.SPEC.isLoaded() ? StorageConfig.MAX_COMPONENT_BYTES.get() : 2048);
        if (picture.save(level.registryAccess()).sizeInBytes() <= limit) return picture;
        ItemStack plain = new ItemStack(stack.getItem());
        if (stack.hasFoil()) plain.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return plain;
    }

    private PipePayloads.TransitStart startPayload(TransitPacket packet, boolean paused, List<BlockPos> route) {
        int ticks = StorageConfig.pipe(StorageConfig.PIPE_TRAVEL_TICKS);
        var exit = packet.destinationSide;
        int next = packet.index + route.size();
        if (next < packet.route.size()) exit = fr.lkdm.homelink.storage.logistics.network.PipeGraph.side(route.getLast(), packet.route.get(next));
        return new PipePayloads.TransitStart(packet.id, picture(level, packet.stack()), List.copyOf(route),
                (byte) packet.entry.get3DDataValue(), (byte) exit.get3DDataValue(), 0, packet.progress, ticks, paused);
    }

    public void start(TransitPacket packet, boolean paused) {
        if (packet.position() == null) return;
        Set<ServerPlayer> next = new LinkedHashSet<>();
        for (ServerPlayer player : watchers(List.of(packet.position()))) {
            if (!allowed(player, packet)) continue;
            List<BlockPos> route = visible(player, packet);
            if (route.isEmpty() || budget <= 0) continue;
            budget--;
            PacketDistributor.sendToPlayer(player, startPayload(packet, paused, route));
            next.add(player);
        }
        Set<ServerPlayer> previous = recipients.put(packet.id, next);
        if (previous != null) {
            previous.removeAll(next);
            send(previous, new PipePayloads.TransitRemove(packet.id, false));
        }
    }

    /** Pause, resume or correction: the whole remaining picture is sent again, so clients never drift. */
    public void update(TransitPacket packet, boolean paused) {
        if (packet.position() == null) return;
        start(packet, paused);
    }

    /** Ends the picture for every player who may have received it along the whole route. */
    public void remove(TransitPacket packet, boolean delivered) {
        forget(packet.id, delivered);
    }

    private void forget(java.util.UUID id, boolean delivered) {
        Set<ServerPlayer> previous = recipients.remove(id);
        if (previous != null) send(previous, new PipePayloads.TransitRemove(id, delivered));
    }

    /** A player just received a chunk: pictures of the cargo crossing it. */
    public void watched(ServerPlayer player, ChunkPos chunk, Collection<TransitPacket> packets) {
        int sent = 0;
        for (TransitPacket packet : packets) {
            if (packet.position() == null || sent >= SNAPSHOT_PER_CHUNK || budget <= 0 || !allowed(player, packet)) continue;
            boolean crosses = false;
            for (BlockPos pos : remaining(packet)) if (pos.getX() >> 4 == chunk.x && pos.getZ() >> 4 == chunk.z) { crosses = true; break; }
            if (!crosses) continue;
            var route = visible(player, packet);
            if (route.isEmpty()) continue;
            PacketDistributor.sendToPlayer(player, startPayload(packet, packet.state != TransitPacket.State.ACTIVE
                    || packet.reason != fr.lkdm.homelink.storage.logistics.network.PipeStatus.TRANSFERRING, route));
            recipients.computeIfAbsent(packet.id, ignored -> new LinkedHashSet<>()).add(player);
            budget--;
            sent++;
        }
    }
}
