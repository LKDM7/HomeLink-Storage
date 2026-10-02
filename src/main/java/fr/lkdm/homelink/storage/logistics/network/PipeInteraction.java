package fr.lkdm.homelink.storage.logistics.network;

import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.logistics.filter.FilterMode;
import fr.lkdm.homelink.storage.logistics.filter.FlowMode;
import fr.lkdm.homelink.storage.logistics.filter.PipeFaceConfig;
import fr.lkdm.homelink.storage.logistics.filter.PipeFilter;
import fr.lkdm.homelink.storage.logistics.pipe.PipeConnection;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import fr.lkdm.homelink.storage.logistics.sync.PipePayloads;
import fr.lkdm.homelink.storage.logistics.sync.PipeSync;
import fr.lkdm.homelink.storage.logistics.transit.TransitPacket;
import fr.lkdm.homelink.storage.network.StorageRequestBudget;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Nameable;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Server handling of every pipe request. Each one re-checks the real player, distance,
 * block, face, permission, revision and request rate; no request ever creates or delivers cargo.
 */
public final class PipeInteraction {
    /** Right-click on a pipe; {@code side} is the clicked connector, or null for the centre. */
    public static void use(ServerPlayer player, StoragePipeBlockEntity pipe, @Nullable Direction side) {
        if (!StorageRequestBudget.acquire(player) || !PipeAccess.near(player, pipe.getBlockPos())) return;
        var state = pipe.getBlockState();
        if (side != null && StoragePipeBlock.side(state, side) == PipeConnection.CONTAINER) { open(player, pipe, side); return; }
        if (side == null) {
            ListTag choices = new ListTag();
            Direction only = null;
            for (Direction direction : Direction.values()) {
                if (StoragePipeBlock.side(state, direction) != PipeConnection.CONTAINER) continue;
                only = choices.isEmpty() ? direction : null;
                CompoundTag choice = new CompoundTag();
                choice.putByte("Face", (byte) direction.get3DDataValue());
                neighbour(pipe, direction, choice);
                choices.add(choice);
            }
            if (choices.size() == 1) { open(player, pipe, only); return; }
            if (choices.size() > 1) {
                if (PipeAccess.check(player, pipe, Permission.VIEW) != PipeAccess.Decision.ALLOWED) { brief(player, pipe, true); return; }
                CompoundTag data = new CompoundTag();
                data.put("Faces", choices);
                PacketDistributor.sendToPlayer(player, new PipePayloads.FaceChoice(pipe.getBlockPos(), data));
                return;
            }
        }
        brief(player, pipe, false);
    }

    /** Short state of a segment with no container on the clicked side. */
    private static void brief(ServerPlayer player, StoragePipeBlockEntity pipe, boolean denied) {
        ServerLevel level = (ServerLevel) pipe.getLevel();
        PipeNetworkManager manager = PipeNetworkManager.get(level);
        PipeComponent component = manager.component(pipe.getBlockPos());
        if (denied || PipeAccess.check(player, pipe, Permission.VIEW) == PipeAccess.Decision.DENIED) {
            player.displayClientMessage(Component.translatable("message.homelink_storage.denied"), true);
            return;
        }
        PipeStatus status = component == null ? PipeStatus.UPDATING : manager.status(component, level.getGameTime());
        int segments = component == null ? 0 : component.pipes.size();
        player.displayClientMessage(Component.translatable("message.homelink_storage.pipe_brief", Component.translatable(status.key()), segments), true);
    }

    /** Request from the face selector. */
    public static void open(ServerPlayer player, PipePayloads.OpenFace request) {
        if (!StorageRequestBudget.acquire(player) || request.side() < 0 || request.side() > 5) return;
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(request.pos())
                || !(level.getBlockEntity(request.pos()) instanceof StoragePipeBlockEntity pipe)) return;
        if (!PipeAccess.near(player, pipe.getBlockPos())) return;
        Direction side = Direction.from3DDataValue(request.side());
        if (StoragePipeBlock.side(pipe.getBlockState(), side) == PipeConnection.CONTAINER) open(player, pipe, side);
    }

    private static void open(ServerPlayer player, StoragePipeBlockEntity pipe, Direction side) {
        PipeAccess.Decision decision = PipeAccess.check(player, pipe, Permission.VIEW);
        if (decision != PipeAccess.Decision.ALLOWED) { refuse(player, decision); return; }
        PacketDistributor.sendToPlayer(player, new PipePayloads.FaceView(pipe.getBlockPos(), view(player, pipe, side, "")));
    }

    private static void refuse(ServerPlayer player, PipeAccess.Decision decision) {
        String key = switch (decision) {
            case CONFLICT -> "pipe_conflict";
            case UNAVAILABLE -> "pipe_unavailable";
            default -> "denied";
        };
        player.displayClientMessage(Component.translatable("message.homelink_storage." + key), true);
    }

    private static void neighbour(StoragePipeBlockEntity pipe, Direction side, CompoundTag tag) {
        var level = pipe.getLevel();
        BlockPos target = pipe.getBlockPos().relative(side);
        tag.putString("Block", StoragePipeBlockEntity.blockId(level.getBlockState(target).getBlock()));
        if (level.getBlockEntity(target) instanceof Nameable named && named.hasCustomName() && named.getCustomName() != null) {
            String name = named.getCustomName().getString();
            tag.putString("Name", name.length() > 64 ? name.substring(0, 64) : name);
        }
    }

    /** Everything the face screen shows, for a player already allowed to view it. */
    static CompoundTag view(ServerPlayer player, StoragePipeBlockEntity pipe, Direction side, String result) {
        ServerLevel level = (ServerLevel) pipe.getLevel();
        PipeNetworkManager manager = PipeNetworkManager.get(level);
        CompoundTag tag = new CompoundTag();
        tag.putByte("Face", (byte) side.get3DDataValue());
        neighbour(pipe, side, tag);
        PipeFaceConfig config = pipe.config(side);
        PipeEndpoint.Live live = PipeEndpoint.resolve(level, pipe.getBlockPos(), side);
        tag.putString("Port", live == null ? "NONE" : live.endpoint().port() == null ? "GENERIC" : live.endpoint().port().name());
        tag.putBoolean("Armed", config != null && config.armed());
        tag.putInt("Mode", config == null || config.mode() == null ? -1 : config.mode().ordinal());
        tag.putInt("Filter", config == null ? FilterMode.BLACKLIST.ordinal() : config.filterMode().ordinal());
        ListTag items = new ListTag();
        if (config != null) for (ResourceLocation id : config.items()) items.add(StringTag.valueOf(id.toString()));
        tag.put("Items", items);
        tag.putLong("Revision", config == null ? 0 : config.revision());
        PipeComponent component = manager.component(pipe.getBlockPos());
        PipeComponent.Face face = component == null ? null : component.face(pipe.getBlockPos(), side);
        PipeStatus circuit = component == null ? PipeStatus.UPDATING : manager.status(component, level.getGameTime());
        PipeStatus status = face == null ? circuit : face.usable && face.status == PipeStatus.IDLE ? circuit : face.status;
        if (live == null) status = PipeStatus.PORT_REJECTED;
        tag.putString("Status", status.name());
        tag.putString("Circuit", circuit.name());
        tag.putBoolean("Autonomous", component != null && component.controllers.isEmpty() && !component.incomplete);
        // Re-Apply must be enabled when a previously managed face changes to standalone ownership.
        if (component != null && component.controllers.isEmpty() && config != null && config.armedController() != null)
            tag.putBoolean("Armed", false);
        StorageBlockEntity controller = component == null ? null : manager.controller(component);
        // A detected Controller remains identifiable while an incomplete graph prevents dispatch.
        // This view has already passed VIEW permission checks; it does not elect a manager.
        if (controller == null && component != null && component.controllers.size() == 1) {
            var detected = component.controllers.entrySet().iterator().next();
            controller = PipeNetworkManager.controllerAt(level, detected.getKey(), detected.getValue());
        }
        tag.putString("Controller", controller == null ? "" : controller.getDisplayName().getString());
        tag.putBoolean("CanEdit", PipeAccess.check(player, pipe, Permission.CONFIGURE) == PipeAccess.Decision.ALLOWED);
        tag.putInt("MaxFilter", StorageConfig.pipe(StorageConfig.PIPE_MAX_FILTER));
        if (!result.isEmpty()) tag.putString("Result", result);
        return tag;
    }

    /** Validates and stores an edit of one face. */
    public static void apply(ServerPlayer player, PipePayloads.ApplyFace request) {
        if (!StorageRequestBudget.acquire(player) || request.side() < 0 || request.side() > 5) return;
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(request.pos())
                || !(level.getBlockEntity(request.pos()) instanceof StoragePipeBlockEntity pipe)) return;
        if (!PipeAccess.near(player, pipe.getBlockPos())) return;
        Direction side = Direction.from3DDataValue(request.side());
        String result = applyChecked(player, level, pipe, side, request);
        if (PipeAccess.check(player, pipe, Permission.VIEW) == PipeAccess.Decision.ALLOWED)
            PacketDistributor.sendToPlayer(player, new PipePayloads.FaceView(pipe.getBlockPos(), view(player, pipe, side, result)));
        else player.displayClientMessage(Component.translatable("message.homelink_storage.denied"), true);
    }

    private static String applyChecked(ServerPlayer player, ServerLevel level, StoragePipeBlockEntity pipe, Direction side, PipePayloads.ApplyFace request) {
        PipeAccess.Decision decision = PipeAccess.check(player, pipe, Permission.CONFIGURE);
        if (decision == PipeAccess.Decision.CONFLICT) return "conflict_controller";
        if (decision == PipeAccess.Decision.UNAVAILABLE) return "unavailable";
        if (decision != PipeAccess.Decision.ALLOWED) return "denied";
        PipeEndpoint.Live live = PipeEndpoint.resolve(level, pipe.getBlockPos(), side);
        if (live == null || StoragePipeBlock.side(pipe.getBlockState(), side) != PipeConnection.CONTAINER) return "no_container";
        PipeFaceConfig existing = pipe.config(side);
        long revision = existing == null ? 0 : existing.revision();
        // Another player saved this face since it was opened: nothing is overwritten silently.
        if (request.revision() != revision) return "conflict_revision";
        FlowMode mode = FlowMode.byId(request.mode());
        FilterMode filter = FilterMode.byId(request.filter());
        if (mode == null || filter == null) return "invalid";
        if (!mode.allowedBy(live.endpoint().port())) return "port_rejected";
        var validation = PipeFilter.validate(request.items(), existing == null ? java.util.Set.of() : existing.items(),
                PipeFilter::registered, StorageConfig.pipe(StorageConfig.PIPE_MAX_FILTER));
        if (!validation.ok()) return "filter_" + validation.problem().name().toLowerCase(java.util.Locale.ROOT);
        PipeNetworkManager manager = PipeNetworkManager.get(level);
        PipeComponent component = manager.component(pipe.getBlockPos());
        StorageBlockEntity controller = component == null ? null : manager.controller(component);
        PipeFaceConfig config = pipe.configOrCreate(side);
        config.apply(mode, filter, validation.items(), player.getUUID(), controller == null ? null : controller.id(),
                controller == null ? null : controller.networkId(), live.endpoint().block());
        pipe.configChanged();
        manager.facesChanged(pipe.getBlockPos());
        return "saved";
    }

    // ---------------------------------------------------------------- Controller recovery view

    public static void recovery(ServerPlayer player, PipePayloads.RecoveryAction request) {
        if (!StorageRequestBudget.acquire(player)) return;
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(request.controller())
                || !(level.getBlockEntity(request.controller()) instanceof StorageBlockEntity controller) || !controller.isController()) return;
        if (!PipeAccess.near(player, controller.getBlockPos()) || !controller.permission(player, Permission.VIEW)) {
            player.displayClientMessage(Component.translatable("message.homelink_storage.denied"), true);
            return;
        }
        String result = "";
        switch (request.action()) {
            case "open" -> { }
            case "pause", "resume" -> {
                if (!controller.permission(player, Permission.CONTROL)) result = "denied";
                else { controller.setPipesPaused(request.action().equals("pause")); result = "saved"; }
            }
            case "retrieve" -> {
                UUID id = parse(request.target());
                if (!controller.permission(player, Permission.CONTROL)) result = "denied";
                else result = id != null && PipeNetworkManager.get(level).retrieve(player, controller, id) ? "retrieved" : "unavailable";
            }
            default -> { return; }
        }
        PacketDistributor.sendToPlayer(player, new PipePayloads.RecoveryView(controller.getBlockPos(), recoveryView(player, level, controller, result)));
    }

    private static @Nullable UUID parse(String text) {
        try { return UUID.fromString(text); } catch (IllegalArgumentException invalid) { return null; }
    }

    private static final int RECOVERY_ROWS = 64;

    static CompoundTag recoveryView(ServerPlayer player, ServerLevel level, StorageBlockEntity controller, String result) {
        PipeNetworkManager manager = PipeNetworkManager.get(level);
        var telemetry = manager.telemetry(controller.id());
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", controller.getDisplayName().getString());
        tag.putString("Status", telemetry.status());
        tag.putBoolean("Partial", telemetry.partial());
        tag.putBoolean("Paused", controller.pipesPaused());
        tag.putBoolean("CanControl", controller.permission(player, Permission.CONTROL));
        tag.putInt("Pipes", telemetry.pipes());
        tag.putInt("Circuits", telemetry.circuits());
        tag.putInt("Sources", telemetry.sources());
        tag.putInt("Destinations", telemetry.destinations());
        tag.putInt("InFlight", telemetry.inFlight());
        tag.putLong("InTransit", telemetry.itemsInTransit());
        tag.putLong("PerMinute", telemetry.deliveredPerMinute());
        tag.putInt("Blocked", telemetry.blocked());
        tag.putLong("RecoveryItems", telemetry.recoveryItems());
        tag.putInt("Unreadable", manager.ledger().unreadable());
        ListTag rows = new ListTag();
        for (TransitPacket packet : manager.ledger().owned(controller.id())) {
            if (packet.state == TransitPacket.State.ACTIVE || rows.size() >= RECOVERY_ROWS) continue;
            CompoundTag row = new CompoundTag();
            row.putString("Id", packet.id.toString());
            row.put("Stack", PipeSync.picture(level, packet.stack()).save(level.registryAccess()));
            row.putInt("Count", packet.stack().getCount());
            row.putString("State", packet.state.name());
            row.putString("Reason", packet.reason.name());
            row.putBoolean("Uncertain", packet.uncertain);
            rows.add(row);
        }
        tag.put("Rows", rows);
        if (!result.isEmpty()) tag.putString("Result", result);
        return tag;
    }

    private PipeInteraction() { }
}
