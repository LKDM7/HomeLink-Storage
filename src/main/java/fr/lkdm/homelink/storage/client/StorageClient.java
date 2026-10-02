package fr.lkdm.homelink.storage.client;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = HomeLinkStorage.MOD_ID, value = Dist.CLIENT)
public final class StorageClient {
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event) {
        event.register(StorageRegistries.STORAGE_MENU.get(), StorageScreen::new);
        event.register(StorageRegistries.DEPOSIT_MENU.get(), fr.lkdm.homelink.storage.client.screen.DepositScreen::new);
    }

    /** Cargo pictures and status lights inside the transparent pipes. */
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(StorageRegistries.PIPE_ENTITY.get(), fr.lkdm.homelink.storage.client.logistics.PipeRenderer::new);
    }
}
