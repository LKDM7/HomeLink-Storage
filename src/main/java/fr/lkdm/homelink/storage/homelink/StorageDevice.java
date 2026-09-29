package fr.lkdm.homelink.storage.homelink;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.action.ActionResult;
import fr.lkdm.homecore.api.action.DeviceAction;
import fr.lkdm.homecore.api.device.DashboardDevice;
import fr.lkdm.homecore.api.device.DeviceStatus;
import fr.lkdm.homecore.api.event.DeviceEvent;
import fr.lkdm.homecore.api.metric.DeviceMetric;
import fr.lkdm.homecore.api.metric.MetricTypes;
import fr.lkdm.homecore.api.metric.Percentage;
import fr.lkdm.homecore.api.metric.Unit;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Adapter of the storage controller to HomeCore's public, server-scoped API. */
public final class StorageDevice implements DashboardDevice, fr.lkdm.homecore.api.network.NetworkMember {
    private final StorageBlockEntity entity;
    private final DeviceMetric<Percentage> capacity = DeviceMetric.builder(id("capacity"), label("metric", "capacity"),
            MetricTypes.PERCENTAGE, new Percentage(0)).unit(Unit.PERCENT).build();
    private final DeviceMetric<Long> items = DeviceMetric.builder(id("item_count"), label("metric", "item_count"),
            MetricTypes.LONG, 0L).unit(Unit.ITEM).build();
    private final DeviceMetric<Integer> unique = integerMetric("unique_items");
    private final DeviceMetric<Integer> inventories = integerMetric("inventory_count");
    private final DeviceMetric<Integer> full = integerMetric("full_inventories");
    private final List<DeviceMetric<?>> metrics = List.of(capacity, items, unique, inventories, full);
    private final List<DeviceAction<?>> actions;
    private final Map<UUID, Boolean> connectionStates = new HashMap<>();
    private boolean warningEmitted;
    private boolean fullEmitted;
    private boolean lastScansPending;
    private long indexedRevision = Long.MIN_VALUE;
    private long metadataRevision = Long.MIN_VALUE;
    private double lastWarningThreshold = Double.NaN;
    private double lastFullThreshold = Double.NaN;
    private double lastHysteresis = Double.NaN;

    public StorageDevice(StorageBlockEntity entity) {
        this.entity = entity;
        warningEmitted = entity.warningLatched();
        fullEmitted = entity.fullLatched();
        actions = List.of(DeviceAction.button(id("refresh_index"), label("action", "refresh_index"))
                .description(label("action", "refresh_index_description"))
                .requiredPermission(Permission.CONTROL.id())
                .handler((context, unused) -> {
                    if (!isValid() || !(entity.getLevel() instanceof ServerLevel level)
                            || !level.getServer().isSameThread()) return ActionResult.of(ActionResult.Code.DEVICE_OFFLINE);
                    if (!context.deviceId().equals(id()) || !context.networkId().equals(entity.networkId()))
                        return ActionResult.of(ActionResult.Code.DENIED);
                    var player = level.getServer().getPlayerList().getPlayer(context.playerId());
                    if (player == null || !DashboardAPI.hasPermission(player, context.networkId(), Permission.CONTROL))
                        return ActionResult.of(ActionResult.Code.DENIED);
                    entity.markIndexDirty();
                    return ActionResult.success();
                }).build());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(HomeLinkStorage.MOD_ID, path);
    }

    private static Component label(String kind, String name) {
        return Component.translatable(kind + ".homelink_storage." + name);
    }

    private static DeviceMetric<Integer> integerMetric(String name) {
        return DeviceMetric.builder(id(name), label("metric", name), MetricTypes.INTEGER, 0).build();
    }

    @Override public UUID id() { return entity.id(); }
    @Override public Optional<UUID> homeNetwork() { return Optional.ofNullable(entity.networkId()); }
    @Override public Optional<UUID> owner() { return Optional.ofNullable(entity.owner()); }
    @Override public boolean canConfigure(net.minecraft.server.level.ServerPlayer player) {
        return isValid() && (player.hasPermissions(2) || owner().filter(player.getUUID()::equals).isPresent()
                || entity.permission(player, Permission.CONFIGURE));
    }
    @Override public void homeNetworkChanged(Optional<fr.lkdm.homecore.api.network.HomeNetwork> network) {
        entity.setHomeNetwork(network.map(fr.lkdm.homecore.api.network.HomeNetwork::id).orElse(null));
    }
    @Override public ResourceLocation deviceType() { return id("storage_controller"); }
    @Override public Component displayName() { return entity.getDisplayName(); }
    @Override public List<DeviceMetric<?>> metrics() { return metrics; }
    @Override public List<DeviceAction<?>> actions() { return actions; }
    @Override public Set<ResourceLocation> eventTypes() {
        return Set.of(id("storage_warning"), id("storage_full"), id("inventory_offline"), id("inventory_online"));
    }
    @Override public Optional<BlockPos> position() { return Optional.of(entity.getBlockPos().immutable()); }
    @Override public Optional<ResourceKey<Level>> dimension() {
        return entity.getLevel() == null ? Optional.empty() : Optional.of(entity.getLevel().dimension());
    }
    @Override public boolean isValid() {
        return !entity.isRemoved() && entity.isController() && entity.getLevel() instanceof ServerLevel level
                && level.hasChunkAt(entity.getBlockPos()) && level.getBlockEntity(entity.getBlockPos()) == entity;
    }

    private double capacityPercent() {
        return entity.index().totalSlots() == 0 ? 0 : 100.0 * entity.index().occupiedSlots() / entity.index().totalSlots();
    }

    @Override public DeviceStatus status() {
        if (!isValid()) return DeviceStatus.OFFLINE;
        if (!entity.powered()) return DeviceStatus.WARNING.withMessage(label("status", "no_power"));
        if (entity.discoveryTruncated()) return DeviceStatus.WARNING.withMessage(label("status", "discovery_limited"));
        if (capacityPercent() >= Math.min(StorageConfig.WARNING_THRESHOLD.get(), StorageConfig.FULL_THRESHOLD.get()))
            return DeviceStatus.WARNING.withMessage(label("status", "storage_warning"));
        if (entity.connections().values().stream().anyMatch(connection -> connection.status != StorageInventoryAdapter.Status.ONLINE))
            return DeviceStatus.WARNING.withMessage(label("status", "inventory_offline"));
        return DeviceStatus.ONLINE;
    }

    /** Called on the server thread after registration; equal metric values retain their revisions. */
    public void update() {
        if (!(entity.getLevel() instanceof ServerLevel level) || !isValid()) return;
        double warningThreshold = StorageConfig.WARNING_THRESHOLD.get();
        double fullThreshold = StorageConfig.FULL_THRESHOLD.get();
        double hysteresis = StorageConfig.HYSTERESIS.get();
        if (indexedRevision == entity.index().revision() && metadataRevision == entity.metadataRevision()
                && lastScansPending == entity.hasPendingScans()
                && lastWarningThreshold == warningThreshold && lastFullThreshold == fullThreshold && lastHysteresis == hysteresis) return;
        double usage = capacityPercent();
        capacity.setValue(new Percentage(usage));
        items.setValue(entity.index().totalItems());
        unique.setValue(entity.index().uniqueItems());
        inventories.setValue(entity.index().inventoryCount());
        full.setValue(entity.index().fullInventories());
        // Do not publish events for an orphaned or superseded device adapter.
        if (DashboardAPI.devices(level.getServer()).get(id()).orElse(null) != this) return;
        indexedRevision = entity.index().revision();
        metadataRevision = entity.metadataRevision();
        lastWarningThreshold = warningThreshold;
        lastFullThreshold = fullThreshold;
        lastHysteresis = hysteresis;
        lastScansPending = entity.hasPendingScans();
        // Partial reconstruction or unloaded inventories cannot prove a threshold crossing.
        boolean complete = entity.indexComplete() && entity.index().totalSlots() > 0;
        if (complete) {
            if (usage < Math.max(0, warningThreshold - hysteresis) || usage == 0) warningEmitted = false;
            if (usage < Math.max(0, fullThreshold - hysteresis) || usage == 0) fullEmitted = false;
        }
        if (complete && usage >= warningThreshold && !warningEmitted) {
            warningEmitted = true;
            publish(level, "storage_warning", DeviceEvent.Severity.WARNING, Map.of("capacity", Double.toString(usage)));
        }
        if (complete && usage >= fullThreshold && !fullEmitted) {
            fullEmitted = true;
            publish(level, "storage_full", DeviceEvent.Severity.CRITICAL, Map.of("capacity", Double.toString(usage)));
        }
        entity.setAlertLatches(warningEmitted, fullEmitted);
        connectionStates.keySet().retainAll(entity.connections().keySet());
        entity.connections().forEach((linkId, connection) -> {
            boolean online = connection.status == StorageInventoryAdapter.Status.ONLINE;
            Boolean previous = connectionStates.put(linkId, online);
            if (previous != null && previous != online) {
                publish(level, online ? "inventory_online" : "inventory_offline",
                        online ? DeviceEvent.Severity.INFO : DeviceEvent.Severity.WARNING,
                        Map.of("link_id", linkId.toString(), "status", connection.status.name()));
            }
        });
    }

    private void publish(ServerLevel level, String type, DeviceEvent.Severity severity, Map<String, String> data) {
        DashboardAPI.events(level.getServer()).publish(new DeviceEvent(id(type), id(), Instant.now(), severity, data));
    }
}
