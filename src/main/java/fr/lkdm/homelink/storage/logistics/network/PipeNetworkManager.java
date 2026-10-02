package fr.lkdm.homelink.storage.logistics.network;

import com.mojang.logging.LogUtils;
import fr.lkdm.homecore.api.device.DashboardDevice;
import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.logistics.filter.FlowMode;
import fr.lkdm.homelink.storage.logistics.pipe.PipeConnection;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import fr.lkdm.homelink.storage.logistics.sync.PipeSync;
import fr.lkdm.homelink.storage.logistics.transit.TransferEngine;
import fr.lkdm.homelink.storage.logistics.transit.TransitLedger;
import fr.lkdm.homelink.storage.logistics.transit.TransitPacket;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side coordination of the Storage Pipes of one dimension.
 *
 * <p>Circuits are rebuilt incrementally after a pipe is placed, broken, loaded or unloaded,
 * within a per-tick work budget. Each circuit is managed by the single Controller touching it;
 * none enables standalone owner-authorized transport, several mean a conflict, and an unloaded border suspends the circuit
 * because a second Controller could hide behind it. Nothing here loads a chunk.</p>
 *
 * <p>Cargo is owned by the {@link TransitLedger}. A departure needs a reachable destination
 * accepting the object under both filters, room in the Controller's queue and its HE; only then
 * is the source debited, and the cargo holds exactly the stack the source returned. Arrival
 * removes from the cargo only what the destination actually accepted.</p>
 */
@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID)
public final class PipeNetworkManager {
    private static final Map<ServerLevel, PipeNetworkManager> MANAGERS = new WeakHashMap<>();
    private static final int FACE_REFRESH_TICKS = 200;
    private static final int STATUS_TICKS = 20;
    private static final int QUARANTINE_TICKS = 1200;

    private final ServerLevel level;
    private final PipeSync sync;
    private final Map<BlockPos, PipeComponent> byPipe = new HashMap<>();
    private final Set<PipeComponent> components = new LinkedHashSet<>();
    private final LinkedHashSet<BlockPos> seeds = new LinkedHashSet<>();
    private @Nullable Build build;
    private long revisions;
    private final Map<UUID, ControllerState> states = new HashMap<>();
    private final Map<PipeEndpoint.Key, Long> quarantine = new HashMap<>();
    private boolean busy;

    private PipeNetworkManager(ServerLevel level) {
        this.level = level;
        this.sync = new PipeSync(level);
    }

    public static PipeNetworkManager get(ServerLevel level) {
        synchronized (MANAGERS) { return MANAGERS.computeIfAbsent(level, PipeNetworkManager::new); }
    }

    public TransitLedger ledger() { return TransitLedger.get(level); }
    public PipeSync sync() { return sync; }

    @SubscribeEvent static void levelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel server) get(server).tick();
    }

    @SubscribeEvent static void levelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel server) synchronized (MANAGERS) { MANAGERS.remove(server); }
    }

    @SubscribeEvent static void stopped(ServerStoppedEvent event) {
        synchronized (MANAGERS) { MANAGERS.clear(); }
    }

    @SubscribeEvent static void chunkSent(ChunkWatchEvent.Sent event) {
        PipeNetworkManager manager = get(event.getLevel());
        manager.sync.watched(event.getPlayer(), event.getPos(), manager.ledger().packets());
    }

    /** Loaded Controller with that identity at that position, or null. Never loads the chunk. */
    public static @Nullable StorageBlockEntity controllerAt(ServerLevel level, UUID id, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        return LoadedBlocks.entity(level, pos) instanceof StorageBlockEntity storage && storage.isController()
                && !storage.isRemoved() && storage.id().equals(id) ? storage : null;
    }

    public @Nullable PipeComponent component(BlockPos pipe) {
        PipeComponent component = byPipe.get(pipe);
        return component != null && component.valid ? component : null;
    }

    public @Nullable StorageBlockEntity controller(PipeComponent component) {
        UUID id = component.controller();
        return id == null ? null : controllerAt(level, id, component.controllers.get(id));
    }

    // ---------------------------------------------------------------- topology events

    public void pipeAdded(BlockPos pos) {
        touched(pos);
        invalidate(byPipe.get(pos));
        for (Direction direction : Direction.values()) invalidate(byPipe.get(pos.relative(direction)));
        seeds.add(pos.immutable());
    }

    public void pipeLoaded(BlockPos pos) { pipeAdded(pos); }

    public void pipeRemoved(BlockPos pos) {
        recoverAt(pos);
        touched(pos);
        invalidate(byPipe.get(pos));
        seeds.remove(pos);
    }

    public void pipeUnloaded(BlockPos pos) {
        touched(pos);
        invalidate(byPipe.get(pos));
        seeds.remove(pos);
    }

    /** The pipe's visible connections changed: a Controller or pipe may have appeared or gone. */
    public void pipeChanged(BlockPos pos) {
        touched(pos);
        invalidate(byPipe.get(pos));
        seeds.add(pos.immutable());
    }

    /** A neighbour or one of its capabilities changed: only the container faces are checked again. */
    public void facesChanged(BlockPos pos) {
        PipeComponent component = byPipe.get(pos);
        if (component != null) component.facesDirty = true;
    }

    public void capabilityChanged(BlockPos pos) { facesChanged(pos); }

    private void touched(BlockPos pos) {
        if (build == null) return;
        boolean near = build.visited.contains(pos);
        for (Direction direction : Direction.values()) near |= build.visited.contains(pos.relative(direction));
        if (near) { seeds.addAll(build.visited); build = null; }
    }

    private void invalidate(@Nullable PipeComponent component) {
        if (component == null || !component.valid) return;
        component.valid = false;
        components.remove(component);
        for (BlockPos pipe : component.pipes) if (byPipe.get(pipe) == component) byPipe.remove(pipe);
        seeds.addAll(component.pipes);
    }

    // ---------------------------------------------------------------- tick

    public void tick() {
        if (busy) return;
        busy = true;
        try { tickOwned(); } finally { busy = false; }
    }

    private void tickOwned() {
        long now = level.getGameTime();
        states.values().forEach(state -> { state.slotBudget = StorageConfig.pipe(StorageConfig.PIPE_SLOT_CHECKS); state.routeBudget = 8; });
        sync.tick(ledger().packets());
        rebuild(StorageConfig.pipe(StorageConfig.PIPE_GRAPH_BUDGET));
        TransitLedger ledger = ledger();
        if (components.isEmpty() && ledger.packets().isEmpty()) return;
        for (PipeComponent component : List.copyOf(components))
            if (component.facesDirty || now - component.facesCheckedAt >= FACE_REFRESH_TICKS)
                refreshFaces(component, now);
        busy = true;
        try {
            for (TransitPacket packet : List.copyOf(ledger.packets())) if (packet.state != TransitPacket.State.RECOVERY) step(packet, now);
            Map<UUID, List<PipeComponent>> byController = new LinkedHashMap<>();
            for (PipeComponent component : components) {
                UUID id = component.controller();
                if (id != null) byController.computeIfAbsent(id, ignored -> new ArrayList<>()).add(component);
            }
            byController.forEach((id, owned) -> {
                StorageBlockEntity controller = controllerAt(level, id, owned.get(0).controllers.get(id));
                if (controller != null) dispatch(new PipeCoordinator(level, controller), owned, now);
            });
            Map<UUID, List<PipeComponent>> autonomous = new LinkedHashMap<>();
            for (PipeComponent component : components) {
                if (!component.controllers.isEmpty() || component.incomplete || component.tooLarge) continue;
                Set<UUID> owners = new HashSet<>();
                for (var face : component.faces.values()) if (face.source() && face.config.armedBy()!=null) owners.add(face.config.armedBy());
                for (UUID owner : owners) autonomous.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(component);
            }
            autonomous.forEach((owner, owned) -> dispatch(new PipeCoordinator(level, owner, owned.getFirst().pipes.iterator().next()), owned, now));
            if (now % STATUS_TICKS == 0) refreshStatus(byController, now);
            if (now % 100 == 0) resolveOrphans();
        } finally {
            busy = false;
        }
    }

    // ---------------------------------------------------------------- incremental circuit rebuild

    private final class Build {
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        final LinkedHashSet<BlockPos> visited = new LinkedHashSet<>();
        final Map<UUID, BlockPos> controllers = new LinkedHashMap<>();
        final List<Map.Entry<BlockPos, Direction>> candidates = new ArrayList<>();
        boolean incomplete, tooLarge;

        Build(BlockPos seed) { visited.add(seed); queue.add(seed); }

        /** Explores segments until the budget is spent; returns the unused budget. */
        int step(int budget) {
            int maximum = StorageConfig.pipe(StorageConfig.PIPE_MAX_NODES);
            while (budget > 0 && !queue.isEmpty()) {
                BlockPos pos = queue.removeFirst();
                budget--;
                BlockState state = LoadedBlocks.state(level, pos);
                for (Direction direction : Direction.values()) {
                    BlockPos next = pos.relative(direction);
                    if (visited.contains(next)) continue;
                    if (!level.isLoaded(next)) { incomplete = true; continue; }
                    BlockState neighbour = LoadedBlocks.state(level, next);
                    if (neighbour.getBlock() instanceof StoragePipeBlock) {
                        if (visited.size() >= maximum) { tooLarge = true; continue; }
                        visited.add(next.immutable());
                        queue.addLast(next.immutable());
                        continue;
                    }
                    BlockEntity entity = LoadedBlocks.entity(level, next);
                    if (entity instanceof StorageBlockEntity storage && storage.isController()) {
                        controllers.put(storage.id(), next.immutable());
                        continue;
                    }
                    if (StoragePipeBlock.side(state, direction) == PipeConnection.CONTAINER || entity != null)
                        candidates.add(new AbstractMap.SimpleImmutableEntry<>(pos.immutable(), direction));
                }
            }
            return budget;
        }
    }

    private void rebuild(int budget) {
        while (budget > 0) {
            if (build == null) {
                BlockPos seed = nextSeed();
                if (seed == null) return;
                build = new Build(seed);
            }
            budget = build.step(budget);
            if (build != null && build.queue.isEmpty()) {
                Build done = build;
                build = null;
                finish(done);
            }
        }
    }

    private @Nullable BlockPos nextSeed() {
        Iterator<BlockPos> iterator = seeds.iterator();
        while (iterator.hasNext()) {
            BlockPos seed = iterator.next();
            iterator.remove();
            if (component(seed) != null || !level.isLoaded(seed)) continue;
            if (LoadedBlocks.state(level, seed).getBlock() instanceof StoragePipeBlock) return seed;
        }
        return null;
    }

    private void finish(Build done) {
        PipeComponent component = new PipeComponent(++revisions, done.visited, done.controllers, done.incomplete, done.tooLarge, done.candidates);
        for (BlockPos pipe : done.visited) {
            PipeComponent old = byPipe.put(pipe, component);
            if (old != null && old != component) invalidate(old);
        }
        for (BlockPos pipe : done.visited) byPipe.put(pipe, component);
        components.add(component);
        seeds.removeAll(done.visited);
        long now = level.getGameTime();
        refreshFaces(component, now);
        component.status = status(component, now);
        pushLamp(component);
    }

    // ---------------------------------------------------------------- container faces

    private void refreshFaces(PipeComponent component, long now) {
        component.faces.clear();
        component.tooManyFaces = false;
        component.facesDirty = false;
        component.facesCheckedAt = now;
        StorageBlockEntity controller = controller(component);
        Map<BlockPos, DashboardDevice> devices = controller == null ? Map.of() : PipeAccess.devices(level);
        int maximum = StorageConfig.pipe(StorageConfig.PIPE_MAX_ENDPOINTS);
        for (var candidate : component.candidates) {
            PipeEndpoint.Live live = PipeEndpoint.resolve(level, candidate.getKey(), candidate.getValue());
            if (live == null) continue;
            if (component.faces.size() >= maximum) { component.tooManyFaces = true; break; }
            StoragePipeBlockEntity pipe = (StoragePipeBlockEntity) LoadedBlocks.entity(level, candidate.getKey());
            var config = pipe.config(candidate.getValue());
            if (config == null) config = new fr.lkdm.homelink.storage.logistics.filter.PipeFaceConfig();
            // A different block behind an armed face means the container was replaced, even while unloaded.
            if (config.armed() && !config.target().isEmpty() && !config.target().equals(live.endpoint().block()) && config.disarm()) pipe.configChanged();
            PipeComponent.Face face = new PipeComponent.Face(live.endpoint(), config);
            evaluate(component, face, controller, devices, now);
            component.faces.put(live.endpoint().key(), face);
        }
        // One physical container as both source and destination of a circuit: its extraction is suspended.
        Set<BlockPos> sources = new HashSet<>(), destinations = new HashSet<>();
        for (PipeComponent.Face face : component.faces.values()) {
            if (face.source()) sources.add(face.endpoint.identity());
            if (face.destination()) destinations.add(face.endpoint.identity());
        }
        for (PipeComponent.Face face : component.faces.values())
            if (face.source() && destinations.contains(face.endpoint.identity())) { face.usable = false; face.status = PipeStatus.LOOP_CONFLICT; }
    }

    private void evaluate(PipeComponent component, PipeComponent.Face face, @Nullable StorageBlockEntity controller,
                          Map<BlockPos, DashboardDevice> devices, long now) {
        var config = face.config;
        face.usable = false;
        face.authorized = false;
        if (!config.armed()) { face.status = PipeStatus.NEEDS_CONFIGURATION; return; }
        if (controller == null && component.controllers.isEmpty() && !component.incomplete) {
            if (config.armedController()!=null) { face.status=PipeStatus.NEEDS_CONFIGURATION; return; }
            if (!PipeAccess.autonomousAllowed(level, face, config.armedBy())) { face.status=PipeStatus.ACCESS_DENIED; return; }
            face.authorized=true;
            face.usable=config.mode().allowedBy(face.endpoint.port()) && quarantine.getOrDefault(face.endpoint.key(),0L)<=now;
            face.status=face.usable?PipeStatus.IDLE:PipeStatus.PORT_REJECTED;
            return;
        }
        if (controller == null) { face.status = component.controllers.size() > 1 ? PipeStatus.CONTROLLER_CONFLICT
                : component.incomplete ? PipeStatus.CHUNK_UNLOADED : component.controllers.isEmpty() ? PipeStatus.NO_CONTROLLER : PipeStatus.CONTROLLER_OFFLINE; return; }
        UUID armedController = config.armedController();
        boolean sameContext = armedController == null ? config.armedBy().equals(controller.owner())
                : armedController.equals(controller.id()) && java.util.Objects.equals(config.armedNetwork(), controller.networkId());
        if (!sameContext) { face.status = PipeStatus.NEEDS_CONFIGURATION; return; }
        if (!PipeAccess.armedFor(level, config, controller) || !PipeAccess.targetAllowed(level, devices, face.endpoint.target(), controller)) {
            face.status = PipeStatus.ACCESS_DENIED; return;
        }
        Long until = quarantine.get(face.endpoint.key());
        if (until != null && until > now) { face.status = PipeStatus.PORT_REJECTED; return; }
        face.authorized = true;
        if (!config.mode().allowedBy(face.endpoint.port())) { face.status = PipeStatus.PORT_REJECTED; return; }
        face.usable = true;
        face.status = PipeStatus.IDLE;
    }

    private void quarantine(PipeComponent.Face face, RuntimeException failure) {
        var key = face.endpoint.key();
        if (quarantine.put(key, level.getGameTime() + QUARANTINE_TICKS) == null)
            LogUtils.getLogger().warn("Storage pipe container at {} misbehaved and is suspended for a minute: {}", face.endpoint.target(), failure.toString());
        face.usable = false;
        face.authorized = false;
        face.status = PipeStatus.PORT_REJECTED;
    }

    // ---------------------------------------------------------------- status and lamps

    /** Readable state of a circuit, from configuration problems down to transport. */
    public PipeStatus status(PipeComponent component, long now) {
        if (!component.valid) return PipeStatus.UPDATING;
        if (component.tooLarge || component.tooManyFaces) return PipeStatus.LIMIT_EXCEEDED;
        if (component.controllers.size() > 1) return PipeStatus.CONTROLLER_CONFLICT;
        if (component.controllers.isEmpty() && !component.incomplete) {
            if (!StorageConfig.pipesEnabled()) return PipeStatus.PAUSED;
            for(var packet:ledger().packets()) if(packet.autonomousOwner!=null && packet.position()!=null && component.pipes.contains(packet.position()))
                return packet.state==TransitPacket.State.BLOCKED?packet.reason:PipeStatus.TRANSFERRING;
            // Keep the autonomous status as informative as the Controller status. Previously
            // this branch reported IDLE whenever a face was configured, even after dispatch
            // had established that no destination on the physical route could accept the item.
            for (PipeComponent.Face face : component.faces.values()) {
                if (!face.source() || face.config.armedBy() == null) continue;
                ControllerState state = states.get(new PipeCoordinator(level, face.config.armedBy(), face.endpoint.pipe()).id());
                if (state != null && state.noRoute) return PipeStatus.NO_ROUTE;
            }
            return component.faces.values().stream().anyMatch(face -> face.usable)?PipeStatus.IDLE:PipeStatus.NEEDS_CONFIGURATION;
        }
        if (component.incomplete) return PipeStatus.CHUNK_UNLOADED;
        StorageBlockEntity controller = controller(component);
        if (controller == null) return PipeStatus.CONTROLLER_OFFLINE;
        if (controller.pipesPaused() || !StorageConfig.pipesEnabled()) return PipeStatus.PAUSED;
        if (!controller.powered()) return PipeStatus.NO_POWER;
        if (!controller.automationAvailable()) return PipeStatus.CONTROLLER_OFFLINE;
        boolean usable = false;
        for (PipeComponent.Face face : component.faces.values()) usable |= face.usable;
        boolean moving = false;
        PipeStatus blocked = null;
        for (TransitPacket packet : ledger().packets()) {
            BlockPos at = packet.position();
            if (at == null || !packet.controller.equals(controller.id()) || !component.pipes.contains(at)) continue;
            if (packet.state == TransitPacket.State.BLOCKED) blocked = packet.reason;
            else moving = true;
        }
        if (blocked != null) return blocked;
        if (moving) return PipeStatus.TRANSFERRING;
        if (!usable) return PipeStatus.NEEDS_CONFIGURATION;
        ControllerState state = states.get(controller.id());
        if (state != null && state.noPower) return PipeStatus.NO_POWER;
        if (state != null && state.noRoute) return PipeStatus.NO_ROUTE;
        return PipeStatus.IDLE;
    }

    private void refreshStatus(Map<UUID, List<PipeComponent>> byController, long now) {
        for (PipeComponent component : components) {
            component.status = status(component, now);
            pushLamp(component);
        }
        // Controllers size their HE buffer for departures only while they manage a circuit.
        byController.forEach((id, owned) -> {
            StorageBlockEntity controller = controllerAt(level, id, owned.get(0).controllers.get(id));
            if (controller == null) return;
            state(id).pos = controller.getBlockPos();
            controller.setPipeCircuits(owned.size());
        });
        states.forEach((id, state) -> {
            if (byController.containsKey(id) || state.pos == null) return;
            StorageBlockEntity controller = controllerAt(level, id, state.pos);
            if (controller != null) controller.setPipeCircuits(0);
        });
    }

    private void pushLamp(PipeComponent component) {
        PipeStatus.Lamp lamp = component.status.lamp();
        if (lamp == component.lamp) return;
        component.lamp = lamp;
        for (BlockPos pipe : component.pipes)
            if (level.isLoaded(pipe) && LoadedBlocks.entity(level, pipe) instanceof StoragePipeBlockEntity entity) entity.setLamp(lamp);
    }

    // ---------------------------------------------------------------- dispatch

    private static final class ControllerState {
        int sourceCursor;
        int destinationCursor;
        long nextDispatch;
        boolean noRoute;
        boolean noPower;
        @Nullable BlockPos pos;
        final Map<Long, Integer> slotCursor = new HashMap<>();
        final Map<PipeEndpoint.Key, Integer> destinationSlots = new HashMap<>();
        int slotBudget = StorageConfig.pipe(StorageConfig.PIPE_SLOT_CHECKS);
        int routeBudget = 8;
        final long[] delivered = new long[60];
        long second = Long.MIN_VALUE;

        void delivered(long now, int count) {
            roll(now);
            delivered[(int) Math.floorMod(now / 20, 60)] += count;
        }

        void roll(long now) {
            long current = now / 20;
            if (second == Long.MIN_VALUE || current - second >= 60) java.util.Arrays.fill(delivered, 0);
            else for (long s = second + 1; s <= current; s++) delivered[(int) Math.floorMod(s, 60)] = 0;
            second = current;
        }

        long perMinute(long now) {
            roll(now);
            long total = 0;
            for (long value : delivered) total += value;
            return total;
        }
    }

    private ControllerState state(UUID controller) { return states.computeIfAbsent(controller, ignored -> new ControllerState()); }

    private record Source(PipeComponent component, PipeComponent.Face face) { }
    private record Choice(PipeComponent.Face face, List<BlockPos> route, int room) { }
    private record Attempt(int checks, boolean departed, boolean noRoute, boolean noPower) { }

    private void dispatch(PipeCoordinator controller, List<PipeComponent> owned, long now) {
        ControllerState state = state(controller.id());
        if (now < state.nextDispatch) return;
        state.nextDispatch = now + StorageConfig.pipe(StorageConfig.PIPE_DISPATCH_INTERVAL);
        state.noPower = false;
        if (!StorageConfig.pipesEnabled() || controller.pipesPaused() || !controller.automationAvailable()) return;
        int maximum = StorageConfig.pipe(StorageConfig.PIPE_MAX_IN_FLIGHT);
        int inFlight = ledger().count(controller.id());
        // A full queue blocks new departures; it never discards cargo already held.
        if (inFlight >= maximum) return;
        List<Source> sources = new ArrayList<>();
        for (PipeComponent component : owned) {
            Set<BlockPos> seen = new HashSet<>();
            for (PipeComponent.Face face : component.faces.values())
                if (face.source() && seen.add(face.endpoint.identity())) sources.add(new Source(component, face));
        }
        if (sources.isEmpty()) { state.noRoute = false; return; }
        sources.sort(Comparator.comparing((Source source) -> source.face.endpoint.identity()).thenComparing(source -> source.face.endpoint.key()));
        int perRound = StorageConfig.pipe(StorageConfig.PIPE_DEPARTURES);
        int budget = StorageConfig.pipe(StorageConfig.PIPE_SLOT_CHECKS);
        int start = Math.floorMod(state.sourceCursor, sources.size());
        int departures = 0;
        boolean noRoute = false;
        for (int i = 0; i < sources.size() && departures < perRound && budget > 0 && inFlight < maximum; i++) {
            int index = (start + i) % sources.size();
            Attempt attempt;
            try { attempt = depart(controller, sources.get(index), budget, state, now); }
            catch (RuntimeException failure) {
                quarantine(sources.get(index).face, failure);
                attempt = new Attempt(1, false, false, false);
            }
            budget -= attempt.checks;
            if (attempt.noPower) { state.noPower = true; break; }
            noRoute |= attempt.noRoute;
            if (attempt.departed) { departures++; inFlight++; state.sourceCursor = index + 1; }
        }
        // Rotate even without a departure, so the last sources are never starved by the budget.
        if (departures == 0) state.sourceCursor = start + 1;
        state.noRoute = departures == 0 && noRoute;
    }

    private Attempt depart(PipeCoordinator controller, Source source, int budget, ControllerState state, long now) {
        PipeComponent.Face face = source.face;
        if (!source.component.valid || source.component.incomplete || source.component.tooManyFaces
                || !authorized(face, controller)) return new Attempt(1, false, false, false);
        PipeEndpoint.Live live = face.endpoint.live(level);
        if (live == null) { source.component.facesDirty = true; return new Attempt(1, false, false, false); }
        IItemHandler handler = live.handler();
        int slots = handler.getSlots();
        long identity = face.endpoint.identity().asLong();
        int cursor = state.slotCursor.getOrDefault(identity, 0);
        int cap = Math.min(64, StorageConfig.pipe(StorageConfig.PIPE_ITEMS_PER_TRANSFER));
        long cost = StorageConfig.pipe(StorageConfig.PIPE_ENERGY);
        int checks = 0;
        boolean noRoute = false;
        for (int k = 0; k < slots && checks < budget && state.slotBudget > 0; k++) {
            int slot = Math.floorMod(cursor + k, slots);
            checks++;
            state.slotBudget--;
            ItemStack present = handler.getStackInSlot(slot);
            if (present.isEmpty() || !face.config.filter().allows(present)) continue;
            int wanted = Math.min(cap, present.getMaxStackSize());
            ItemStack simulated = handler.extractItem(slot, wanted, true);
            if (simulated.isEmpty() || !TransferEngine.sameVariant(simulated, present) || simulated.getCount() > wanted) continue;
            // No destination: the object stays in its container. Never extract "to see later".
            Choice choice = choose(controller, source.component, face.endpoint.pipe(), simulated, face.endpoint.identity(), null);
            if (choice == null) { noRoute = true; continue; }
            if (!controller.canPayLogistics(cost)) return new Attempt(checks, false, false, true);
            PipeEndpoint.Live again = face.endpoint.live(level);
            if (again == null || slot >= again.handler().getSlots()
                    || !TransferEngine.sameVariant(again.handler().getStackInSlot(slot), simulated)) continue;
            if (!source.component.valid || !authorized(face, controller) || !authorized(choice.face, controller)
                    || choice.face.endpoint.live(level) == null || !controller.canPayLogistics(cost)) continue;
            int amount = Math.min(choice.room, simulated.getCount());
            if (!controller.payLogistics(cost)) return new Attempt(checks, false, false, true);
            ItemStack extracted;
            try {
                extracted = TransferEngine.extract(again.handler(), slot, amount, simulated);
            } catch (TransferEngine.Anomaly anomaly) {
                extracted = anomaly.stack();
                quarantine(face, anomaly);
            } catch (RuntimeException failure) {
                // Unknown outcome inside a third-party handler: nothing is invented to compensate.
                quarantine(face, failure);
                controller.refundLogistics(cost);
                return new Attempt(checks, false, false, false);
            }
            if (extracted.isEmpty()) { controller.refundLogistics(cost); continue; }
            var target = choice.face.endpoint;
            TransitPacket packet = new TransitPacket(UUID.randomUUID(), controller.id(), controller.getBlockPos(), extracted,
                    face.endpoint.pipe(), face.endpoint.side(), face.endpoint.identity(), target.pipe(), target.side(), target.identity(), choice.route);
            packet.network = controller.networkId();
            packet.autonomousOwner = controller.autonomous() ? controller.owner() : null;
            packet.checkedRevision = source.component.revision;
            ledger().add(packet);
            if (!TransferEngine.sameVariant(extracted, simulated) || !choice.face.config.filter().allows(extracted)) block(packet, PipeStatus.PORT_REJECTED, now);
            sync.start(packet, packet.state != TransitPacket.State.ACTIVE);
            controller.inventoryChanged(face.endpoint.identity());
            state.slotCursor.put(identity, slot + 1);
            return new Attempt(checks, true, false, false);
        }
        state.slotCursor.put(identity, Math.floorMod(cursor + checks, Math.max(1, slots)));
        return new Attempt(checks, false, noRoute, false);
    }

    /** Revalidate rights immediately before inventory operations, including offline configuring players. */
    private boolean authorized(PipeComponent.Face face, PipeCoordinator controller) {
        return face.usable && (controller.autonomous() ? PipeAccess.autonomousAllowed(level, face, controller.owner())
                : PipeAccess.armedFor(level, face.config, controller.block)
                && PipeAccess.targetAllowed(level, PipeAccess.devices(level), face.endpoint.target(), controller.block));
    }

    private @Nullable IItemHandler window(PipeCoordinator controller, PipeComponent.Face face, IItemHandler handler) {
        ControllerState state = state(controller.id());
        // Two passes in room(): charge both, and bound real insertion before any mutation.
        int size = Math.min(handler.getSlots(), Math.min(64, state.slotBudget / 2));
        if (size <= 0) return null;
        state.slotBudget -= size * 2;
        var key = face.endpoint.key();
        int start = state.destinationSlots.getOrDefault(key, 0);
        state.destinationSlots.put(key, Math.floorMod(start + size, handler.getSlots()));
        return new fr.lkdm.homelink.storage.logistics.transit.HandlerWindow(handler, start, size);
    }

    /**
     * Nearest destination accepting the object for this Controller, by route length, with
     * round-robin between destinations at the same distance.
     */
    private @Nullable Choice choose(PipeCoordinator controller, PipeComponent component, BlockPos from, ItemStack stack,
                                    BlockPos sourceIdentity, @Nullable UUID moving) {
        int maximum = StorageConfig.pipe(StorageConfig.PIPE_MAX_ROUTE);
        ControllerState work = state(controller.id());
        if (work.routeBudget-- <= 0 || work.slotBudget < 2) return null;
        List<Choice> candidates = new ArrayList<>();
        for (PipeComponent.Face face : component.faces.values()) {
            if (!face.destination() || face.endpoint.identity().equals(sourceIdentity) || !face.config.filter().allows(stack)
                    || !authorized(face, controller)) continue;
            List<BlockPos> route = component.route(from, face.endpoint.pipe(), maximum);
            if (route != null) candidates.add(new Choice(face, route, 0));
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingInt((Choice choice) -> choice.route.size()).thenComparing(choice -> choice.face.endpoint.key()));
        ControllerState state = state(controller.id());
        int i = 0;
        int destinationRound = state.destinationCursor++;
        while (i < candidates.size()) {
            int length = candidates.get(i).route.size();
            int end = i;
            while (end < candidates.size() && candidates.get(end).route.size() == length) end++;
            int size = end - i;
            int offset = Math.floorMod(destinationRound, size);
            for (int k = 0; k < size; k++) {
                Choice candidate = candidates.get(i + (offset + k) % size);
                PipeEndpoint.Live live = candidate.face.endpoint.live(level);
                if (live == null) { component.facesDirty = true; continue; }
                IItemHandler window = window(controller, candidate.face, live.handler());
                if (window == null) return null;
                int room;
                try { room = TransferEngine.room(window, stack, heading(controller.id(), candidate.face.endpoint.identity(), moving)); }
                catch (RuntimeException failure) { quarantine(candidate.face, failure); continue; }
                if (room > 0) {
                    return new Choice(candidate.face, candidate.route, room);
                }
            }
            i = end;
        }
        return null;
    }

    /** Cargo of this Controller already promised to that container. Protects only against our own sends. */
    private List<ItemStack> heading(UUID controller, BlockPos identity, @Nullable UUID except) {
        List<ItemStack> result = new ArrayList<>();
        for (TransitPacket packet : ledger().packets())
            if (packet.controller.equals(controller) && packet.state != TransitPacket.State.RECOVERY
                    && packet.destinationIdentity.equals(identity) && !packet.id.equals(except)) result.add(packet.stack());
        return result;
    }

    // ---------------------------------------------------------------- transit

    private @Nullable PipeStatus waiting(TransitPacket packet, @Nullable PipeComponent component) {
        if (component == null) return PipeStatus.UPDATING;
        if (component.controllers.size() > 1) return PipeStatus.CONTROLLER_CONFLICT;
        if (packet.autonomousOwner != null) {
            if (component.incomplete) return PipeStatus.CHUNK_UNLOADED;
            if (component.tooLarge) return PipeStatus.LIMIT_EXCEEDED;
            return StorageConfig.pipesEnabled() ? null : PipeStatus.PAUSED;
        }
        // Another Controller's circuit never adopts this cargo.
        if (!component.controllers.containsKey(packet.controller))
            return component.controllers.isEmpty() ? component.incomplete ? PipeStatus.CHUNK_UNLOADED : PipeStatus.NO_CONTROLLER : PipeStatus.ACCESS_DENIED;
        if (component.incomplete) return PipeStatus.CHUNK_UNLOADED;
        if (component.tooLarge) return PipeStatus.LIMIT_EXCEEDED;
        StorageBlockEntity controller = controllerAt(level, packet.controller, component.controllers.get(packet.controller));
        if (controller == null) return PipeStatus.CONTROLLER_OFFLINE;
        if (!java.util.Objects.equals(packet.network, controller.networkId())) return PipeStatus.ACCESS_DENIED;
        if (controller.pipesPaused()) return PipeStatus.PAUSED;
        if (!controller.automationAvailable()) return controller.powered() ? PipeStatus.CONTROLLER_OFFLINE : PipeStatus.NO_POWER;
        return null;
    }

    private void step(TransitPacket packet, long now) {
        BlockPos pos = packet.route.get(packet.index);
        // Frozen with its chunk: no catch-up, no delivery, no ticket.
        if (!level.isLoaded(pos)) { pause(packet, PipeStatus.CHUNK_UNLOADED); return; }
        if (!(LoadedBlocks.state(level, pos).getBlock() instanceof StoragePipeBlock)) { toRecovery(packet, PipeStatus.RECOVERY_REQUIRED); return; }
        PipeComponent component = component(pos);
        PipeStatus wait = waiting(packet, component);
        if (wait != null) { pause(packet, wait); return; }
        PipeCoordinator controller = packet.autonomousOwner != null
                ? new PipeCoordinator(level, packet.autonomousOwner, packet.sourcePipe)
                : new PipeCoordinator(level, controllerAt(level, packet.controller, component.controllers.get(packet.controller)));
        if (packet.checkedRevision != component.revision) {
            packet.checkedRevision = component.revision;
            if (packet.state == TransitPacket.State.ACTIVE && !PipeGraph.valid(component.pipes, packet.route, packet.index)
                    && !route(packet, component, controller, false, now)) { block(packet, PipeStatus.NO_ROUTE, now); return; }
        }
        if (packet.state == TransitPacket.State.BLOCKED) {
            if (now >= packet.nextAttempt) retry(packet, component, controller, now);
            return;
        }
        resume(packet);
        int ticks = StorageConfig.pipe(StorageConfig.PIPE_TRAVEL_TICKS);
        ledger().setDirty();
        if (++packet.progress < ticks) return;
        if (packet.index < packet.route.size() - 1) {
            BlockPos next = packet.route.get(packet.index + 1);
            if (!component.pipes.contains(next)) { packet.progress = ticks; block(packet, PipeStatus.NO_ROUTE, now); return; }
            packet.entry = PipeGraph.side(next, pos);
            packet.index++;
            packet.progress = 0;
            ledger().setDirty();
        } else {
            packet.progress = ticks;
            deliver(packet, component, controller, now);
        }
    }

    private void pause(TransitPacket packet, PipeStatus reason) {
        if (packet.reason == reason) return;
        boolean moving = packet.state == TransitPacket.State.ACTIVE && packet.reason == PipeStatus.TRANSFERRING;
        packet.reason = reason;
        if (moving) sync.update(packet, true);
        ledger().setDirty();
    }

    private void resume(TransitPacket packet) {
        if (packet.reason == PipeStatus.TRANSFERRING) return;
        packet.reason = PipeStatus.TRANSFERRING;
        sync.update(packet, false);
        ledger().setDirty();
    }

    private void block(TransitPacket packet, PipeStatus reason, long now) {
        if (packet.state != TransitPacket.State.BLOCKED) {
            packet.state = TransitPacket.State.BLOCKED;
            packet.blockedSince = now;
            sync.update(packet, true);
        }
        packet.reason = reason;
        packet.nextAttempt = now + StorageConfig.pipe(StorageConfig.PIPE_RETRY_INTERVAL);
        ledger().setDirty();
    }

    private boolean waitedLong(TransitPacket packet, long now) {
        return packet.state == TransitPacket.State.BLOCKED && now - packet.blockedSince >= StorageConfig.pipe(StorageConfig.PIPE_RETURN_DELAY);
    }

    private void retry(TransitPacket packet, PipeComponent component, PipeCoordinator controller, long now) {
        packet.nextAttempt = now + StorageConfig.pipe(StorageConfig.PIPE_RETRY_INTERVAL);
        int ticks = StorageConfig.pipe(StorageConfig.PIPE_TRAVEL_TICKS);
        if (packet.index == packet.route.size() - 1 && packet.progress >= ticks) deliver(packet, component, controller, now);
        else route(packet, component, controller, waitedLong(packet, now), now);
    }

    /** New destination reachable from where the cargo stands, or back to its source after a long wait. */
    private boolean route(TransitPacket packet, PipeComponent component, PipeCoordinator controller, boolean back, long now) {
        BlockPos from = packet.route.get(packet.index);
        Choice choice = choose(controller, component, from, packet.stack(), packet.sourceIdentity, packet.id);
        if (choice != null) {
            var target = choice.face.endpoint;
            packet.reroute(choice.route, target.pipe(), target.side(), target.identity(), false);
            packet.progress = Math.min(packet.progress, StorageConfig.pipe(StorageConfig.PIPE_TRAVEL_TICKS) - 1);
            sync.start(packet, false);
            ledger().setDirty();
            return true;
        }
        if (!back) return false;
        PipeComponent.Face origin = component.face(packet.sourcePipe, packet.sourceSide);
        if (origin == null || !origin.authorized || !origin.endpoint.identity().equals(packet.sourceIdentity)
                || !origin.config.filter().allows(packet.stack())
                || origin.endpoint.port() != null && !origin.endpoint.port().canReceive()) return false;
        List<BlockPos> path = component.route(from, origin.endpoint.pipe(), StorageConfig.pipe(StorageConfig.PIPE_MAX_ROUTE));
        PipeEndpoint.Live live = origin.endpoint.live(level);
        if (path == null || live == null) return false;
        IItemHandler returnWindow = window(controller, origin, live.handler());
        if (returnWindow == null) return false;
        try { if (TransferEngine.room(returnWindow, packet.stack(), heading(controller.id(), packet.sourceIdentity, packet.id)) <= 0) return false; }
        catch (RuntimeException failure) { quarantine(origin, failure); return false; }
        packet.reroute(path, origin.endpoint.pipe(), origin.endpoint.side(), origin.endpoint.identity(), true);
        packet.progress = Math.min(packet.progress, StorageConfig.pipe(StorageConfig.PIPE_TRAVEL_TICKS) - 1);
        sync.start(packet, false);
        ledger().setDirty();
        return true;
    }

    private void deliver(TransitPacket packet, PipeComponent component, PipeCoordinator controller, long now) {
        boolean back = waitedLong(packet, now);
        PipeComponent.Face face = component.face(packet.destinationPipe, packet.destinationSide);
        boolean valid = face != null && authorized(face, controller)
                && face.endpoint.identity().equals(packet.destinationIdentity) && (packet.returning
                ? face.authorized && face.endpoint.identity().equals(packet.sourceIdentity)
                        && face.config.filter().allows(packet.stack())
                        && (face.endpoint.port() == null || face.endpoint.port().canReceive())
                : face.destination() && face.config.filter().allows(packet.stack()) && !face.endpoint.identity().equals(packet.sourceIdentity));
        // Settings changed on the way: never delivered by force, routed again or kept.
        if (!valid) { if (!route(packet, component, controller, back, now)) block(packet, PipeStatus.NO_ROUTE, now); return; }
        PipeEndpoint.Live live = face.endpoint.live(level);
        if (live == null) {
            component.facesDirty = true;
            if (!route(packet, component, controller, back, now)) block(packet, PipeStatus.NO_ROUTE, now);
            return;
        }
        ItemStack carried = packet.stack();
        IItemHandler window = window(controller, face, live.handler());
        if (window == null) return;
        ItemStack remainder;
        try {
            remainder = TransferEngine.insert(window, carried);
        } catch (TransferEngine.Anomaly anomaly) {
            remainder = anomaly.stack();
            quarantine(face, anomaly);
            packet.uncertain = true;
            packet.setStack(remainder);
            toRecovery(packet, PipeStatus.PORT_REJECTED);
            return;
        } catch (RuntimeException failure) {
            // Outcome unknown: retain evidence but forbid compensation, including player recovery and drops.
            quarantine(face, failure);
            packet.uncertain = true;
            toRecovery(packet, PipeStatus.PORT_REJECTED);
            return;
        }
        int delivered = remainder.isEmpty() || TransferEngine.sameVariant(carried, remainder) ? carried.getCount() - remainder.getCount() : 0;
        controller.inventoryChanged(face.endpoint.identity());
        if (delivered > 0) state(controller.id()).delivered(now, delivered);
        if (remainder.isEmpty()) {
            ledger().remove(packet.id);
            sync.remove(packet, true);
            return;
        }
        packet.setStack(remainder);
        ledger().setDirty();
        if (!route(packet, component, controller, back, now)) block(packet, PipeStatus.DESTINATION_FULL, now);
    }

    private void toRecovery(TransitPacket packet, PipeStatus reason) {
        if (packet.position() != null) sync.remove(packet, false);
        packet.state = TransitPacket.State.RECOVERY;
        packet.reason = reason;
        ledger().setDirty();
        BlockPos at=packet.route.get(packet.index);
        if(packet.autonomousOwner!=null && !packet.uncertain && level.isLoaded(at) && ledger().remove(packet.id)!=null)
            Containers.dropItemStack(level,at.getX()+0.5,at.getY()+0.5,at.getZ()+0.5,packet.stack().copy());
    }

    /** A broken segment: the cargo on it is kept by its Controller, never left in the void or copied. */
    private void recoverAt(BlockPos pos) {
        for (TransitPacket packet : List.copyOf(ledger().packets()))
            if (pos.equals(packet.position())) toRecovery(packet, PipeStatus.RECOVERY_REQUIRED);
    }

    // ---------------------------------------------------------------- Controller lifecycle and recovery

    /**
     * A Controller is destroyed: all its cargo, including cargo stopped in unloaded chunks, is
     * resolved once from the ledger and dropped at the Controller, like a chest's contents.
     */
    public void controllerDestroyed(StorageBlockEntity controller) {
        BlockPos pos = controller.getBlockPos();
        for (Direction side : Direction.values()) invalidate(byPipe.get(pos.relative(side)));
        for (TransitPacket packet : ledger().owned(controller.id())) {
            if (packet.uncertain) continue;
            if (ledger().remove(packet.id) == null) continue;
            if (packet.position() != null) sync.remove(packet, false);
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, packet.stack().copy());
        }
        states.remove(controller.id());
    }

    /** Cargo whose Controller is provably gone (its loaded position holds no such Controller). */
    private void resolveOrphans() {
        for (TransitPacket packet : List.copyOf(ledger().packets())) {
            if (packet.uncertain) continue;
            if (packet.autonomousOwner != null) {
                // The ledger remains authoritative when a pipe disappears. Release once, at the
                // last physical segment, never at a remote Controller and never into an unloaded chunk.
                BlockPos at = packet.route.get(packet.index);
                if (packet.state != TransitPacket.State.RECOVERY || !level.isLoaded(at)) continue;
                if (ledger().remove(packet.id) != null)
                    Containers.dropItemStack(level, at.getX()+0.5, at.getY()+0.5, at.getZ()+0.5, packet.stack().copy());
                continue;
            }
            if (!level.isLoaded(packet.controllerPos)) continue;
            BlockState state = LoadedBlocks.state(level, packet.controllerPos);
            boolean present = state.is(StorageRegistries.CONTROLLER.get())
                    && (!(LoadedBlocks.entity(level, packet.controllerPos) instanceof StorageBlockEntity storage) || storage.id().equals(packet.controller));
            if (present || ledger().remove(packet.id) == null) continue;
            if (packet.position() != null) sync.remove(packet, false);
            BlockPos pos = packet.controllerPos;
            Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, packet.stack().copy());
            LogUtils.getLogger().info("Storage pipe cargo {} of a removed Controller was dropped at {}", packet.id, pos);
        }
    }

    /** Explicit recovery by an authorized player: only cargo that is blocked or has no segment any more. */
    public boolean retrieve(ServerPlayer player, StorageBlockEntity controller, UUID id) {
        TransitPacket packet = ledger().get(id);
        if (busy || !PipeAccess.near(player, controller.getBlockPos())
                || !controller.permission(player, fr.lkdm.homecore.api.security.Permission.CONTROL)) return false;
        if (packet == null || packet.uncertain || !packet.controller.equals(controller.id()) || packet.state == TransitPacket.State.ACTIVE) return false;
        if (ledger().remove(id) == null) return false;
        if (packet.position() != null) sync.remove(packet, false);
        ItemHandlerHelper.giveItemToPlayer(player, packet.stack().copy());
        return true;
    }

    // ---------------------------------------------------------------- telemetry

    /** Pipe figures of one Controller. {@code partial} when a circuit is ambiguous or unloaded. */
    public record Telemetry(int pipes, int circuits, int sources, int destinations, int inFlight, long itemsInTransit,
                            long deliveredPerMinute, int blocked, int recovery, long recoveryItems, String status, boolean partial,
                            boolean conflict) { }

    public Telemetry telemetry(UUID controller) {
        int pipes = 0, circuits = 0, sources = 0, destinations = 0, inFlight = 0, blocked = 0, recovery = 0;
        long transit = 0, recovered = 0;
        boolean partial = false, conflict = false;
        PipeStatus worst = null;
        long now = level.getGameTime();
        for (PipeComponent component : components) {
            if (!component.controllers.containsKey(controller)) continue;
            if (component.controllers.size() > 1) conflict = true;
            if (component.incomplete || component.tooLarge || component.controllers.size() > 1) partial = true;
            circuits++;
            pipes += component.pipes.size();
            for (PipeComponent.Face face : component.faces.values()) {
                if (face.source()) sources++;
                if (face.destination()) destinations++;
            }
            if (worst == null || rank(component.status) > rank(worst)) worst = component.status;
        }
        if (build != null || !seeds.isEmpty()) partial = true;
        for (TransitPacket packet : ledger().packets()) {
            if (!packet.controller.equals(controller)) continue;
            if (packet.state == TransitPacket.State.RECOVERY) { recovery++; recovered += packet.stack().getCount(); continue; }
            inFlight++;
            transit += packet.stack().getCount();
            if (packet.state == TransitPacket.State.BLOCKED) blocked++;
        }
        if (recovery > 0 && (worst == null || rank(PipeStatus.RECOVERY_REQUIRED) > rank(worst))) worst = PipeStatus.RECOVERY_REQUIRED;
        ControllerState state = states.get(controller);
        long perMinute = state == null ? 0 : state.perMinute(now);
        String status = worst == null ? "NONE" : worst.name();
        return new Telemetry(pipes, circuits, sources, destinations, inFlight, transit, perMinute, blocked, recovery, recovered, status, partial, conflict);
    }

    private static int rank(PipeStatus status) {
        return switch (status) {
            case IDLE, UPDATING -> 0;
            case TRANSFERRING -> 1;
            case NEEDS_CONFIGURATION -> 2;
            case PAUSED -> 3;
            case NO_ROUTE, DESTINATION_FULL -> 4;
            case CHUNK_UNLOADED, PORT_REJECTED, LOOP_CONFLICT -> 5;
            case ACCESS_DENIED, LIMIT_EXCEEDED -> 6;
            case RECOVERY_REQUIRED -> 7;
            case NO_POWER, CONTROLLER_OFFLINE, NO_CONTROLLER -> 8;
            case CONTROLLER_CONFLICT -> 9;
        };
    }

    /** True once no circuit of this dimension waits to be rebuilt (verification and tools). */
    public boolean settled() { return build == null && seeds.isEmpty(); }

    /** Finishes pending rebuilds at once (verification and tools only; never called by gameplay). */
    public void settleNow() {
        rebuild(Integer.MAX_VALUE);
        for (PipeComponent component : List.copyOf(components)) {
            refreshFaces(component, level.getGameTime());
            component.status = status(component, level.getGameTime());
            pushLamp(component);
        }
    }

    /** Next departure round of a Controller happens on the coming tick (verification and tools only). */
    public void dispatchStandaloneSoon(UUID owner) {
        dispatchSoon(new PipeCoordinator(level, owner, BlockPos.ZERO).id());
    }

    /** Next departure round of an authority happens on the coming tick (verification and tools only). */
    public void dispatchSoon(UUID controller) { state(controller).nextDispatch = Long.MIN_VALUE; }
}
