package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.action.Unit;
import fr.lkdm.homecore.api.action.ActionResult;
import fr.lkdm.homecore.api.network.NetworkRole;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.dashboard.blockentity.HomeServerBlockEntity;
import fr.lkdm.homelink.dashboard.client.screen.DashboardScreen;
import fr.lkdm.homelink.dashboard.client.state.DebugDeviceView;
import fr.lkdm.homelink.dashboard.registry.DashboardRegistries;
import fr.lkdm.homelink.dashboard.menu.DashboardMenu;
import fr.lkdm.homelink.dashboard.server.DashboardAccess;
import fr.lkdm.homelink.dashboard.server.RadioNetworkService;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Compiled only with -PwithDashboard. Uses the actual Dashboard screen, action controls and live transport. */
public final class DashboardChecks {
    private static final BlockPos CONTROLLER = new BlockPos(80, 5, 0);
    private static final BlockPos BARREL = CONTROLLER.offset(2, 0, 0);
    private static final BlockPos ACCESS = CONTROLLER.offset(0, 0, 2);
    private static UUID deviceId;
    private static UUID viewerNetwork;
    private static int stage;
    private static int frames;
    private static volatile boolean done;
    private static volatile Throwable failure;
    private static int loggedStage = -1;
    private static long stageStarted;

    public static void prepare(ServerPlayer player) {
        var level = player.serverLevel();
        for (int x = 78; x <= 85; x++) for (int z = -2; z <= 6; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 4, z), Blocks.STONE.defaultBlockState());
        }
        player.teleportTo(80.5, 6, 4.5);
        level.setBlockAndUpdate(CONTROLLER, StorageRegistries.CONTROLLER.get().defaultBlockState());
        var controller = (StorageBlockEntity) level.getBlockEntity(CONTROLLER);
        controller.setOwner(player.getUUID());
        level.setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(BARREL.above(), StorageRegistries.LINK.get().defaultBlockState());
        check(((StorageBlockEntity) level.getBlockEntity(BARREL.above())).bind(controller), "Dashboard fixture Link binding");
        controller.ensureHomeCore();
        check(controller.permission(player, Permission.CONFIGURE), "Storage owner cannot configure");
        fill(player, 1);
        deviceId = controller.id();
        var stack = DashboardRegistries.HOME_SERVER.get().asItem().getDefaultInstance();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        var result = ((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(ACCESS.getBottomCenter(), Direction.UP, ACCESS.below(), false)));
        check(result.consumesAction() && level.getBlockState(ACCESS.above()).is(DashboardRegistries.HOME_SERVER.get()),
                "Dashboard HomeServer BlockItem must place both rack halves");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var access = (HomeServerBlockEntity) level.getBlockEntity(ACCESS);
        access.initializeOwner(player.getUUID());
        access.setNetworkId(controller.networkId());
        DashboardAccess.open(player, access);
        check(player.containerMenu instanceof DashboardMenu, "Real HomeServer did not open DashboardMenu");
        LogUtils.getLogger().info("STORAGE_DASHBOARD_PREPARED network={} device={} menu=true rack_halves=2 floor=true", controller.networkId(), deviceId);
    }

    public static boolean tick(Minecraft client) {
        if (failure != null) throw new IllegalStateException("Dashboard server fixture failed", failure);
        if (loggedStage != stage) {
            loggedStage = stage;
            stageStarted = System.nanoTime();
            LogUtils.getLogger().info("STORAGE_DASHBOARD_STAGE {}", stage);
        }
        check(System.nanoTime() - stageStarted < 30_000_000_000L, "Dashboard stage " + stage + " timed out; screen="
                + (client.screen == null ? "null" : client.screen.getClass().getSimpleName()));
        if (stage == 8) {
            if (!done || client.screen instanceof DashboardScreen || client.player.containerMenu instanceof DashboardMenu) return false;
            LogUtils.getLogger().info("STORAGE_DASHBOARD_CHECKS_OK real_mod=true screen=true metrics=5 owner_refresh=true warning=true event_alert=true inventory_offline_online=true viewer_view=true viewer_control_denied=true radio_offline_closes=true");
            return true;
        }
        if (!(client.screen instanceof DashboardScreen screen) || screen.state() == null || screen.state().loading()) return false;
        var state = screen.state();
        var device = state.devices().stream().filter(value -> value.id().equals(deviceId)).findFirst().orElse(null);
        if (device == null) return false;
        switch (stage) {
            case 0 -> {
                check(device.status().equals("ONLINE"), "Storage is not ONLINE in the real Dashboard");
                Set<String> expected = Set.of("capacity", "item_count", "unique_items", "inventory_count", "full_inventories");
                check(device.metrics().stream().map(metric -> metric.id().replace("homelink_storage:", "")).collect(Collectors.toSet()).equals(expected), "Dashboard did not decode all five real metrics");
                check(device.metrics().stream().allMatch(metric -> metric.value() != null && !metric.displayValue().contains("unsupported")), "Dashboard metric rendering unsupported");
                screen.showDevices();
                check(screen.explorer().select(deviceId), "Cannot select Storage in Dashboard explorer");
                stage = 1;
            }
            case 1 -> {
                if (++frames < 20) return false;
                screenshot(client, "storage-dashboard-device.png");
                screen.openActions();
                check(screen.actions().selectAction("homelink_storage:refresh_index"), "Real Dashboard action control missing");
                check(screen.actions().submit(Unit.INSTANCE), "Real Dashboard rejected owner refresh submission");
                stage = 2;
            }
            case 2 -> {
                if (state.actionPending()) return false;
                check(state.lastActionCode().equals("SUCCESS"), "Dashboard refresh response: " + state.lastActionCode());
                schedule(client, player -> fill(player, 25));
                stage = 3;
            }
            case 3 -> {
                if (!done || !device.status().equals("WARNING") || !hasAlert(screen, "storage_warning")) return false;
                check(metric(device, "item_count").equals(25L), "Dashboard item-count delta incorrect");
                screen.showAlerts();
                frames = 0;
                stage = 4;
            }
            case 4 -> {
                if (++frames < 20) return false;
                screenshot(client, "storage-dashboard-alert.png");
                schedule(client, player -> {
                    player.serverLevel().destroyBlock(BARREL, false);
                    refresh(player);
                });
                stage = 5;
            }
            case 5 -> {
                if (!done || !hasAlert(screen, "inventory_offline")) return false;
                schedule(client, player -> {
                    player.serverLevel().setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState());
                    fill(player, 1);
                });
                stage = 6;
            }
            case 6 -> {
                if (!done || !hasAlert(screen, "inventory_online") || !device.status().equals("ONLINE")) return false;
                schedule(client, player -> {
                    var networks = DashboardAPI.networks(player.server);
                    var network = networks.createNetwork("Storage Dashboard viewer verification", UUID.randomUUID());
                    viewerNetwork = network.id();
                    networks.setMember(viewerNetwork, player.getUUID(), NetworkRole.VIEWER);
                    networks.addDevice(viewerNetwork, deviceId);
                    check(DashboardAPI.hasPermission(player, viewerNetwork, Permission.VIEW)
                            && !DashboardAPI.hasPermission(player, viewerNetwork, Permission.CONFIGURE), "Viewer configuration permissions incorrect");
                    check(DashboardAPI.executeAction(player, viewerNetwork, deviceId,
                            ResourceLocation.parse("homelink_storage:refresh_index"), Unit.INSTANCE).code() == ActionResult.Code.DENIED,
                            "Server accepted viewer action despite Dashboard restriction");
                    var access = (HomeServerBlockEntity) player.serverLevel().getBlockEntity(ACCESS);
                    access.setNetworkId(viewerNetwork);
                    DashboardAccess.open(player, access);
                });
                stage = 7;
            }
            case 7 -> {
                if (!done || !state.role().equals("VIEWER")) return false;
                screen.showDevices();
                check(screen.explorer().select(deviceId), "Viewer could not inspect Storage");
                screen.openActions();
                check(screen.actions().selectAction("homelink_storage:refresh_index"), "Viewer action definition missing");
                check(!state.canControl(screen.actions().currentAction()) && !screen.actions().submit(Unit.INSTANCE), "Dashboard allowed viewer control");
                schedule(client, player -> {
                    var access = (HomeServerBlockEntity) player.serverLevel().getBlockEntity(ACCESS);
                    access.setActive(false);
                    check(!RadioNetworkService.hasSignal(access), "Inactive Dashboard server still transmits");
                    check(!player.containerMenu.stillValid(player), "Dashboard menu stayed authorized after signal loss");
                });
                stage = 8;
            }
            default -> throw new IllegalStateException("Unexpected Dashboard stage " + stage);
        }
        return false;
    }

    private static boolean hasAlert(DashboardScreen screen, String name) {
        return screen.state().alerts().stream().anyMatch(alert -> alert.sourceId().equals(deviceId) && alert.type().equals("homelink_storage:" + name));
    }
    private static Object metric(DebugDeviceView device, String name) {
        return device.metrics().stream().filter(value -> value.id().equals("homelink_storage:" + name)).findFirst().orElseThrow().value().value();
    }
    private static void fill(ServerPlayer player, int occupied) {
        var barrel = (Container) player.serverLevel().getBlockEntity(BARREL);
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) barrel.setItem(slot, slot < occupied ? new ItemStack(Items.DIAMOND) : ItemStack.EMPTY);
        refresh(player);
    }
    private static void refresh(ServerPlayer player) {
        var controller = (StorageBlockEntity) player.serverLevel().getBlockEntity(CONTROLLER);
        controller.refreshConnections();
        controller.refreshIndex();
        controller.device().update();
    }
    private static void schedule(Minecraft client, Consumer<ServerPlayer> task) {
        done = false;
        UUID identity = client.player.getUUID();
        var server = client.getSingleplayerServer();
        server.execute(() -> {
            try { task.accept(server.getPlayerList().getPlayer(identity)); done = true; }
            catch (Throwable problem) { failure = problem; }
        });
    }
    private static void screenshot(Minecraft client, String name) {
        Screenshot.grab(client.gameDirectory, name, client.getMainRenderTarget(), message -> LogUtils.getLogger().info("STORAGE_DASHBOARD_SCREENSHOT {}", message.getString()));
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private DashboardChecks() { }
}
