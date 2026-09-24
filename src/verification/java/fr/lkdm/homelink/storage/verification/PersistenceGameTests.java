package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Two separate JVM runs against one dedicated world verify actual disk persistence. */
@GameTestHolder(StorageValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PersistenceGameTests {
    private static final BlockPos CONTROLLER = new BlockPos(160, 70, 160);
    private static final BlockPos BARREL = CONTROLLER.east(2);
    private static final BlockPos LINK = BARREL.above();
    private static final BlockPos TERMINAL = CONTROLLER.south(2);
    private static final BlockPos DEPOSIT = CONTROLLER.south(3);
    private static final UUID OWNER = UUID.fromString("67bc182a-8265-48d2-af10-e9907b46391b");
    private static final UUID DEVICE = UUID.fromString("b468efda-9b5e-40f4-a9d1-35b1fc8a5a40");
    private static final UUID LINK_ID = UUID.fromString("54957053-7aa8-4f25-aa99-f3dbb34d0e22");

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void storageSurvivesRestart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The fixture explicitly loads its own fixed chunk; production code never does this.
        level.getChunkAt(CONTROLLER);
        String pass = System.getProperty("storage.persistencePass", "");
        if (pass.equals("write")) {
            for (BlockPos pos : new BlockPos[]{LINK, TERMINAL, DEPOSIT, BARREL, CONTROLLER}) level.destroyBlock(pos, false);
            StorageBlockEntity controller = place(level, CONTROLLER, StorageRegistries.CONTROLLER.get(), DEVICE);
            controller.setLogicalName("Persistent storage");
            controller.ensureHomeCore();
            String zone = controller.createZone("Persistent minerals");
            level.setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState());
            ((Container) level.getBlockEntity(BARREL)).setItem(0, new ItemStack(Items.DIAMOND, 64));
            StorageBlockEntity link = place(level, LINK, StorageRegistries.LINK.get(), LINK_ID);
            helper.assertTrue(link.bind(controller), "Persistence link binding failed");
            var inventory = controller.connections().values().stream().filter(c -> c.inventoryPos.equals(BARREL)).findFirst().orElseThrow();
            controller.setInventoryName(inventory.linkId, "Persistent diamonds");
            controller.setZone(inventory.linkId, zone);
            StorageBlockEntity terminal = place(level, TERMINAL, StorageRegistries.TERMINAL.get(), UUID.fromString("768ad01c-6eb5-4120-a502-9bdfc06a4dfe"));
            helper.assertTrue(terminal.bind(controller), "Persistence terminal binding failed");
            var deposit = (fr.lkdm.homelink.storage.blockentity.DepositBlockEntity) place(level, DEPOSIT,
                    StorageRegistries.DEPOSIT.get(), UUID.fromString("4b1b2036-adbb-47e7-86b0-bb9bbc8b8cf1"));
            helper.assertTrue(deposit.bind(controller), "Persistence deposit binding failed");
            deposit.inventory().setStackInSlot(8, new ItemStack(Items.EMERALD, 5));
            controller.refreshIndex();
            var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                    new com.mojang.authlib.GameProfile(OWNER, "StoragePersistence"));
            player.getInventory().clearContent();
            player.setPos(CONTROLLER.getX(), CONTROLLER.getY(), CONTROLLER.getZ());
            long row = controller.index().entries().iterator().next().id();
            helper.assertTrue(fr.lkdm.homelink.storage.storage.inventory.StorageWithdrawal.withdraw(player, controller,
                    inventory.linkId, row, 1) == 1 && player.getInventory().countItem(Items.DIAMOND) == 1
                    && controller.index().totalItems() == 63, "Pre-save withdrawal did not conserve objects");
            level.getServer().saveEverything(false, true, true);
            LogUtils.getLogger().info("STORAGE_PERSISTENCE_WRITE_OK device={} network={} items={}", DEVICE, controller.networkId(), controller.index().totalItems());
            helper.succeed();
        } else if (pass.equals("read")) {
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(level.getBlockEntity(CONTROLLER) instanceof StorageBlockEntity, "Controller not saved by prior process");
                StorageBlockEntity controller = (StorageBlockEntity) level.getBlockEntity(CONTROLLER);
                helper.assertTrue(DEVICE.equals(controller.id()) && OWNER.equals(controller.owner()), "Controller identity/owner changed across restart");
                helper.assertTrue("Persistent storage".equals(controller.logicalName()), "Controller name not persisted");
                helper.assertTrue(controller.networkId() != null, "HomeNetwork association missing after restart");
                var home = DashboardAPI.networks(level.getServer()).getNetwork(controller.networkId()).orElseThrow();
                helper.assertTrue(home.owner().equals(OWNER) && home.devices().contains(DEVICE), "HomeCore persistent membership missing");
                controller.ensureHomeCore();
                helper.assertTrue(DashboardAPI.devices(level.getServer()).get(DEVICE).isPresent(), "HomeCore device not re-registered");
                StorageBlockEntity link = (StorageBlockEntity) level.getBlockEntity(LINK);
                StorageBlockEntity terminal = (StorageBlockEntity) level.getBlockEntity(TERMINAL);
                helper.assertTrue(link != null && link.id().equals(LINK_ID) && link.controller() == controller, "Link identity/binding not persisted");
                helper.assertTrue(terminal != null && terminal.controller() == controller, "Terminal binding not persisted");
                var deposit = (fr.lkdm.homelink.storage.blockentity.DepositBlockEntity) level.getBlockEntity(DEPOSIT);
                helper.assertTrue(deposit != null && deposit.controller() == controller && OWNER.equals(deposit.owner())
                        && deposit.inventory().getStackInSlot(8).is(Items.EMERALD)
                        && deposit.inventory().getStackInSlot(8).getCount() == 5,
                        "Deposit content, owner or binding did not survive real restart");
                var connection = controller.connections().values().stream().filter(c -> c.inventoryPos.equals(BARREL)).findFirst().orElseThrow();
                helper.assertTrue(connection != null && connection.name.equals("Persistent diamonds"), "Inventory name/registration missing");
                helper.assertTrue("Persistent minerals".equals(controller.zones().get(connection.zone)), "Custom zone missing");
                controller.refreshIndex();
                helper.assertTrue(controller.index().totalItems() == 63 && controller.index().inventoryCount() == 1, "Index was not rebuilt from saved inventory");
                LogUtils.getLogger().info("STORAGE_PERSISTENCE_READ_OK device={} network={} items=63", DEVICE, controller.networkId());
                helper.succeed();
            });
        } else helper.fail("Set storage.persistencePass to write or read");
    }

    private static StorageBlockEntity place(ServerLevel level, BlockPos pos, Block block, UUID identity) {
        var state = block.defaultBlockState();
        if (state.hasProperty(StorageBlock.TARGET)) state = state.setValue(StorageBlock.TARGET, Direction.DOWN);
        level.setBlockAndUpdate(pos, state);
        StorageBlockEntity entity = (StorageBlockEntity) level.getBlockEntity(pos);
        CompoundTag saved = entity.saveWithFullMetadata(level.registryAccess());
        saved.putUUID("Id", identity);
        entity.loadWithComponents(saved, level.registryAccess());
        entity.setOwner(OWNER);
        return entity;
    }
}
