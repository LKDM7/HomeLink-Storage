package fr.lkdm.homelink.storage.verification;

import com.mojang.authlib.GameProfile;
import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server inventories and capabilities, without a second routing implementation. */
@GameTestHolder(StorageValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DepositGameTests {
    private static final UUID OWNER = UUID.fromString("53f272dc-62b9-442c-9265-cf8fd9f51a21");

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void depositRoutingAndConservation(GameTestHelper helper) {
        Fixture f = new Fixture(helper, new BlockPos(320, 80, 320));
        try {
            f.deposit.inventory().setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 64, "Unbound deposit lost items");
            check(helper, f.deposit.status() == DepositBlockEntity.Status.NOT_CONNECTED, "Missing unbound status");
            check(helper, f.deposit.bind(f.controller), "Cannot bind deposit");
            f.first.setItem(0, new ItemStack(Items.IRON_INGOT, 1));
            f.controller.refreshIndex();
            tick(f.deposit, 19);
            check(helper, count(f.deposit) == 64, "Sorted faster than twenty ticks");
            tick(f.deposit, 1);
            check(helper, count(f.deposit) == 0 && f.first.countItem(Items.IRON_INGOT) == 65, "Matching transfer did not conserve items");
            f.deposit.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 5));
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 5, "Unmatched item was routed");
            check(helper, f.deposit.status() == DepositBlockEntity.Status.BLOCKED, "Unmatched item did not report blocked");
            f.first.setItem(1, new ItemStack(Items.DIAMOND, 1));
            f.controller.refreshIndex();
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 0 && f.first.countItem(Items.DIAMOND) == 6, "Manual seed did not enable routing");

            fill(f.first, Items.IRON_INGOT, 64);
            fill(f.second, Items.IRON_INGOT, 64);
            f.deposit.inventory().setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 64));
            f.controller.refreshIndex();
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 64, "Full destinations lost items");
            f.second.setItem(0, new ItemStack(Items.IRON_INGOT, 41));
            f.controller.refreshIndex();
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 41 && f.second.getItem(0).getCount() == 64, "Full-first / partial-second transfer incorrect");

            f.first.clearContent(); f.second.clearContent();
            f.first.setItem(0, new ItemStack(Items.GOLD_INGOT, 1));
            f.deposit.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 5));
            f.deposit.inventory().setStackInSlot(1, new ItemStack(Items.GOLD_INGOT, 64));
            f.deposit.inventory().setStackInSlot(2, new ItemStack(Items.GOLD_INGOT, 64));
            f.controller.refreshIndex();
            int before = f.first.countItem(Items.GOLD_INGOT);
            for (int i = 0; i < 3; i++) {
                tick(f.deposit, 20);
                int after = f.first.countItem(Items.GOLD_INGOT);
                check(helper, after - before <= 64, "More than one stack processed per attempt");
                before = after;
            }
            check(helper, count(f.deposit) == 5 && before == 129, "Blocked slot starved subsequent slots");
            f.deposit.inventory().setStackInSlot(0, ItemStack.EMPTY);

            ItemStack named = new ItemStack(Items.IRON_INGOT, 8);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Distinct component"));
            f.first.clearContent(); f.first.setItem(0, new ItemStack(Items.IRON_INGOT, 1));
            f.deposit.inventory().setStackInSlot(0, named);
            f.controller.refreshIndex(); tick(f.deposit, 20);
            check(helper, count(f.deposit) == 8, "Different components were merged");
            f.first.setItem(1, named.copyWithCount(1));
            f.controller.refreshIndex(); tick(f.deposit, 20);
            check(helper, count(f.deposit) == 0 && f.first.getItem(1).getCount() == 9, "Identical components did not merge");

            ItemStack modded = StorageRegistries.LINK_KEY.get().getDefaultInstance();
            f.first.clearContent(); f.first.setItem(0, modded.copy());
            f.deposit.inventory().setStackInSlot(0, modded.copy());
            f.controller.refreshIndex(); tick(f.deposit, 20);
            check(helper, count(f.deposit) == 0 && f.first.countItem(modded.getItem()) == 2, "Modded item routing failed");

            f.first.clearContent(); f.first.setItem(0, new ItemStack(Items.EMERALD, 1));
            f.deposit.inventory().setStackInSlot(0, new ItemStack(Items.EMERALD, 5));
            f.controller.refreshIndex();
            f.first.clearContent(); // Deliberately leave the controller index stale.
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 5 && f.first.isEmpty(), "Stale matching row seeded an empty destination");
            f.first.setItem(0, new ItemStack(Items.EMERALD, 1));
            f.controller.refreshIndex();
            f.level.removeBlock(f.base.above(), false);
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 5 && f.first.countItem(Items.EMERALD) == 1, "Destroyed Storage Link remained routable");
            f.level.setBlockAndUpdate(f.base.above(), StorageRegistries.LINK.get().defaultBlockState());
            ((StorageBlockEntity) f.level.getBlockEntity(f.base.above())).bind(f.controller);

            f.first.clearContent(); f.first.setItem(0, new ItemStack(Items.EMERALD, 1));
            f.deposit.inventory().setStackInSlot(0, new ItemStack(Items.EMERALD, 5));
            f.controller.refreshIndex();
            f.level.removeBlock(f.base.east(2), false);
            tick(f.deposit, 20);
            check(helper, count(f.deposit) == 5, "Destroyed destination lost items through stale index");
            var savedController = f.controller.saveWithFullMetadata(f.level.registryAccess());
            f.level.removeBlock(f.base, false); tick(f.deposit, 20);
            check(helper, count(f.deposit) == 5 && f.deposit.controller() == null, "Destroyed controller did not stop sorting");
            check(helper, f.deposit.status() == DepositBlockEntity.Status.CONTROLLER_OFFLINE, "Missing controller did not report offline");
            f.level.setBlockAndUpdate(f.base, StorageRegistries.CONTROLLER.get().defaultBlockState());
            StorageBlockEntity restored = (StorageBlockEntity) f.level.getBlockEntity(f.base);
            restored.loadWithComponents(savedController, f.level.registryAccess());
            f.second.setItem(0, new ItemStack(Items.EMERALD, 1));
            restored.refreshIndex(); tick(f.deposit, 20);
            check(helper, f.deposit.controller() == restored && count(f.deposit) == 0, "Restored controller did not resume");
            var remote = new BlockPos(2_000_000, 80, 2_000_000);
            check(helper, !f.level.hasChunkAt(remote), "Unloaded fixture unexpectedly loaded");
            check(helper, StorageInventoryAdapter.resolveAny(f.level, remote).status() == StorageInventoryAdapter.Status.UNLOADED
                    && !f.level.hasChunkAt(remote), "Inventory resolution forced chunk loading");
            helper.succeed();
        } finally { f.clear(); }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void depositConnectorMenuPersistenceAndDrops(GameTestHelper helper) {
        Fixture f = new Fixture(helper, new BlockPos(384, 80, 384));
        try {
            var player = FakePlayerFactory.get(f.level, new GameProfile(OWNER, "DepositVerification"));
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, StorageRegistries.LINK_KEY.get().getDefaultInstance());
            for (BlockPos pos : new BlockPos[]{f.base, f.deposit.getBlockPos()}) {
                player.setPos(pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5);
                player.gameMode.useItemOn(player, f.level, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                        new BlockHitResult(pos.getCenter(), Direction.UP, pos, false));
            }
            check(helper, f.deposit.controller() == f.controller, "Existing Link Key did not bind deposit");
            player.getInventory().clearContent();
            player.getInventory().setItem(9, new ItemStack(Items.EMERALD, 32));
            var menu = f.deposit.createMenu(1, player.getInventory(), player);
            menu.quickMoveStack(player, 27);
            check(helper, count(f.deposit) == 32 && player.getInventory().countItem(Items.EMERALD) == 0, "Player shift-click failed");
            check(helper, f.deposit.comparatorSignal() > 0 && f.deposit.comparatorSignal() < 15, "Partial deposit comparator incorrect");
            var visitor = FakePlayerFactory.get(f.level, new GameProfile(UUID.fromString("fd6578b8-4aa8-45c6-887a-d33e1b13b330"), "DepositVisitor"));
            visitor.setPos(player.getX(), player.getY(), player.getZ());
            check(helper, !menu.stillValid(visitor), "Another player's menu access bypassed owner permissions");
            f.controller.ensureHomeCore();
            fr.lkdm.homecore.api.DashboardAPI.networks(player.server).setMember(f.controller.networkId(), visitor.getUUID(),
                    fr.lkdm.homecore.api.network.NetworkRole.VIEWER);
            check(helper, f.controller.canAccess(visitor), "HomeCore VIEWER fixture cannot inspect controller");
            check(helper, !f.deposit.canAccess(visitor) && !menu.stillValid(visitor), "HomeCore VIEWER bypassed deposit CONTROL permission");
            visitor.getInventory().setItem(9, new ItemStack(Items.EMERALD, 4));
            check(helper, menu.quickMoveStack(visitor, 0).isEmpty() && count(f.deposit) == 32,
                    "HomeCore VIEWER extracted deposit items");
            menu.removed(player);
            check(helper, count(f.deposit) == 32, "Closing menu changed inventory");
            var saved = f.deposit.saveWithFullMetadata(f.level.registryAccess());
            f.deposit.inventory().setStackInSlot(0, ItemStack.EMPTY);
            f.deposit.loadWithComponents(saved, f.level.registryAccess());
            check(helper, count(f.deposit) == 32 && f.deposit.controller() == f.controller, "Inventory/binding NBT persistence failed");
            for (int slot = 0; slot < 27; slot++) f.deposit.inventory().setStackInSlot(slot, new ItemStack(Items.EMERALD, 64));
            check(helper, f.deposit.comparatorSignal() == 15, "Full deposit comparator incorrect");
            f.deposit.loadWithComponents(saved, f.level.registryAccess());
            var handler = f.level.getCapability(Capabilities.ItemHandler.BLOCK, f.deposit.getBlockPos(), Direction.UP);
            check(helper, handler != null && handler.insertItem(0, new ItemStack(Items.EMERALD, 3), false).isEmpty()
                    && count(f.deposit) == 35, "Automation capability insertion failed");
            var secondPos = f.base.south(3);
            f.level.setBlockAndUpdate(secondPos, StorageRegistries.DEPOSIT.get().defaultBlockState());
            var second = (DepositBlockEntity) f.level.getBlockEntity(secondPos);
            second.setOwner(OWNER); second.bind(f.controller);
            f.first.setItem(0, new ItemStack(Items.EMERALD, 1));
            second.inventory().setStackInSlot(0, new ItemStack(Items.EMERALD, 7));
            f.controller.refreshIndex(); tick(f.deposit, 20); tick(second, 20);
            check(helper, count(f.deposit) == 0 && count(second) == 0 && f.first.countItem(Items.EMERALD) == 43,
                    "Multiple deposits failed conservation");
            check(helper, f.deposit.comparatorSignal() == 0, "Empty deposit comparator incorrect");
            f.deposit.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 13));
            f.level.destroyBlock(f.deposit.getBlockPos(), true);
            int dropped = f.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(f.deposit.getBlockPos()).inflate(2)).stream()
                    .filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
            check(helper, dropped == 13, "Breaking deposit did not drop exactly its contents");
            helper.succeed();
        } finally { f.clear(); }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void hopperFeedsDeposit(GameTestHelper helper) {
        Fixture f = new Fixture(helper, new BlockPos(448, 80, 448));
        // Test-only ticking ticket: the fixed fixture is outside the GameTest structure.
        f.level.setChunkForced(f.base.getX() >> 4, f.base.getZ() >> 4, true);
        BlockPos hopper = f.deposit.getBlockPos().above();
        f.level.setBlockAndUpdate(hopper, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.DOWN));
        ((Container) f.level.getBlockEntity(hopper)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        helper.runAfterDelay(40, () -> {
            try {
                check(helper, count(f.deposit) == 3 && ((Container) f.level.getBlockEntity(hopper)).isEmpty(), "Vanilla hopper did not feed deposit");
                helper.succeed();
            } finally {
                f.level.removeBlock(hopper, false); f.clear();
                f.level.setChunkForced(f.base.getX() >> 4, f.base.getZ() >> 4, false);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void indexedDestinationAndControllerActuallyUnload(GameTestHelper helper) {
        Fixture destinations = new Fixture(helper, new BlockPos(512, 80, 512));
        Fixture controllers = new Fixture(helper, new BlockPos(576, 80, 576));
        ServerLevel level = helper.getLevel();
        BlockPos remote = destinations.base.east(512);
        BlockPos survivingDepositPos = controllers.base.south(512);
        level.setChunkForced(destinations.base.getX() >> 4, destinations.base.getZ() >> 4, true);
        level.setChunkForced(survivingDepositPos.getX() >> 4, survivingDepositPos.getZ() >> 4, true);
        level.getChunkAt(remote);
        level.setBlockAndUpdate(remote, Blocks.BARREL.defaultBlockState());
        ((Container) level.getBlockEntity(remote)).setItem(0, new ItemStack(Items.DIAMOND, 1));
        level.setBlockAndUpdate(remote.above(), StorageRegistries.LINK.get().defaultBlockState());
        ((StorageBlockEntity) level.getBlockEntity(remote.above())).bind(destinations.controller);
        destinations.deposit.bind(destinations.controller);
        destinations.controller.refreshIndex();
        check(helper, destinations.controller.index().totalItems() == 1, "Remote fixture was not indexed before unload");
        level.setBlockAndUpdate(survivingDepositPos, StorageRegistries.DEPOSIT.get().defaultBlockState());
        DepositBlockEntity survivingDeposit = (DepositBlockEntity) level.getBlockEntity(survivingDepositPos);
        survivingDeposit.setOwner(OWNER); survivingDeposit.bind(controllers.controller);
        survivingDeposit.inventory().setStackInSlot(0, new ItemStack(Items.EMERALD, 7));
        // No chunk tickets remain for the remote destination or second controller.
        // Allow vanilla's chunk scheduler to perform an actual unload.
        helper.runAfterDelay(700, () -> {
            try {
                check(helper, !level.hasChunkAt(remote), "Remote destination chunk did not unload");
                destinations.deposit.inventory().setStackInSlot(0, new ItemStack(Items.DIAMOND, 5));
                tick(destinations.deposit, 20);
                check(helper, count(destinations.deposit) == 5 && !level.hasChunkAt(remote), "Indexed unloaded destination was loaded or lost items");
                check(helper, !level.hasChunkAt(controllers.base), "Controller chunk did not unload");
                tick(survivingDeposit, 20);
                check(helper, count(survivingDeposit) == 7 && survivingDeposit.status() == DepositBlockEntity.Status.CONTROLLER_OFFLINE
                        && !level.hasChunkAt(controllers.base), "Unloaded controller was loaded or lost items");
                level.getChunkAt(controllers.base); // Explicit test-driven reload, never production behavior.
                tick(survivingDeposit, 20);
                check(helper, survivingDeposit.controller() != null && count(survivingDeposit) == 7, "Controller binding did not survive actual chunk reload");
                level.getChunkAt(remote);
                destinations.controller.refreshIndex(); tick(destinations.deposit, 20);
                check(helper, count(destinations.deposit) == 0 && ((Container) level.getBlockEntity(remote)).countItem(Items.DIAMOND) == 6,
                        "Routing did not resume after actual destination reload");
                helper.succeed();
            } finally {
                level.removeBlock(remote.above(), false); level.removeBlock(remote, false);
                level.removeBlock(survivingDepositPos, false);
                destinations.clear(); controllers.clear();
                level.setChunkForced(destinations.base.getX() >> 4, destinations.base.getZ() >> 4, false);
                level.setChunkForced(survivingDepositPos.getX() >> 4, survivingDepositPos.getZ() >> 4, false);
            }
        });
    }

    private static void tick(DepositBlockEntity deposit, int times) {
        for (int i = 0; i < times; i++) DepositBlockEntity.tick(deposit.getLevel(), deposit.getBlockPos(), deposit.getBlockState(), deposit);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void depositItemPlacementBreakAndReplace(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = new BlockPos(704, 80, 704);
        level.getChunkAt(pos);
        var player = FakePlayerFactory.get(level, new GameProfile(OWNER, "DepositVerification"));
        try {
            level.removeBlock(pos, false);
            level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
            player.setPos(pos.getX() + 2.5, pos.getY(), pos.getZ() + .5);
            player.setItemInHand(InteractionHand.MAIN_HAND, StorageRegistries.DEPOSIT.get().asItem().getDefaultInstance());
            var hit = new BlockHitResult(pos.getCenter().add(0, -.5, 0), Direction.UP, pos.below(), false);
            player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            check(helper, level.getBlockEntity(pos) instanceof DepositBlockEntity, "Deposit block item did not place");
            var placed = (DepositBlockEntity) level.getBlockEntity(pos);
            check(helper, OWNER.equals(placed.owner()) && placed.canAccess(player), "Placement did not assign accessible owner");
            placed.inventory().setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 9));
            level.destroyBlock(pos, true);
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(pos).inflate(2));
            check(helper, drops.stream().filter(e -> e.getItem().is(Items.IRON_INGOT)).mapToInt(e -> e.getItem().getCount()).sum() == 9,
                    "Placed deposit did not return exact contents");
            var blockDrops = drops.stream().filter(e -> e.getItem().is(StorageRegistries.DEPOSIT.get().asItem())).toList();
            check(helper, blockDrops.size() == 1 && blockDrops.getFirst().getItem().getCount() == 1, "Deposit loot table did not drop one block item");
            player.setItemInHand(InteractionHand.MAIN_HAND, blockDrops.getFirst().getItem().copy());
            blockDrops.getFirst().discard();
            player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            check(helper, level.getBlockEntity(pos) instanceof DepositBlockEntity replacement && count(replacement) == 0
                    && OWNER.equals(replacement.owner()), "Replacing dropped deposit duplicated inventory or lost ownership");
            helper.succeed();
        } finally {
            level.removeBlock(pos, false); level.removeBlock(pos.below(), false);
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(pos).inflate(3)).forEach(net.minecraft.world.entity.Entity::discard);
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }
    private static int count(DepositBlockEntity deposit) {
        int count = 0;
        for (int i = 0; i < deposit.inventory().getSlots(); i++) count += deposit.inventory().getStackInSlot(i).getCount();
        return count;
    }
    private static void fill(Container container, net.minecraft.world.item.Item item, int amount) {
        for (int i = 0; i < container.getContainerSize(); i++) container.setItem(i, new ItemStack(item, amount));
    }
    private static void check(GameTestHelper helper, boolean condition, String message) { helper.assertTrue(condition, message); }
    private static final class Fixture {
        final ServerLevel level; final BlockPos base;
        final StorageBlockEntity controller; final DepositBlockEntity deposit;
        final Container first; final Container second;
        Fixture(GameTestHelper helper, BlockPos base) {
            this.level = helper.getLevel(); this.base = base;
            level.getChunkAt(base); clear();
            level.setBlockAndUpdate(base, StorageRegistries.CONTROLLER.get().defaultBlockState());
            controller = (StorageBlockEntity) level.getBlockEntity(base); controller.setOwner(OWNER);
            for (int x : new int[]{2, 3}) level.setBlockAndUpdate(base.east(x), Blocks.BARREL.defaultBlockState());
            first = (Container) level.getBlockEntity(base.east(2)); second = (Container) level.getBlockEntity(base.east(3));
            level.setBlockAndUpdate(base.above(), StorageRegistries.LINK.get().defaultBlockState());
            ((StorageBlockEntity) level.getBlockEntity(base.above())).setOwner(OWNER);
            check(helper, ((StorageBlockEntity) level.getBlockEntity(base.above())).bind(controller), "Fixture link failed");
            level.setBlockAndUpdate(base.south(2), StorageRegistries.DEPOSIT.get().defaultBlockState());
            deposit = (DepositBlockEntity) level.getBlockEntity(base.south(2)); deposit.setOwner(OWNER);
        }
        void clear() {
            for (BlockPos pos : new BlockPos[]{base.above(), base.south(2), base.south(3), base.east(2), base.east(3), base}) level.removeBlock(pos, false);
            level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(base).inflate(5)).forEach(net.minecraft.world.entity.Entity::discard);
        }
    }
}
