package fr.lkdm.homelink.storage.logistics.transit;

import fr.lkdm.homelink.storage.logistics.network.PipeStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * One cargo: the exact stack extracted from a source, owned by a Controller context or a
 * standalone player context until delivery or recovery. Standalone cargo drops locally if its segment breaks.
 * Its only authoritative copy lives in the {@link TransitLedger}; clients see a picture of it.
 */
public final class TransitPacket {
    /** ACTIVE: moving along its route. BLOCKED: waiting on a segment. RECOVERY: no segment any more. */
    public enum State { ACTIVE, BLOCKED, RECOVERY }

    public final UUID id;
    public final UUID controller;
    public final BlockPos controllerPos;
    private ItemStack stack;
    public final BlockPos sourcePipe;
    public final Direction sourceSide;
    public final BlockPos sourceIdentity;
    public BlockPos destinationPipe;
    public Direction destinationSide;
    public BlockPos destinationIdentity;
    /** Segments still to cross; {@code route.get(index)} is the segment holding the cargo. */
    public List<BlockPos> route;
    public int index;
    /** Ticks spent on the current segment. */
    public int progress;
    /** Side of the current segment through which the cargo entered it. */
    public Direction entry;
    public State state = State.ACTIVE;
    public PipeStatus reason = PipeStatus.TRANSFERRING;
    public long blockedSince;
    public long nextAttempt;
    /** Heading back to the source container after waiting too long elsewhere. */
    public boolean returning;
    /** An operation threw after an uncertain third-party mutation. Never automatically redeliver/drop/retrieve. */
    public boolean uncertain;
    /** Automation context at departure; changing networks cannot adopt existing cargo. */
    public @Nullable UUID network;
    /** Non-null for Controller-free cargo; never replaced by a newly adjacent Controller. */
    public @Nullable UUID autonomousOwner;
    /** Circuit revision the route was checked against; transient. */
    public long checkedRevision = -1;

    public TransitPacket(UUID id, UUID controller, BlockPos controllerPos, ItemStack stack, BlockPos sourcePipe, Direction sourceSide,
                         BlockPos sourceIdentity, BlockPos destinationPipe, Direction destinationSide, BlockPos destinationIdentity,
                         List<BlockPos> route) {
        if (stack.isEmpty() || route.isEmpty()) throw new IllegalArgumentException("Empty cargo or route");
        this.id = id;
        this.controller = controller;
        this.controllerPos = controllerPos.immutable();
        this.stack = stack;
        this.sourcePipe = sourcePipe.immutable();
        this.sourceSide = sourceSide;
        this.sourceIdentity = sourceIdentity.immutable();
        this.destinationPipe = destinationPipe.immutable();
        this.destinationSide = destinationSide;
        this.destinationIdentity = destinationIdentity.immutable();
        this.route = List.copyOf(route);
        this.entry = sourceSide;
    }

    public ItemStack stack() { return stack; }
    public void setStack(ItemStack value) { stack = value; }
    /** Segment currently holding the cargo, or null once it is only held by its Controller. */
    public @Nullable BlockPos position() { return state == State.RECOVERY ? null : route.get(index); }

    /** New route from the current segment, keeping the side it entered through. */
    public void reroute(List<BlockPos> path, BlockPos pipe, Direction side, BlockPos identity, boolean back) {
        route = List.copyOf(path);
        index = 0;
        destinationPipe = pipe.immutable();
        destinationSide = side;
        destinationIdentity = identity.immutable();
        returning = back;
        state = State.ACTIVE;
        reason = PipeStatus.TRANSFERRING;
    }

    CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Controller", controller);
        tag.putLong("ControllerPos", controllerPos.asLong());
        tag.put("Stack", stack.save(registries));
        tag.putLong("SourcePipe", sourcePipe.asLong());
        tag.putByte("SourceSide", (byte) sourceSide.get3DDataValue());
        tag.putLong("SourceIdentity", sourceIdentity.asLong());
        tag.putLong("DestinationPipe", destinationPipe.asLong());
        tag.putByte("DestinationSide", (byte) destinationSide.get3DDataValue());
        tag.putLong("DestinationIdentity", destinationIdentity.asLong());
        tag.put("Route", new LongArrayTag(route.stream().mapToLong(BlockPos::asLong).toArray()));
        tag.putInt("Index", index);
        tag.putInt("Progress", progress);
        tag.putByte("Entry", (byte) entry.get3DDataValue());
        tag.putString("State", state.name());
        tag.putString("Reason", reason.name());
        tag.putLong("BlockedSince", blockedSince);
        tag.putBoolean("Returning", returning);
        tag.putBoolean("Uncertain", uncertain);
        if (network != null) tag.putUUID("Network", network);
        if (autonomousOwner != null) tag.putUUID("AutonomousOwner", autonomousOwner);
        return tag;
    }

    /** Reads a saved cargo; empty when the entry cannot be trusted (it is then kept aside, never deleted). */
    static Optional<TransitPacket> load(CompoundTag tag, HolderLookup.Provider registries, int maximumRoute) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Controller") || !tag.contains("Stack")) return Optional.empty();
        Optional<ItemStack> stack = ItemStack.parse(registries, tag.getCompound("Stack"));
        if (stack.isEmpty() || stack.get().isEmpty()) return Optional.empty();
        long[] saved = tag.getLongArray("Route");
        if (saved.length == 0 || saved.length > Math.max(maximumRoute, 4096)) return Optional.empty();
        List<BlockPos> route = new ArrayList<>(saved.length);
        for (long value : saved) route.add(BlockPos.of(value));
        for (int i = 1; i < route.size(); i++) if (route.get(i).distManhattan(route.get(i - 1)) != 1) return Optional.empty();
        int index = tag.getInt("Index");
        if (index < 0 || index >= route.size()) return Optional.empty();
        if (new java.util.HashSet<>(route).size() != route.size()) return Optional.empty();
        for (String key : List.of("SourceSide", "DestinationSide", "Entry"))
            if (!tag.contains(key) || tag.getByte(key) < 0 || tag.getByte(key) > 5) return Optional.empty();
        boolean knownState = false;
        for (State value : State.values()) if (value.name().equals(tag.getString("State"))) knownState = true;
        if (!knownState) return Optional.empty();
        TransitPacket packet = new TransitPacket(tag.getUUID("Id"), tag.getUUID("Controller"), BlockPos.of(tag.getLong("ControllerPos")),
                stack.get(), BlockPos.of(tag.getLong("SourcePipe")), Direction.from3DDataValue(tag.getByte("SourceSide")),
                BlockPos.of(tag.getLong("SourceIdentity")), BlockPos.of(tag.getLong("DestinationPipe")),
                Direction.from3DDataValue(tag.getByte("DestinationSide")), BlockPos.of(tag.getLong("DestinationIdentity")), route);
        packet.index = index;
        packet.progress = Math.max(0, tag.getInt("Progress"));
        packet.entry = Direction.from3DDataValue(tag.getByte("Entry"));
        State state = State.ACTIVE;
        for (State value : State.values()) if (value.name().equals(tag.getString("State"))) state = value;
        packet.state = state;
        packet.reason = PipeStatus.byName(tag.getString("Reason"));
        packet.blockedSince = tag.getLong("BlockedSince");
        packet.returning = tag.getBoolean("Returning");
        packet.uncertain = tag.getBoolean("Uncertain");
        packet.network = tag.hasUUID("Network") ? tag.getUUID("Network") : null;
        packet.autonomousOwner = tag.hasUUID("AutonomousOwner") ? tag.getUUID("AutonomousOwner") : null;
        return Optional.of(packet);
    }
}
