package fr.lkdm.homelink.storage.logistics.sync;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.logistics.network.PipeInteraction;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers the pipe payloads. Client handlers live in the client package and are only reached
 * when a client receives a payload, so a dedicated server never loads a client class.
 */
@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID)
public final class PipeNetworking {
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("pipes-1");
        registrar.playToClient(PipePayloads.TransitStart.TYPE, PipePayloads.TransitStart.CODEC,
                (payload, context) -> fr.lkdm.homelink.storage.client.logistics.PipeClientHandlers.start(payload));
        registrar.playToClient(PipePayloads.TransitRemove.TYPE, PipePayloads.TransitRemove.CODEC,
                (payload, context) -> fr.lkdm.homelink.storage.client.logistics.PipeClientHandlers.remove(payload));
        registrar.playToClient(PipePayloads.FaceView.TYPE, PipePayloads.FaceView.CODEC,
                (payload, context) -> fr.lkdm.homelink.storage.client.logistics.PipeClientHandlers.view(payload));
        registrar.playToClient(PipePayloads.FaceChoice.TYPE, PipePayloads.FaceChoice.CODEC,
                (payload, context) -> fr.lkdm.homelink.storage.client.logistics.PipeClientHandlers.choice(payload));
        registrar.playToClient(PipePayloads.RecoveryView.TYPE, PipePayloads.RecoveryView.CODEC,
                (payload, context) -> fr.lkdm.homelink.storage.client.logistics.PipeClientHandlers.recovery(payload));
        registrar.playToServer(PipePayloads.OpenFace.TYPE, PipePayloads.OpenFace.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) PipeInteraction.open(player, payload);
        });
        registrar.playToServer(PipePayloads.ApplyFace.TYPE, PipePayloads.ApplyFace.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) PipeInteraction.apply(player, payload);
        });
        registrar.playToServer(PipePayloads.RecoveryAction.TYPE, PipePayloads.RecoveryAction.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) PipeInteraction.recovery(player, payload);
        });
    }

    private PipeNetworking() { }
}
