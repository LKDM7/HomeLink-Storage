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
    public record Command(int menuId, String action, String target, String value) implements CustomPacketPayload {
        public static final Type<Command> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(HomeLinkStorage.MOD_ID, "command"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> CODEC = StreamCodec.of(
                (buf, value) -> { buf.writeVarInt(value.menuId); buf.writeUtf(value.action, 32); buf.writeUtf(value.target, 64); buf.writeUtf(value.value, 64); },
                buf -> new Command(buf.readVarInt(), buf.readUtf(32), buf.readUtf(64), buf.readUtf(64)));
        @Override public Type<Command> type() { return TYPE; }
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
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
