package fr.lkdm.homelink.storage;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import fr.lkdm.homelink.storage.config.StorageConfig;

@Mod(HomeLinkStorage.MOD_ID)
public final class HomeLinkStorage {
    public static final String MOD_ID = "homelink_storage";

    public HomeLinkStorage(IEventBus bus, ModContainer container) {
        StorageRegistries.register(bus);
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    StorageRegistries.DEPOSIT_ENTITY.get(), (entity, side) -> entity.inventory());
            // Hoppers and Links see the overflow chest as an ordinary inventory.
            event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    StorageRegistries.OVERFLOW_ENTITY.get(), (entity, side) -> entity.inventory());
        });
        container.registerConfig(ModConfig.Type.SERVER, StorageConfig.SPEC);
        // Resolve the actual API at runtime, in addition to the mandatory loader dependency.
        DashboardAPI.providers();
    }
}
