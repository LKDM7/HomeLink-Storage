package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.action.ActionResult;
import fr.lkdm.homecore.api.action.Unit;
import fr.lkdm.homecore.api.client.ClientDeviceCache;
import fr.lkdm.homecore.api.client.HomeCoreClient;
import fr.lkdm.homecore.api.device.DeviceStatus;
import fr.lkdm.homecore.api.event.DeviceEvent;
import fr.lkdm.homecore.api.metric.Percentage;
import fr.lkdm.homecore.api.network.NetworkRole;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homecore.api.transport.HomeCorePayloads;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Integration checks against the real HomeCore public SDK and connected-client transport. */
public final class HomeCoreChecks {
    private static final BlockPos CONTROLLER = new BlockPos(48, 5, 0);
    private static final BlockPos BARREL = new BlockPos(48, 5, 2);
    private static volatile UUID networkId;
    private static volatile UUID deviceId;
    private static boolean subscribed;
    private static UUID request;
    private static boolean clientVerified;
    private static boolean eventTriggered;
    private static volatile Throwable eventFailure;

    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        var server = level.getServer();
        StorageBlockEntity controller = place(player, CONTROLLER, StorageRegistries.CONTROLLER.get());
        level.setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState());
        StorageBlockEntity link = place(player, BARREL.above(), StorageRegistries.LINK.get());
        check(link.bind(controller), "HomeCore fixture binding failed");
        controller.ensureHomeCore();
        refresh(controller);
        deviceId = controller.id();
        networkId = controller.networkId();
        check(networkId != null, "Controller has no HomeNetwork");
        check(DashboardAPI.devices(server).get(deviceId).orElseThrow() == controller.device(), "Controller not registered with real HomeCore");
        check(DashboardAPI.networks(server).getDevices(networkId).contains(deviceId), "Device missing from persistent HomeNetwork");
        check(controller.permission(player, Permission.VIEW) && controller.permission(player, Permission.CONTROL), "Owner permissions denied");
        check(controller.device().metrics().size() == 16, "Storage and pipe metric definitions missing");
        check(metric(controller, "capacity").equals(new Percentage(0)), "Capacity metric must use real Percentage type");
        check(metric(controller, "inventory_count").equals(1), "Inventory metric mismatch");
        check(DashboardAPI.executeAction(player, networkId, deviceId, id("refresh_index"), Unit.INSTANCE).isSuccess(), "Owner refresh action failed");

        var manager = DashboardAPI.networks(server);
        var viewerNetwork = manager.createNetwork("Storage viewer verification", UUID.fromString("7fd50468-a86a-4d83-b96a-916e21f9aee8"));
        manager.setMember(viewerNetwork.id(), player.getUUID(), NetworkRole.VIEWER);
        manager.addDevice(viewerNetwork.id(), deviceId);
        check(DashboardAPI.hasPermission(player, viewerNetwork.id(), Permission.VIEW), "Viewer cannot view");
        check(!DashboardAPI.hasPermission(player, viewerNetwork.id(), Permission.CONTROL), "Viewer can control");
        check(DashboardAPI.executeAction(player, viewerNetwork.id(), deviceId, id("refresh_index"), Unit.INSTANCE).code() == ActionResult.Code.DENIED, "Viewer action was not denied");
        manager.deleteNetwork(viewerNetwork.id());

        List<DeviceEvent> events = new ArrayList<>();
        try (var subscription = DashboardAPI.events(server).subscribe(event -> { if (event.source().equals(deviceId)) events.add(event); })) {
            fill(player, controller, 25);
            check(metric(controller, "item_count").equals(25L), "Item metric mismatch");
            check(controller.device().status().state() == DeviceStatus.State.WARNING, "Capacity warning status missing");
            check(count(events, "storage_warning") == 1, "Warning event missing");
            controller.device().update();
            check(count(events, "storage_warning") == 1, "Unchanged capacity repeated warning");
            check(DashboardAPI.executeAction(player, networkId, deviceId, id("refresh_index"), Unit.INSTANCE).code() == ActionResult.Code.DEVICE_OFFLINE, "HomeCore WARNING action restriction changed");
            fill(player, controller, 27);
            check(count(events, "storage_full") == 1 && metric(controller, "full_inventories").equals(1), "Full event or metric missing");
            fill(player, controller, 26);
            fill(player, controller, 27);
            check(count(events, "storage_full") == 1, "Full threshold rearmed too early");
            fill(player, controller, 22);
            fill(player, controller, 25);
            fill(player, controller, 27);
            check(count(events, "storage_warning") == 2 && count(events, "storage_full") == 2, "Threshold hysteresis failed to rearm");
            level.destroyBlock(BARREL, false);
            refresh(controller);
            check(count(events, "inventory_offline") == 1, "Offline transition missing");
            controller.device().update();
            check(count(events, "inventory_offline") == 1, "Offline event repeated");
            level.setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState());
            refresh(controller);
            check(count(events, "inventory_online") == 1, "Reconnection event missing");
        }

        BlockPos temporaryPos = new BlockPos(14, 5, 2);
        StorageBlockEntity temporary = place(player, temporaryPos, StorageRegistries.CONTROLLER.get());
        temporary.ensureHomeCore();
        check(DashboardAPI.devices(server).get(temporary.id()).isPresent(), "Lifecycle fixture not registered");
        level.destroyBlock(temporaryPos, false);
        check(DashboardAPI.devices(server).get(temporary.id()).isEmpty(), "Destroyed controller stayed registered");
        partialRebuildLatches(player, controller);
        LogUtils.getLogger().info("STORAGE_HOMECORE_SERVER_CHECKS_OK sdk={} registration=true metrics=16 owner_action=true viewer_denied=true warning_action_denied=true hysteresis=true inventory_events=true lifecycle=true partial_rebuild_latches=true", DashboardAPI.API_VERSION);
    }

    private static void partialRebuildLatches(ServerPlayer player, StorageBlockEntity controller) {
        var level = player.serverLevel();
        BlockPos secondPos = new BlockPos(50, 5, 2);
        level.setBlockAndUpdate(secondPos, Blocks.BARREL.defaultBlockState());
        Container first = (Container) level.getBlockEntity(BARREL);
        Container second = (Container) level.getBlockEntity(secondPos);
        for (int slot = 0; slot < 27; slot++) {
            first.setItem(slot, new ItemStack(Items.DIAMOND));
            second.setItem(slot, new ItemStack(Items.DIAMOND));
        }
        refresh(controller);
        check(controller.indexComplete() && controller.warningLatched() && controller.fullLatched(), "Full baseline did not latch both thresholds");
        StorageBlockEntity restored = new StorageBlockEntity(CONTROLLER, controller.getBlockState());
        restored.loadWithComponents(controller.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        check(restored.warningLatched() && restored.fullLatched(), "Alert latches missing from persisted NBT");

        controller.markIndexDirty();
        for (int slot = 0; slot < 27; slot++) first.setItem(slot, ItemStack.EMPTY);
        var adapter = fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter.resolve(level, BARREL, Direction.UP).adapter();
        check(adapter != null, "Partial rebuild inventory adapter missing");
        controller.index().update(BARREL, adapter.handler());
        controller.index().remove(secondPos);
        controller.device().update();
        check(!controller.indexComplete() && controller.index().totalSlots() == 27 && controller.index().totalItems() == 0, "Fixture did not produce an incomplete empty sample");
        check(controller.warningLatched() && controller.fullLatched(), "Partial index rebuild incorrectly rearmed persisted threshold latches");

        for (int slot = 0; slot < 27; slot++) second.setItem(slot, ItemStack.EMPTY);
        refresh(controller);
        check(controller.indexComplete() && controller.index().totalSlots() == 54, "Full consistency pass did not finish reconstruction");
        check(!controller.warningLatched() && !controller.fullLatched(), "Complete drained inventories did not rearm thresholds");
        level.destroyBlock(secondPos, false);
        refresh(controller);
        var missing = controller.connections().values().stream().filter(connection -> connection.inventoryPos.equals(secondPos)).findFirst().orElseThrow();
        controller.forget(missing.linkId);
        refresh(controller);
    }

    public static boolean clientReady() {
        if (clientVerified) return true;
        if (!subscribed) { HomeCoreClient.subscribeNetwork(networkId); subscribed = true; return false; }
        var snapshot = ClientDeviceCache.INSTANCE.devices().get(new ClientDeviceCache.DeviceKey(networkId, deviceId));
        if (snapshot == null) return false;
        check(snapshot.data().getList("metrics", 10).size() == 16, "HomeCore client snapshot missing metrics");
        if (request == null) { request = HomeCoreClient.executeAction(networkId, deviceId, id("refresh_index"), Unit.INSTANCE); return false; }
        var response = ClientDeviceCache.INSTANCE.recentMessages().stream().filter(HomeCorePayloads.ActionResultResponse.class::isInstance)
                .map(HomeCorePayloads.ActionResultResponse.class::cast).filter(result -> result.requestId().equals(request)).findFirst();
        if (response.isEmpty()) return false;
        check(response.orElseThrow().result().isSuccess(), "HomeCore client action response failed");
        if (!eventTriggered) {
            eventTriggered = true;
            var client = net.minecraft.client.Minecraft.getInstance();
            var identity = client.player.getUUID();
            var server = client.getSingleplayerServer();
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(identity);
                    fill(player, (StorageBlockEntity) player.serverLevel().getBlockEntity(CONTROLLER), 25);
                } catch (Throwable failure) { eventFailure = failure; }
            });
            return false;
        }
        if (eventFailure != null) throw new IllegalStateException("HomeCore event transport fixture failed", eventFailure);
        boolean eventReceived = ClientDeviceCache.INSTANCE.recentMessages().stream()
                .filter(HomeCorePayloads.DeviceEventNotification.class::isInstance)
                .map(HomeCorePayloads.DeviceEventNotification.class::cast)
                .anyMatch(notification -> notification.event().source().equals(deviceId) && notification.event().type().equals(id("storage_warning")));
        if (!eventReceived) return false;
        HomeCoreClient.unsubscribe(networkId);
        clientVerified = true;
        LogUtils.getLogger().info("STORAGE_HOMECORE_CLIENT_CHECKS_OK snapshot_metrics=16 action_request_response=true event_transport=true");
        return true;
    }

    private static void fill(ServerPlayer player, StorageBlockEntity controller, int occupied) {
        Container barrel = (Container) player.serverLevel().getBlockEntity(BARREL);
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) barrel.setItem(slot, slot < occupied ? new ItemStack(Items.DIAMOND) : ItemStack.EMPTY);
        refresh(controller);
    }
    private static void refresh(StorageBlockEntity controller) { controller.refreshConnections(); controller.refreshIndex(); controller.device().update(); }
    private static Object metric(StorageBlockEntity controller, String path) { return controller.device().metrics().stream().filter(metric -> metric.id().equals(id(path))).findFirst().orElseThrow().value(); }
    private static long count(List<DeviceEvent> events, String path) { return events.stream().filter(event -> event.type().equals(id(path))).count(); }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("homelink_storage", path); }
    private static StorageBlockEntity place(ServerPlayer player, BlockPos pos, Block block) {
        player.serverLevel().setBlockAndUpdate(pos, block.defaultBlockState().setValue(StorageBlock.TARGET, Direction.DOWN));
        StorageBlockEntity entity = (StorageBlockEntity) player.serverLevel().getBlockEntity(pos);
        entity.setOwner(player.getUUID());
        return entity;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private HomeCoreChecks() { }
}
