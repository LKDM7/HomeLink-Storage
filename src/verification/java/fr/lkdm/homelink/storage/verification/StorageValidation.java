package fr.lkdm.homelink.storage.verification;

import net.neoforged.fml.common.Mod;

/** Development-only checks; this source set is excluded from the shipped mod. */
@Mod(StorageValidation.MOD_ID)
public final class StorageValidation {
    public static final String MOD_ID = "storage_validation";
    public StorageValidation(net.neoforged.bus.api.IEventBus bus) {
        bus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) ->
                event.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                        (level, pos, state, entity, side) -> DepositCapabilityChecks.HANDLERS.get(pos),
                        net.minecraft.world.level.block.Blocks.OAK_SIGN));
        // The checks build networks without any power source; EnergyGameTests covers the HE requirement.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.server.ServerStartedEvent event) -> EnergyGameTests.free());
    }
}
