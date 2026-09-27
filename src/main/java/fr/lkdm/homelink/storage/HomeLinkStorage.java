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
                    StorageRegistries.DEPOSIT_ENTITY.get(), (entity, side) -> depositOpen(entity, side) ? entity.inventory() : null);
            // Hoppers and Links see the overflow chest as an ordinary inventory.
            event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    StorageRegistries.OVERFLOW_ENTITY.get(), (entity, side) -> entity.inventory());
            // HomeLink Energy: the Controller powers its network, a Deposit powers its own sorting.
            event.registerBlockEntity(fr.lkdm.homecore.api.energy.EnergyApi.BLOCK, StorageRegistries.STORAGE_ENTITY.get(), (entity, side) -> entity.energyPort());
            event.registerBlockEntity(fr.lkdm.homecore.api.energy.EnergyApi.BLOCK, StorageRegistries.DEPOSIT_ENTITY.get(), (entity, side) -> entity.energyPort());
        });
        container.registerConfig(ModConfig.Type.SERVER, StorageConfig.SPEC);
        // Resolve the actual API at runtime, in addition to the mandatory loader dependency.
        DashboardAPI.providers();
    }

    /**
     * Hoppers and pipes reach the Deposit through its top, back and side ports; the front (screen)
     * and the bottom stay closed. A null side is the neutral access.
     */
    public static boolean depositOpen(fr.lkdm.homelink.storage.blockentity.DepositBlockEntity deposit,
            @org.jetbrains.annotations.Nullable net.minecraft.core.Direction side) {
        if (side == null || side == net.minecraft.core.Direction.UP) return true;
        if (side == net.minecraft.core.Direction.DOWN) return false;
        var state = deposit.getBlockState();
        return state.hasProperty(fr.lkdm.homelink.storage.block.StorageBlock.TARGET)
                && side != state.getValue(fr.lkdm.homelink.storage.block.StorageBlock.TARGET);
    }
}
