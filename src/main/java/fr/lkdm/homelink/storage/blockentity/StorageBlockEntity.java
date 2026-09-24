package fr.lkdm.homelink.storage.blockentity;

import com.mojang.logging.LogUtils;
import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.homelink.StorageDevice;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.index.StorageIndex;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import fr.lkdm.homelink.storage.storage.network.CoverageNode;
import fr.lkdm.homelink.storage.storage.network.InventoryConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Server-owned identity, chunk coverage graph, scheduled index and HomeCore lifecycle. */
public class StorageBlockEntity extends BlockEntity implements MenuProvider {
    private record Discovery(CoverageNode source, StorageInventoryAdapter adapter) { }
    private record LegacyMetadata(String name, String zone) { }

    private UUID id = UUID.randomUUID();
    private UUID owner;
    private UUID networkId;
    private UUID controllerId;
    private BlockPos controllerPos;
    private StorageDevice device;
    private boolean registrationFailed;
    private boolean warningLatched;
    private boolean fullLatched;
    private boolean coverageDirty;
    private boolean discoveryTruncated;
    private int comparatorSignal;
    private String logicalName = "";
    private long metadataRevision;
    private final Map<UUID, CoverageNode> coverage = new LinkedHashMap<>();
    private final Map<UUID, InventoryConnection> connections = new LinkedHashMap<>();
    private final Map<UUID, LegacyMetadata> legacyMetadata = new HashMap<>();
    private final Map<String, String> zones = new LinkedHashMap<>();
    private final StorageIndex index = new StorageIndex();
    private final ArrayDeque<UUID> pendingScans = new ArrayDeque<>();
    private final Set<UUID> reportedFailures = new HashSet<>();
    private Set<ChunkPos> activeChunks = Set.of();

    public StorageBlockEntity(BlockPos pos, BlockState state) {
        this(StorageRegistries.STORAGE_ENTITY.get(), pos, state);
    }

    protected StorageBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        addBuiltinZones();
    }

    private void addBuiltinZones() {
        for (String zone : new String[]{"minerals", "wood", "farming", "food", "equipment", "redstone", "misc"}) zones.put(zone, "");
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public UUID networkId() { return networkId; }
    public StorageDevice device() { return device; }
    public long metadataRevision() { return metadataRevision; }
    public String logicalName() { return logicalName; }
    public StorageIndex index() { return index; }
    public boolean warningLatched() { return warningLatched; }
    public boolean fullLatched() { return fullLatched; }
    public BlockPos controllerPos() { return controllerPos; }
    public boolean isController() { return getBlockState().is(StorageRegistries.CONTROLLER.get()); }
    public boolean isLink() { return getBlockState().is(StorageRegistries.LINK.get()); }
    public boolean isRepeater() { return getBlockState().is(StorageRegistries.REPEATER.get()); }
    public boolean isCoverageNode() { return isLink() || isRepeater(); }
    public Map<UUID, InventoryConnection> connections() { return Collections.unmodifiableMap(connections); }
    public Map<UUID, CoverageNode> coverageNodes() { return Collections.unmodifiableMap(coverage); }
    public Map<String, String> zones() { return Collections.unmodifiableMap(zones); }
    public boolean hasPendingScans() { return !pendingScans.isEmpty(); }
    public boolean isCoverageActive(UUID sourceId) {
        CoverageNode node = coverage.get(sourceId);
        return node != null && node.status == StorageInventoryAdapter.Status.ONLINE;
    }
    public boolean discoveryTruncated() { return discoveryTruncated; }

    public void setOwner(UUID owner) { this.owner = owner; setChanged(); }
    public void setAlertLatches(boolean warning, boolean full) {
        if (warningLatched != warning || fullLatched != full) {
            warningLatched = warning;
            fullLatched = full;
            setChanged();
        }
    }
    public void setLogicalName(String name) { logicalName = validName(name); metadataRevision++; setChanged(); }
    public void setInventoryName(UUID inventory, String name) {
        InventoryConnection connection = connections.get(inventory);
        if (connection != null) { connection.name = validName(name); metadataRevision++; setChanged(); }
    }
    public void setZone(UUID inventory, String zone) {
        InventoryConnection connection = connections.get(inventory);
        if (connection != null && zones.containsKey(zone)) { connection.zone = zone; metadataRevision++; setChanged(); }
    }
    public void resetInventory(UUID inventory) {
        InventoryConnection connection = connections.get(inventory);
        if (connection != null) {
            connection.name = "";
            connection.zone = "misc";
            metadataRevision++;
            setChanged();
        }
    }
    public String createZone(String name) {
        if (zones.size() >= 64) throw new IllegalArgumentException("Zone limit");
        String display = validName(name);
        if (display.isBlank()) throw new IllegalArgumentException("Empty zone");
        String zoneId = UUID.randomUUID().toString();
        zones.put(zoneId, display); metadataRevision++; setChanged();
        return zoneId;
    }
    /** Forgets a stale inventory. Online inventories remain managed by automatic discovery. */
    public void forget(UUID inventory) {
        InventoryConnection current = connections.get(inventory);
        if (current == null || current.status == StorageInventoryAdapter.Status.ONLINE) return;
        InventoryConnection removed = connections.remove(inventory);
        if (removed != null) { pendingScans.remove(inventory); discardUnavailable(removed); metadataRevision++; setChanged(); }
    }

    public int comparatorSignal() {
        return isController() && index.totalSlots() > 0 ? (int) (15L * index.occupiedSlots() / index.totalSlots()) : 0;
    }
    public boolean indexComplete() {
        return pendingScans.isEmpty()
                && connections.values().stream().allMatch(connection -> connection.status == StorageInventoryAdapter.Status.ONLINE)
                && connections.values().stream().map(connection -> connection.inventoryPos).distinct().count() == index.inventoryCount();
    }

    public StorageBlockEntity controller() {
        if (isController()) return this;
        if (level == null || controllerPos == null || !level.hasChunkAt(controllerPos)) return null;
        return level.getBlockEntity(controllerPos) instanceof StorageBlockEntity source
                && source.isController() && source.id.equals(controllerId) ? source : null;
    }

    public boolean canAccess(ServerPlayer player) { return permission(player, Permission.VIEW); }
    public boolean permission(ServerPlayer player, Permission permission) {
        StorageBlockEntity source = controller();
        if (source != null && source.registrationFailed) return false;
        if (source != null && source.networkId != null) {
            var networks = DashboardAPI.networks(player.server);
            return networks.getNetwork(source.networkId).map(home -> home.devices().contains(source.id)).orElse(false)
                    && DashboardAPI.hasPermission(player, source.networkId, permission)
                    && (source.device == null || networks.isReachable(source.networkId, source.device));
        }
        return owner != null && owner.equals(player.getUUID());
    }

    public boolean bind(StorageBlockEntity target) {
        if (isController() || !target.isController() || target.level != level || level == null || level.isClientSide) return false;
        boolean node = isCoverageNode();
        if (node && !target.coverage.containsKey(id) && target.coverage.size() >= StorageConfig.MAX_COVERAGE_NODES.get()) return false;
        StorageBlockEntity old = controller();
        if (old != null && old != target && node) old.removeCoverage(id, StorageInventoryAdapter.Status.INVALID);
        controllerId = target.id;
        controllerPos = target.worldPosition.immutable();
        if (node) {
            target.coverage.put(id, new CoverageNode(id, worldPosition, isRepeater()));
            target.coverageDirty = true;
            target.refreshConnections();
        }
        metadataRevision++;
        target.metadataRevision++;
        target.setChanged();
        setChanged();
        return true;
    }

    public void destroyed() {
        if (!(level instanceof ServerLevel server)) return;
        if (isController() && networkId != null) {
            var manager = DashboardAPI.networks(server.getServer());
            manager.getNetwork(networkId).ifPresent(home -> {
                manager.removeDevice(networkId, id);
                manager.getNetwork(networkId).ifPresent(updated -> {
                    if (updated.devices().isEmpty() && updated.members().size() == 1 && updated.owner().equals(owner)) manager.deleteNetwork(networkId);
                });
            });
        } else if (isCoverageNode()) {
            StorageBlockEntity source = controller();
            if (source != null) source.removeCoverage(id, StorageInventoryAdapter.Status.INVALID);
        }
        unregisterHomeCore();
    }

    private void removeCoverage(UUID nodeId, StorageInventoryAdapter.Status status) {
        if (coverage.remove(nodeId) == null) return;
        for (InventoryConnection connection : connections.values()) if (connection.sourceId.equals(nodeId)) {
            connection.status = status;
            discardUnavailable(connection);
            pendingScans.remove(connection.linkId);
        }
        coverageDirty = true;
        metadataRevision++;
        setChanged();
    }

    private void notifyUnavailable(StorageInventoryAdapter.Status status) {
        StorageBlockEntity source = controller();
        if (source == null || source == this) return;
        CoverageNode node = source.coverage.get(id);
        if (node != null) node.status = status;
        for (InventoryConnection connection : source.connections.values()) if (connection.sourceId.equals(id)) {
            connection.status = status;
            source.discardUnavailable(connection);
        }
        source.coverageDirty = true;
        source.metadataRevision++;
        source.setChanged();
    }

    public void ensureHomeCore() {
        if (!isController() || owner == null || isRemoved() || registrationFailed || !(level instanceof ServerLevel server)) return;
        try {
            if (networkId == null) {
                networkId = DashboardAPI.networks(server.getServer()).createNetwork(logicalName.isBlank() ? "HomeLink Storage" : logicalName, owner).id();
                DashboardAPI.networks(server.getServer()).addDevice(networkId, id);
                setChanged();
            }
            if (device == null) {
                StorageDevice candidate = new StorageDevice(this);
                DashboardAPI.devices(server.getServer()).register(candidate);
                device = candidate;
            }
        } catch (RuntimeException failure) {
            registrationFailed = true;
            LogUtils.getLogger().error("Storage controller {} at {} could not register with HomeCore", id, worldPosition, failure);
        }
    }

    private void unregisterHomeCore() {
        if (device != null && level instanceof ServerLevel server) {
            DashboardAPI.devices(server.getServer()).unregister(device.id());
            device = null;
        }
    }

    @Override public void onLoad() { super.onLoad(); coverageDirty = isController(); ensureHomeCore(); }
    @Override public void onChunkUnloaded() {
        if (isCoverageNode()) notifyUnavailable(StorageInventoryAdapter.Status.UNLOADED);
        unregisterHomeCore();
        super.onChunkUnloaded();
    }
    @Override public void setRemoved() { unregisterHomeCore(); super.setRemoved(); }

    public static void tick(Level level, BlockPos pos, BlockState state, StorageBlockEntity entity) {
        if (!entity.isController()) return;
        entity.ensureHomeCore();
        int verification = StorageConfig.VERIFY_INTERVAL.get();
        int rescan = StorageConfig.RESCAN_INTERVAL.get();
        if (entity.coverageDirty || level.getGameTime() % verification == Math.floorMod(pos.asLong(), verification)) entity.verifyCoverage();
        if (level.getGameTime() % rescan == Math.floorMod(pos.asLong(), rescan)) entity.refreshConnections();
        for (int budget = 0; budget < StorageConfig.SCANS_PER_TICK.get() && !entity.pendingScans.isEmpty(); budget++) {
            entity.scan(entity.pendingScans.removeFirst());
        }
        if (entity.device != null) entity.device.update();
        int signal = entity.comparatorSignal();
        if (signal != entity.comparatorSignal) {
            entity.comparatorSignal = signal;
            level.updateNeighbourForOutputSignal(pos, state.getBlock());
        }
    }

    public void markIndexDirty() {
        if (isController()) enqueueScans();
        else if (isCoverageNode()) {
            StorageBlockEntity source = controller();
            if (source != null) { source.coverageDirty = true; source.refreshConnections(); }
        }
    }

    private void verifyCoverage() {
        if (!(level instanceof ServerLevel)) return;
        Set<ChunkPos> before = activeChunks;
        boolean changed = recomputeCoverage();
        coverageDirty = false;
        if (changed || !before.equals(activeChunks)) discoverCoveredInventories();
    }

    private boolean recomputeCoverage() {
        if (!(level instanceof ServerLevel server)) return false;
        Map<UUID, StorageInventoryAdapter.Status> previous = new HashMap<>();
        Map<UUID, CoverageNode> valid = new LinkedHashMap<>();
        for (CoverageNode node : coverage.values()) {
            previous.put(node.id, node.status);
            var chunk = server.getChunkSource().getChunkNow(node.chunk().x, node.chunk().z);
            if (chunk == null) { node.status = StorageInventoryAdapter.Status.UNLOADED; continue; }
            BlockEntity found = chunk.getBlockEntity(node.position);
            if (found instanceof StorageBlockEntity storage && storage.id.equals(node.id)
                    && id.equals(storage.controllerId) && storage.isRepeater() == node.repeater
                    && (storage.isLink() || storage.isRepeater())) {
                node.status = StorageInventoryAdapter.Status.OFFLINE;
                valid.put(node.id, node);
            } else node.status = StorageInventoryAdapter.Status.INVALID;
        }

        Set<UUID> activeIds = new HashSet<>();
        Set<ChunkPos> chunks = new HashSet<>();
        for (CoverageNode node : valid.values()) if (!node.repeater) {
            activeIds.add(node.id);
            chunks.add(node.chunk());
        }
        boolean advanced;
        do {
            advanced = false;
            for (CoverageNode node : valid.values()) if (node.repeater && !activeIds.contains(node.id)
                    && chunks.stream().anyMatch(chunk -> chunk.equals(node.chunk()) || cardinalNeighbours(chunk, node.chunk()))) {
                activeIds.add(node.id);
                chunks.add(node.chunk());
                advanced = true;
            }
        } while (advanced);
        for (UUID active : activeIds) valid.get(active).status = StorageInventoryAdapter.Status.ONLINE;
        activeChunks = Set.copyOf(chunks);

        boolean changed = false;
        for (CoverageNode node : coverage.values()) if (previous.get(node.id) != node.status) changed = true;
        for (InventoryConnection connection : connections.values()) {
            CoverageNode source = coverage.get(connection.sourceId);
            StorageInventoryAdapter.Status status = source == null ? StorageInventoryAdapter.Status.INVALID : source.status;
            if (status != StorageInventoryAdapter.Status.ONLINE && connection.status != status) {
                connection.status = status;
                discardUnavailable(connection);
                changed = true;
            }
        }
        if (changed) { metadataRevision++; setChanged(); }
        return changed;
    }

    private static boolean cardinalNeighbours(ChunkPos first, ChunkPos second) {
        return Math.abs(first.x - second.x) + Math.abs(first.z - second.z) == 1;
    }

    /** Rebuilds the bounded list of inventories from loaded, explicitly covered chunks. */
    private void discoverCoveredInventories() {
        if (!(level instanceof ServerLevel server)) return;
        Map<ChunkPos, CoverageNode> sourceByChunk = new HashMap<>();
        for (CoverageNode node : coverage.values()) if (node.status == StorageInventoryAdapter.Status.ONLINE) {
            sourceByChunk.merge(node.chunk(), node, (left, right) -> left.id.compareTo(right.id) <= 0 ? left : right);
        }
        Map<BlockPos, Discovery> found = new HashMap<>();
        List<ChunkPos> chunks = new ArrayList<>(activeChunks);
        chunks.sort(Comparator.comparingInt((ChunkPos chunk) -> chunk.x).thenComparingInt(chunk -> chunk.z));
        int candidates = 0;
        boolean truncated = false;
        discovery:
        for (ChunkPos chunkPos : chunks) {
            var chunk = server.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            CoverageNode source = sourceByChunk.get(chunkPos);
            if (chunk == null || source == null) continue;
            List<BlockPos> positions = new ArrayList<>(chunk.getBlockEntities().keySet());
            positions.sort(BlockPos::compareTo);
            for (BlockPos position : positions) {
                BlockEntity blockEntity = chunk.getBlockEntity(position);
                if (blockEntity == null || blockEntity.isRemoved() || blockEntity instanceof StorageBlockEntity) continue;
                if (candidates++ >= StorageConfig.MAX_DISCOVERY_CANDIDATES.get()) { truncated = true; break discovery; }
                try {
                    StorageInventoryAdapter.Resolution resolution = StorageInventoryAdapter.resolveAny(server, position);
                    if (resolution.adapter() != null) {
                        StorageInventoryAdapter adapter = resolution.adapter();
                        found.putIfAbsent(adapter.identity(), new Discovery(source, adapter));
                    }
                } catch (RuntimeException failure) {
                    if (reportedFailures.add(source.id)) LogUtils.getLogger().warn("Storage capability discovery failed in chunk {}: {}", chunkPos, failure.toString());
                }
            }
        }

        boolean limitChanged = discoveryTruncated != truncated;
        discoveryTruncated = truncated;

        int maximum = StorageConfig.MAX_INVENTORIES.get();
        Set<BlockPos> accepted = new HashSet<>();
        connections.values().stream().filter(connection -> found.containsKey(connection.inventoryPos))
                .sorted(Comparator.comparing(connection -> connection.inventoryPos)).limit(maximum)
                .forEach(connection -> accepted.add(connection.inventoryPos));
        found.keySet().stream().sorted().filter(position -> accepted.size() < maximum).forEach(accepted::add);

        boolean changed = false;
        for (BlockPos identity : accepted.stream().sorted().toList()) {
            Discovery discovery = found.get(identity);
            InventoryConnection connection = connections.values().stream().filter(existing -> existing.inventoryPos.equals(identity)).findFirst().orElse(null);
            if (connection == null) {
                UUID inventoryId = inventoryId(identity);
                while (connections.containsKey(inventoryId)) inventoryId = UUID.randomUUID();
                connection = new InventoryConnection(inventoryId, discovery.source.id, discovery.source.position,
                        identity, discovery.adapter.position());
                LegacyMetadata legacy = legacyMetadata.remove(discovery.source.id);
                if (legacy != null) { connection.name = legacy.name; connection.zone = zones.containsKey(legacy.zone) ? legacy.zone : "misc"; }
                connections.put(connection.linkId, connection);
                changed = true;
            }
            if (!connection.sourceId.equals(discovery.source.id) || !connection.linkPos.equals(discovery.source.position)
                    || !connection.accessPos.equals(discovery.adapter.position()) || connection.status != StorageInventoryAdapter.Status.ONLINE) {
                connection.sourceId = discovery.source.id;
                connection.linkPos = discovery.source.position;
                connection.accessPos = discovery.adapter.position();
                connection.status = StorageInventoryAdapter.Status.ONLINE;
                changed = true;
            }
            if (!pendingScans.contains(connection.linkId)) pendingScans.addLast(connection.linkId);
        }

        for (InventoryConnection connection : connections.values()) {
            CoverageNode source = coverage.get(connection.sourceId);
            if (!truncated && source != null && source.status == StorageInventoryAdapter.Status.ONLINE
                    && !accepted.contains(connection.inventoryPos)
                    && connection.status != StorageInventoryAdapter.Status.INVENTORY_MISSING) {
                connection.status = StorageInventoryAdapter.Status.INVENTORY_MISSING;
                pendingScans.remove(connection.linkId);
                discardUnavailable(connection);
                changed = true;
            }
        }
        if (found.size() > maximum) LogUtils.getLogger().warn("Storage controller {} discovered {} inventories but its configured limit is {}", id, found.size(), maximum);
        retainOnlineIndex();
        if (changed || limitChanged) { metadataRevision++; setChanged(); }
    }

    private UUID inventoryId(BlockPos identity) {
        String value = "homelink_storage:" + id + ":" + identity.asLong();
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private void enqueueScans() {
        Set<BlockPos> seen = new HashSet<>();
        for (InventoryConnection connection : connections.values()) if (connection.status == StorageInventoryAdapter.Status.ONLINE
                && seen.add(connection.inventoryPos) && !pendingScans.contains(connection.linkId)) pendingScans.addLast(connection.linkId);
    }

    public void refreshInventory(UUID inventoryId) { scan(inventoryId); }

    /** Only indexed locations are candidates; no discovery or global rescan on a deposit attempt. */
    public List<InventoryConnection> depositDestinations(net.minecraft.world.item.ItemStack stack) {
        var entry = index.find(stack);
        if (!isController() || entry == null) return List.of();
        recomputeCoverage();
        return connections.values().stream()
                .filter(connection -> connection.status == StorageInventoryAdapter.Status.ONLINE
                        && isCoverageActive(connection.sourceId) && entry.locations().containsKey(connection.inventoryPos))
                .sorted(Comparator.comparing(connection -> connection.inventoryPos)).toList();
    }

    public boolean automationAvailable() {
        if (registrationFailed || !isController() || isRemoved() || !(level instanceof ServerLevel server)
                || !server.hasChunkAt(worldPosition) || server.getBlockEntity(worldPosition) != this) return false;
        if (networkId == null) return true;
        var networks = DashboardAPI.networks(server.getServer());
        return device != null && networks.getNetwork(networkId).map(home -> home.devices().contains(id)).orElse(false)
                && networks.isReachable(networkId, device);
    }

    /** Check membership against the coverage graph refreshed once when selecting this attempt's candidates. */
    public boolean depositConnectionAvailable(InventoryConnection connection) {
        return connections.get(connection.linkId) == connection && connection.status == StorageInventoryAdapter.Status.ONLINE
                && isCoverageActive(connection.sourceId);
    }

    private void scan(UUID inventoryId) {
        InventoryConnection connection = connections.get(inventoryId);
        if (connection == null || !(level instanceof ServerLevel server)) return;
        CoverageNode source = coverage.get(connection.sourceId);
        if (source == null || source.status != StorageInventoryAdapter.Status.ONLINE
                || server.getChunkSource().getChunkNow(new ChunkPos(connection.accessPos).x, new ChunkPos(connection.accessPos).z) == null) {
            connection.status = source == null ? StorageInventoryAdapter.Status.INVALID : source.status;
            discardUnavailable(connection);
            return;
        }
        try {
            StorageInventoryAdapter.Resolution resolution = StorageInventoryAdapter.resolveAny(server, connection.accessPos);
            StorageInventoryAdapter adapter = resolution.adapter();
            if (adapter == null || !adapter.identity().equals(connection.inventoryPos)) {
                connection.status = resolution.status();
                discardUnavailable(connection);
                metadataRevision++;
                return;
            }
            index.update(adapter.identity(), adapter.handler());
            connection.status = StorageInventoryAdapter.Status.ONLINE;
            reportedFailures.remove(connection.linkId);
        } catch (RuntimeException failure) {
            connection.status = StorageInventoryAdapter.Status.INVALID;
            discardUnavailable(connection);
            if (reportedFailures.add(connection.linkId)) LogUtils.getLogger().warn("Storage inventory {} at {} could not be indexed: {}",
                    connection.linkId, connection.inventoryPos, failure.toString());
        }
    }

    /** Immediate topology/discovery refresh used by binding, commands and verification. */
    public void refreshConnections() {
        if (!(level instanceof ServerLevel) || !isController()) return;
        recomputeCoverage();
        coverageDirty = false;
        discoverCoveredInventories();
    }

    /** Explicit full consistency pass, reserved for user refresh and verification. */
    public void refreshIndex() {
        refreshConnections();
        enqueueScans();
        while (!pendingScans.isEmpty()) scan(pendingScans.removeFirst());
    }

    private void retainOnlineIndex() {
        Set<BlockPos> online = new HashSet<>();
        for (InventoryConnection connection : connections.values()) if (connection.status == StorageInventoryAdapter.Status.ONLINE) online.add(connection.inventoryPos);
        index.retain(online);
    }

    private void discardUnavailable(InventoryConnection connection) {
        if (connection.inventoryPos == null) return;
        boolean other = connections.values().stream().anyMatch(candidate -> candidate != connection
                && candidate.status == StorageInventoryAdapter.Status.ONLINE && connection.inventoryPos.equals(candidate.inventoryPos));
        if (!other) index.remove(connection.inventoryPos);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("StorageFormat", 2);
        tag.putUUID("Id", id);
        tag.putString("Name", logicalName);
        tag.putBoolean("WarningLatched", warningLatched);
        tag.putBoolean("FullLatched", fullLatched);
        CompoundTag zoneTag = new CompoundTag();
        zones.forEach(zoneTag::putString);
        tag.put("Zones", zoneTag);
        if (owner != null) tag.putUUID("Owner", owner);
        if (networkId != null) tag.putUUID("HomeNetwork", networkId);
        if (controllerId != null && controllerPos != null) {
            tag.putUUID("ControllerId", controllerId);
            tag.putLong("ControllerPos", controllerPos.asLong());
        }
        ListTag nodeTags = new ListTag();
        for (CoverageNode node : coverage.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", node.id);
            entry.putLong("Pos", node.position.asLong());
            entry.putBoolean("Repeater", node.repeater);
            nodeTags.add(entry);
        }
        tag.put("Coverage", nodeTags);
        ListTag inventories = new ListTag();
        for (InventoryConnection connection : connections.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", connection.linkId);
            entry.putUUID("Source", connection.sourceId);
            entry.putLong("SourcePos", connection.linkPos.asLong());
            entry.putLong("InventoryPos", connection.inventoryPos.asLong());
            entry.putLong("AccessPos", connection.accessPos.asLong());
            entry.putString("Name", connection.name);
            entry.putString("Zone", connection.zone);
            inventories.add(entry);
        }
        tag.put("Inventories", inventories);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("Id")) id = tag.getUUID("Id");
        logicalName = savedName(tag.getString("Name"));
        warningLatched = tag.getBoolean("WarningLatched");
        fullLatched = tag.getBoolean("FullLatched");
        zones.clear();
        addBuiltinZones();
        CompoundTag zoneTag = tag.getCompound("Zones");
        for (String key : zoneTag.getAllKeys()) if (zones.size() < 64 && key.length() <= 64 && !key.isBlank()) zones.put(key, savedName(zoneTag.getString(key)));
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        networkId = tag.hasUUID("HomeNetwork") ? tag.getUUID("HomeNetwork") : null;
        controllerId = tag.hasUUID("ControllerId") ? tag.getUUID("ControllerId") : null;
        controllerPos = controllerId == null ? null : BlockPos.of(tag.getLong("ControllerPos"));
        coverage.clear();
        connections.clear();
        legacyMetadata.clear();
        for (var value : tag.getList("Coverage", 10)) {
            if (coverage.size() >= 256) break;
            CompoundTag entry = (CompoundTag) value;
            if (!entry.hasUUID("Id")) continue;
            CoverageNode node = new CoverageNode(entry.getUUID("Id"), BlockPos.of(entry.getLong("Pos")), entry.getBoolean("Repeater"));
            coverage.put(node.id, node);
        }
        for (var value : tag.getList("Inventories", 10)) {
            if (connections.size() >= 512) break;
            CompoundTag entry = (CompoundTag) value;
            if (!entry.hasUUID("Id") || !entry.hasUUID("Source")) continue;
            InventoryConnection connection = new InventoryConnection(entry.getUUID("Id"), entry.getUUID("Source"),
                    BlockPos.of(entry.getLong("SourcePos")), BlockPos.of(entry.getLong("InventoryPos")), BlockPos.of(entry.getLong("AccessPos")));
            connection.name = savedName(entry.getString("Name"));
            connection.zone = zones.containsKey(entry.getString("Zone")) ? entry.getString("Zone") : "misc";
            connections.put(connection.linkId, connection);
        }
        // Migration from the original one-link/one-inventory format.
        if (coverage.isEmpty()) for (var value : tag.getList("Links", 10)) {
            if (coverage.size() >= 256) break;
            CompoundTag entry = (CompoundTag) value;
            if (!entry.hasUUID("Id")) continue;
            UUID nodeId = entry.getUUID("Id");
            coverage.put(nodeId, new CoverageNode(nodeId, BlockPos.of(entry.getLong("Pos")), false));
            legacyMetadata.put(nodeId, new LegacyMetadata(savedName(entry.getString("Name")), entry.getString("Zone")));
        }
        coverageDirty = true;
    }

    private static String validName(String name) {
        if (name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid name");
        return name.strip();
    }
    private static String savedName(String name) {
        return name.codePoints().filter(codepoint -> !Character.isISOControl(codepoint)).limit(64)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
    }

    @Override public Component getDisplayName() {
        return logicalName.isBlank() ? getBlockState().getBlock().getName() : Component.literal(logicalName);
    }
    @Override public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new StorageMenu(containerId, inventory, worldPosition);
    }
}
