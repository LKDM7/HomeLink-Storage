package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** End-to-end terminal snapshot, delta and local filter assertions. */
public final class TerminalChecks {
    private static final BlockPos CONTROLLER = new BlockPos(0, 5, 0);
    private static final BlockPos CHEST = new BlockPos(0, 5, 3);
    private static final BlockPos BARREL = new BlockPos(4, 5, 3);
    private static final BlockPos LINK = new BlockPos(7, 5, 7);
    private static volatile String minerals;
    private static long initialRevision;
    private static Object unchangedIronRow;
    private static int managementStep;
    private static int managementTicks;
    private static String managementLink;
    private static String managementZone;

    public static void prepare(ServerPlayer player, BlockPos terminalPos) {
        var level = player.serverLevel();
        StorageBlockEntity controller = (StorageBlockEntity) level.getBlockEntity(CONTROLLER);
        check(controller != null, "Terminal fixture controller missing");
        controller.setLogicalName("Smoke storage");
        minerals = controller.createZone("Smoke minerals");
        level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState());
        Container chest = (Container) level.getBlockEntity(CHEST);
        Container barrel = (Container) level.getBlockEntity(BARREL);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 47));
        for (int slot = 1; slot <= 2; slot++) {
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("Fixture sword " + slot));
            chest.setItem(slot, sword);
        }
        barrel.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
        barrel.setItem(1, new ItemStack(Items.IRON_INGOT, 64));
        level.setBlockAndUpdate(LINK, StorageRegistries.LINK.get().defaultBlockState().setValue(StorageBlock.TARGET, Direction.NORTH));
        StorageBlockEntity link = (StorageBlockEntity) level.getBlockEntity(LINK);
        link.setOwner(player.getUUID());
        check(link.bind(controller), "Terminal chunk Link binding failed");
        StorageBlockEntity terminal = (StorageBlockEntity) level.getBlockEntity(terminalPos);
        check(terminal != null && terminal.bind(controller), "Terminal controller binding failed");
        controller.refreshConnections();
        controller.refreshIndex();
        var chestConnection = controller.connections().values().stream().filter(connection -> connection.inventoryPos.equals(CHEST)).findFirst().orElseThrow();
        var barrelConnection = controller.connections().values().stream().filter(connection -> connection.inventoryPos.equals(BARREL)).findFirst().orElseThrow();
        controller.setInventoryName(chestConnection.linkId, "Rare Minerals");
        controller.setZone(chestConnection.linkId, minerals);
        controller.setInventoryName(barrelConnection.linkId, "Iron Barrel");
        check(controller.index().totalItems() == 177, "Terminal fixture index mismatch");
        StorageBlockEntity restored = new StorageBlockEntity(CONTROLLER, controller.getBlockState());
        restored.loadWithComponents(controller.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        check("Smoke minerals".equals(restored.zones().get(minerals)), "Custom zone did not survive NBT reload");
        check(restored.connections().values().stream().anyMatch(connection -> connection.name.equals("Rare Minerals") && connection.zone.equals(minerals)), "Inventory name/zone did not survive NBT reload");
    }

    public static boolean snapshotReady(StorageMenu menu) {
        return menu.clientStats().connected() && menu.clientStats().items() == 177 && menu.clientRows().size() == 4;
    }

    public static void verifySnapshot(StorageMenu menu, StorageScreen screen) {
        check(menu.clientStats().unique() == 3 && menu.clientStats().inventories() == 2, "Terminal statistics mismatch");
        check(menu.clientStats().occupied() == 5 && menu.clientStats().slots() == 54, "Terminal capacity mismatch");
        var diamond = menu.clientRows().stream().filter(row -> row.stack().is(Items.DIAMOND)).findFirst().orElseThrow();
        check(diamond.count() == 47 && diamond.locations().get(CHEST) == 47L, "Terminal item location quantity mismatch");
        check(menu.clientLocations().stream().anyMatch(location -> location.position().equals(CHEST)
                && location.name().equals("Rare Minerals") && location.zone().equals(minerals)), "Inventory name or zone missing in snapshot");
        initialRevision = menu.clientRevision();
        unchangedIronRow = menu.clientRows().stream().filter(row -> row.stack().is(Items.IRON_INGOT)).findFirst().orElseThrow();
        screen.setSearchForTest("minecraft:diamond");
        check(screen.displayedRowCount() > 0, "Registry-ID search did not match diamond");
        screen.setSearchForTest("missing_fixture_item_8675309");
        check(screen.displayedRowCount() == 0, "Nonmatching search retained rows");
        screen.setZoneForTest(minerals);
        screen.setSearchForTest("minecraft:iron_ingot");
        check(screen.displayedRowCount() == 0, "Zone filter leaked another zone's items");
        screen.setSearchForTest("minecraft:diamond");
        check(screen.displayedRowCount() > 0, "Zone filter hid its own items");
        screen.setZoneForTest("");
        screen.setSearchForTest("minecraft:iron_ingot");
        check(screen.displayedRowCount() == 1, "All-zones filter failed to restore iron");
        screen.setSearchForTest("");
    }

    public static void mutate(ServerPlayer player) {
        ((Container) player.serverLevel().getBlockEntity(CHEST)).setItem(0, new ItemStack(Items.DIAMOND, 31));
        ((StorageBlockEntity) player.serverLevel().getBlockEntity(CONTROLLER)).refreshIndex();
    }

    public static boolean deltaReady(StorageMenu menu) {
        return menu.clientStats().items() == 161 && menu.clientRevision() > initialRevision
                && menu.clientRows().stream().anyMatch(row -> row.stack().is(Items.DIAMOND) && row.count() == 31);
    }

    public static void verifyDelta(StorageMenu menu, StorageScreen screen) {
        var diamond = menu.clientRows().stream().filter(row -> row.stack().is(Items.DIAMOND)).findFirst().orElseThrow();
        check(diamond.count() == 31 && diamond.locations().get(CHEST) == 31L, "Client delta did not replace changed quantities");
        check(menu.clientRows().stream().anyMatch(row -> row == unchangedIronRow), "Delta replaced an unchanged cached row");
        screen.setSearchForTest("");
        LogUtils.getLogger().info("STORAGE_TERMINAL_CHECKS_OK initial_items=177 delta_items=161 variants=4 locations=true custom_zone=true zone_filter=true registry_search=true unchanged_row_preserved=true metadata_nbt=true");
    }

    public static boolean managementComplete(StorageMenu menu) {
        if (++managementTicks < 8) return false;
        switch (managementStep) {
            case 0 -> {
                managementLink = menu.clientLocations().stream().filter(location -> location.position().equals(CHEST)).findFirst().orElseThrow().linkId().toString();
                menu.send("rename_controller", "", "Verified storage");
                managementStep = 1;
                managementTicks = 0;
            }
            case 1 -> {
                if (!menu.clientName().equals("Verified storage")) return false;
                menu.send("create_zone", "", "Verified zone");
                managementStep = 2;
                managementTicks = 0;
            }
            case 2 -> {
                var zone = menu.clientZones().entrySet().stream().filter(entry -> entry.getValue().equals("Verified zone")).findFirst();
                if (zone.isEmpty()) return false;
                managementZone = zone.orElseThrow().getKey();
                menu.send("rename_inventory", managementLink, "Verified diamonds");
                managementStep = 3;
                managementTicks = 0;
            }
            case 3 -> {
                if (menu.clientLocations().stream().noneMatch(location -> location.linkId().toString().equals(managementLink) && location.name().equals("Verified diamonds"))) return false;
                menu.send("set_zone", managementLink, managementZone);
                managementStep = 4;
                managementTicks = 0;
            }
            case 4 -> {
                if (menu.clientLocations().stream().noneMatch(location -> location.linkId().toString().equals(managementLink) && location.zone().equals(managementZone))) return false;
                LogUtils.getLogger().info("STORAGE_MANAGEMENT_CHECKS_OK rename_controller=true create_zone=true rename_inventory=true assign_zone=true transport=true");
                managementStep = 5;
                return true;
            }
            case 5 -> { return true; }
            default -> throw new IllegalStateException("Unknown management verification step");
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private TerminalChecks() { }
}
