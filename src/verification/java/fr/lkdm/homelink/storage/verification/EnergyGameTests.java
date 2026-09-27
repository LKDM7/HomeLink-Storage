package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homecore.api.energy.EnergyApi;
import fr.lkdm.homecore.api.energy.EnergyPort;
import fr.lkdm.homecore.api.energy.EnergyRole;
import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The Controller powers its network and a Deposit its own sorting with HomeLink Energy. The other
 * checks run with free machines ({@link #free()}); this batch restores the default costs.
 */
@GameTestHolder(StorageValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EnergyGameTests {
    private static final String BATCH = "energy";
    private static final UUID OWNER = UUID.fromString("7d1f4c2e-9a53-4b8e-a1f0-3c2b5d6e7f80");
    private static final List<ModConfigSpec.IntValue> COSTS = List.of(StorageConfig.CONTROLLER_ENERGY,
            StorageConfig.INVENTORY_ENERGY, StorageConfig.DEPOSIT_ENERGY);

    private EnergyGameTests() {
    }

    /** Checks written before machines needed HE build networks without any power source. */
    static void free() {
        for (ModConfigSpec.IntValue cost : COSTS) cost.set(0);
    }

    @BeforeBatch(batch = BATCH)
    public static void restoreCosts(ServerLevel level) {
        for (ModConfigSpec.IntValue cost : COSTS) cost.set(cost.getDefault());
    }

    @AfterBatch(batch = BATCH)
    public static void freeAgain(ServerLevel level) {
        free();
    }

    private static EnergyPort port(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getCapability(EnergyApi.BLOCK, helper.absolutePos(pos), Direction.UP);
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 100)
    public static void controllerAndDepositTakeEnergy(GameTestHelper helper) {
        BlockPos controller = new BlockPos(1, 1, 1);
        BlockPos deposit = new BlockPos(3, 1, 1);
        BlockPos terminal = new BlockPos(5, 1, 1);
        helper.setBlock(controller, StorageRegistries.CONTROLLER.get());
        helper.setBlock(deposit, StorageRegistries.DEPOSIT.get());
        helper.setBlock(terminal, StorageRegistries.TERMINAL.get());
        for (BlockPos pos : new BlockPos[]{controller, deposit}) {
            EnergyPort port = port(helper, pos);
            helper.assertTrue(port != null && port.role() == EnergyRole.CONSUMER && !port.type().canSend(), "No HE input at " + pos);
        }
        helper.assertTrue(port(helper, terminal) == null, "A Terminal is powered by its Controller, not directly");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = BATCH, timeoutTicks = 200)
    public static void networkStopsWithoutEnergy(GameTestHelper helper) {
        BlockPos controllerPos = new BlockPos(1, 1, 1);
        BlockPos depositPos = new BlockPos(3, 1, 1);
        helper.setBlock(controllerPos, StorageRegistries.CONTROLLER.get());
        helper.setBlock(depositPos, StorageRegistries.DEPOSIT.get());
        StorageBlockEntity controller = helper.getBlockEntity(controllerPos);
        DepositBlockEntity deposit = helper.getBlockEntity(depositPos);
        controller.setOwner(OWNER);
        deposit.setOwner(OWNER);
        helper.assertTrue(deposit.bind(controller), "Cannot bind deposit");
        deposit.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(!controller.powered() && !controller.automationAvailable(), "Unpowered Controller still runs its network");
                    helper.assertTrue(deposit.status() == DepositBlockEntity.Status.NO_POWER, "Unpowered Deposit is " + deposit.status());
                })
                .thenExecute(() -> {
                    port(helper, controllerPos).insert(Long.MAX_VALUE, false);
                    port(helper, depositPos).insert(Long.MAX_VALUE, false);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(controller.powered(), "Powered Controller still stopped");
                    helper.assertTrue(deposit.status() != DepositBlockEntity.Status.NO_POWER, "Powered Deposit still without power");
                })
                .thenSucceed();
    }
}
