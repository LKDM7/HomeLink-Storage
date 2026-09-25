package fr.lkdm.homelink.storage.network;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID)
public final class StoragePackets {
    public record Data(int menuId, CompoundTag data) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(HomeLinkStorage.MOD_ID, "data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Data> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeVarInt(value.menuId); buf.writeNbt(value.data); },
                buf -> new Data(buf.readVarInt(), buf.readNbt()));
        @Override public Type<Data> type() { return TYPE; }
    }
    /** Room for a bounded recipe-viewer batch ("row:amount;..."). */
    public static final int MAX_VALUE = 512;
    public record Command(int menuId, String action, String target, String value) implements CustomPacketPayload {
        public static final Type<Command> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(HomeLinkStorage.MOD_ID, "command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeVarInt(value.menuId); buf.writeUtf(value.action, 32); buf.writeUtf(value.target, 64); buf.writeUtf(value.value, MAX_VALUE); },
                buf -> new Command(buf.readVarInt(), buf.readUtf(32), buf.readUtf(64), buf.readUtf(MAX_VALUE)));
        @Override public Type<Command> type() { return TYPE; }
    }
    /** Upper bound of chunks in one coverage display: every node of a network plus the clicked one. */
    public static final int MAX_COVERAGE_CHUNKS = 257;
    /** Coverage zone of a Link or Repeater and of the rest of its network, for a local outline. */
    public record Coverage(String dimension, java.util.List<CoverageState.Chunk> chunks) implements CustomPacketPayload {
        public static final Type<Coverage> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(HomeLinkStorage.MOD_ID, "coverage"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Coverage> CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.dimension, 256);
                    if (value.chunks.size() > MAX_COVERAGE_CHUNKS) throw new IllegalArgumentException("Too many coverage chunks");
                    buf.writeVarInt(value.chunks.size());
                    for (var chunk : value.chunks) {
                        buf.writeInt(chunk.x()); buf.writeInt(chunk.z()); buf.writeBlockPos(chunk.node());
                        buf.writeByte((chunk.active() ? 1 : 0) | (chunk.repeater() ? 2 : 0) | (chunk.focus() ? 4 : 0));
                    }
                },
                buf -> {
                    String dimension = buf.readUtf(256);
                    int size = buf.readVarInt();
                    if (size < 0 || size > MAX_COVERAGE_CHUNKS) throw new IllegalArgumentException("Too many coverage chunks");
                    var chunks = new java.util.ArrayList<CoverageState.Chunk>(size);
                    for (int i = 0; i < size; i++) {
                        int x = buf.readInt(), z = buf.readInt();
                        var node = buf.readBlockPos();
                        int flags = buf.readByte();
                        chunks.add(new CoverageState.Chunk(x, z, node, (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0));
                    }
                    return new Coverage(dimension, chunks);
                });
        @Override public Type<Coverage> type() { return TYPE; }
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3");
        registrar.playToClient(Coverage.TYPE, Coverage.CODEC, (coverage, context) -> {
            // A second click already hid the zone locally: replace the "shown" status with "hidden".
            if (!CoverageState.accept(coverage.dimension, coverage.chunks)) context.player().displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.homelink_storage.coverage_hidden"), true);
        });
        registrar.playToClient(Data.TYPE, Data.CODEC, (data, context) -> {
            if (data.data != null && context.player().containerMenu instanceof StorageMenu menu && menu.containerId == data.menuId) menu.applyData(data.data, context.player().registryAccess());
        });
        registrar.playToServer(Command.TYPE, Command.CODEC, (command, context) -> {
            if (context.player() instanceof ServerPlayer player && StorageRequestBudget.acquire(player)
                    && player.containerMenu instanceof StorageMenu menu && menu.containerId == command.menuId && menu.stillValid(player))
                menu.command(player, command.action, command.target, command.value);
        });
    }
    private StoragePackets() {}
}
