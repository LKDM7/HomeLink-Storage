package fr.lkdm.homelink.storage.logistics.sync;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Bounded wire formats of the pipes. Clients only ever receive pictures, never authority. */
public final class PipePayloads {
    /** Longest route a picture may describe; longer server routes are cut to their visible part. */
    public static final int MAX_VISUAL_ROUTE = 512;
    /** Hard cap on filter entries in one edit; the server config may lower it. */
    public static final int MAX_FILTER_WIRE = 1024;

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(HomeLinkStorage.MOD_ID, path));
    }

    private static void writeRoute(RegistryFriendlyByteBuf buf, List<BlockPos> route) {
        if (route.isEmpty() || route.size() > MAX_VISUAL_ROUTE) throw new IllegalArgumentException("Invalid visual route");
        buf.writeVarInt(route.size());
        for (BlockPos pos : route) buf.writeLong(pos.asLong());
    }

    private static List<BlockPos> readRoute(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size <= 0 || size > MAX_VISUAL_ROUTE) throw new IllegalArgumentException("Invalid visual route");
        List<BlockPos> route = new ArrayList<>(size);
        for (int i = 0; i < size; i++) route.add(BlockPos.of(buf.readLong()));
        return route;
    }

    /** A cargo became visible or changed route. {@code display} is a one-item picture of the stack. */
    public record TransitStart(UUID id, ItemStack display, List<BlockPos> route, byte entry, byte exit, int index,
                               int progress, int ticksPerBlock, boolean paused) implements CustomPacketPayload {
        public static final Type<TransitStart> TYPE = typeOf("transit_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, TransitStart> CODEC = StreamCodec.of((buf, value) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, value.id);
            ItemStack.STREAM_CODEC.encode(buf, value.display);
            writeRoute(buf, value.route);
            buf.writeByte(value.entry); buf.writeByte(value.exit);
            buf.writeVarInt(value.index); buf.writeVarInt(value.progress); buf.writeVarInt(value.ticksPerBlock);
            buf.writeBoolean(value.paused);
        }, buf -> new TransitStart(UUIDUtil.STREAM_CODEC.decode(buf), ItemStack.STREAM_CODEC.decode(buf), readRoute(buf),
                buf.readByte(), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        @Override public Type<TransitStart> type() { return TYPE; }
    }

    /** The cargo left the pipes: delivered, recovered or dropped. */
    public record TransitRemove(UUID id, boolean delivered) implements CustomPacketPayload {
        public static final Type<TransitRemove> TYPE = typeOf("transit_remove");
        public static final StreamCodec<RegistryFriendlyByteBuf, TransitRemove> CODEC = StreamCodec.of((buf, value) -> {
            UUIDUtil.STREAM_CODEC.encode(buf, value.id);
            buf.writeBoolean(value.delivered);
        }, buf -> new TransitRemove(UUIDUtil.STREAM_CODEC.decode(buf), buf.readBoolean()));
        @Override public Type<TransitRemove> type() { return TYPE; }
    }

    /** Settings and state of one face, for a player allowed to view them. */
    public record FaceView(BlockPos pos, CompoundTag data) implements CustomPacketPayload {
        public static final Type<FaceView> TYPE = typeOf("face_view");
        public static final StreamCodec<RegistryFriendlyByteBuf, FaceView> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeBlockPos(value.pos); buf.writeNbt(value.data);
        }, buf -> new FaceView(buf.readBlockPos(), buf.readNbt()));
        @Override public Type<FaceView> type() { return TYPE; }
    }

    /** Container faces of a pipe whose centre was clicked. */
    public record FaceChoice(BlockPos pos, CompoundTag data) implements CustomPacketPayload {
        public static final Type<FaceChoice> TYPE = typeOf("face_choice");
        public static final StreamCodec<RegistryFriendlyByteBuf, FaceChoice> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeBlockPos(value.pos); buf.writeNbt(value.data);
        }, buf -> new FaceChoice(buf.readBlockPos(), buf.readNbt()));
        @Override public Type<FaceChoice> type() { return TYPE; }
    }

    /** Request to open one face of a pipe. */
    public record OpenFace(BlockPos pos, byte side) implements CustomPacketPayload {
        public static final Type<OpenFace> TYPE = typeOf("open_face");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenFace> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeBlockPos(value.pos); buf.writeByte(value.side);
        }, buf -> new OpenFace(buf.readBlockPos(), buf.readByte()));
        @Override public Type<OpenFace> type() { return TYPE; }
    }

    /** Edit of one face, based on the revision the player saw. */
    public record ApplyFace(BlockPos pos, byte side, long revision, byte mode, byte filter, List<String> items) implements CustomPacketPayload {
        public static final Type<ApplyFace> TYPE = typeOf("apply_face");
        public static final StreamCodec<RegistryFriendlyByteBuf, ApplyFace> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeBlockPos(value.pos); buf.writeByte(value.side); buf.writeVarLong(value.revision);
            buf.writeByte(value.mode); buf.writeByte(value.filter);
            if (value.items.size() > MAX_FILTER_WIRE) throw new IllegalArgumentException("Too many filter entries");
            buf.writeVarInt(value.items.size());
            for (String item : value.items) buf.writeUtf(item, 256);
        }, buf -> {
            BlockPos pos = buf.readBlockPos();
            byte side = buf.readByte();
            long revision = buf.readVarLong();
            byte mode = buf.readByte(), filter = buf.readByte();
            int size = buf.readVarInt();
            if (size < 0 || size > MAX_FILTER_WIRE) throw new IllegalArgumentException("Too many filter entries");
            List<String> items = new ArrayList<>(size);
            for (int i = 0; i < size; i++) items.add(buf.readUtf(256));
            return new ApplyFace(pos, side, revision, mode, filter, items);
        });
        @Override public Type<ApplyFace> type() { return TYPE; }
    }

    /** Pipes and recovery view of a Controller. */
    public record RecoveryView(BlockPos controller, CompoundTag data) implements CustomPacketPayload {
        public static final Type<RecoveryView> TYPE = typeOf("recovery_view");
        public static final StreamCodec<RegistryFriendlyByteBuf, RecoveryView> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeBlockPos(value.controller); buf.writeNbt(value.data);
        }, buf -> new RecoveryView(buf.readBlockPos(), buf.readNbt()));
        @Override public Type<RecoveryView> type() { return TYPE; }
    }

    /** Recovery view request or action: "open", "retrieve", "pause", "resume". */
    public record RecoveryAction(BlockPos controller, String action, String target) implements CustomPacketPayload {
        public static final Type<RecoveryAction> TYPE = typeOf("recovery_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, RecoveryAction> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeBlockPos(value.controller); buf.writeUtf(value.action, 16); buf.writeUtf(value.target, 36);
        }, buf -> new RecoveryAction(buf.readBlockPos(), buf.readUtf(16), buf.readUtf(36)));
        @Override public Type<RecoveryAction> type() { return TYPE; }
    }

    private PipePayloads() { }
}
