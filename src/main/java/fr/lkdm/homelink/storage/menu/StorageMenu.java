package fr.lkdm.homelink.storage.menu;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import fr.lkdm.homelink.storage.network.StorageData;
import fr.lkdm.homelink.storage.network.StoragePackets;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.storage.index.StorageIndex;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import fr.lkdm.homelink.storage.storage.inventory.StorageWithdrawal;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;

/** Terminal item browser or Controller management view; every command is server-authorized for its block. */
public final class StorageMenu extends AbstractContainerMenu {
    /** Distinct variants one recipe-viewer request may fetch. */
    public static final int MAX_BATCH = 16;
    private final BlockPos pos;
    private final StorageBlockEntity source;
    private final ServerPlayer viewer;
    private final Map<Long, Long> sent = new HashMap<>();
    private final ArrayDeque<StorageIndex.Entry> pendingRows = new ArrayDeque<>();
    private final ArrayDeque<StorageData.Location> pendingLocations = new ArrayDeque<>();
    private final ArrayDeque<Long> pendingRemoved = new ArrayDeque<>();
    private CompoundTag pendingHeader;
    private boolean hasPending() { return pendingHeader != null || !pendingLocations.isEmpty() || !pendingRemoved.isEmpty() || !pendingRows.isEmpty(); }
    private void clearPending() { pendingHeader = null; pendingLocations.clear(); pendingRemoved.clear(); pendingRows.clear(); }
    private final Map<Long, StorageData.Row> rows = new LinkedHashMap<>();
    private final Map<UUID, StorageData.Location> locations = new LinkedHashMap<>();
    private final Map<String, String> zones = new LinkedHashMap<>();
    private StorageData.Stats stats = new StorageData.Stats(0,0,0,0,0,0,false);
    private long clientRevision;
    private String clientName = "";
    public String clientName() { return clientName; }
    private long sentIndex = -1, sentMetadata = -1;
    private UUID sentController;
    private boolean first = true;
    /** Hash of the last inventory list sent; 0 forces a resend. */
    private int sentLocations;
    /** Set by a command: the answer is sent in the same tick instead of waiting for the next cycle. */
    private boolean urgent;
    private static final int URGENT_FRAGMENTS = 8;
    private int ticks;
    private long lastCommand = Long.MIN_VALUE;
    public StorageMenu(int id, Inventory inventory, RegistryFriendlyByteBuf data) { this(id, inventory, data.readBlockPos()); }
    public StorageMenu(int id, Inventory inventory, BlockPos pos) {
        super(StorageRegistries.STORAGE_MENU.get(), id);
        this.pos = pos.immutable();
        source = inventory.player.level().getBlockEntity(pos) instanceof StorageBlockEntity storage ? storage : null;
        viewer = inventory.player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }
    public Collection<StorageData.Row> clientRows() { return Collections.unmodifiableCollection(rows.values()); }
    public Collection<StorageData.Location> clientLocations() { return Collections.unmodifiableCollection(locations.values()); }
    public Map<String, String> clientZones() { return Collections.unmodifiableMap(zones); }
    public StorageData.Stats clientStats() { return stats; }
    public long clientRevision() { return clientRevision; }
    public void send(String action, String target, String value) { PacketDistributor.sendToServer(new StoragePackets.Command(containerId, action, target, value)); }
    public StorageBlockEntity controller() { return source == null ? null : source.controller(); }

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer == null || !stillValid(viewer)) return;
        // Every other tick, or right away after a command such as a withdrawal.
        if (++ticks % 2 != 0 && !urgent) return;
        StorageBlockEntity controller = controller();
        if (controller != null && !controller.canAccess(viewer)) { viewer.closeContainer(); return; }
        UUID identity = controller == null ? null : controller.id();
        if (!Objects.equals(sentController, identity)) { first = true; clearPending(); sent.clear(); sentIndex = -1; sentMetadata = -1; sentLocations = 0; sentController = identity; }
        // Two revision comparisons when nothing changed, so checking often costs nothing.
        if (!hasPending()) prepare(controller);
        for (int fragments = 0; hasPending() && fragments < (urgent ? URGENT_FRAGMENTS : 1); fragments++)
            PacketDistributor.sendToPlayer(viewer, new StoragePackets.Data(containerId, nextPacket()));
        urgent = false;
    }

    private void prepare(StorageBlockEntity controller) {
        long revision = controller == null ? 0 : controller.index().revision();
        long metadata = controller == null ? 0 : controller.metadataRevision();
        if (!first && sentIndex == revision && sentMetadata == metadata) return;
        CompoundTag packet = new CompoundTag(); packet.putBoolean("Reset", first); first = false;
        packet.putBoolean("Connected", controller != null);
        CompoundTag zoneTag = new CompoundTag();
        if (controller != null) {
            packet.putString("Name", controller.logicalName());
            var index = controller.index();
            packet.putLong("Items", index.totalItems()); packet.putInt("Unique", index.uniqueItems()); packet.putInt("Inventories", index.inventoryCount());
            packet.putInt("Occupied", index.occupiedSlots()); packet.putInt("Slots", index.totalSlots()); packet.putInt("Full", index.fullInventories());
            controller.zones().forEach(zoneTag::putString);
            // The inventory list is resent only when one of its entries changed, not on every item change.
            List<StorageData.Location> current = new ArrayList<>(controller.connections().size());
            for (var connection : controller.connections().values()) {
                current.add(new StorageData.Location(connection.linkId,
                        connection.inventoryPos == null ? connection.linkPos : connection.inventoryPos,
                        connection.name, connection.zone, connection.status.name()));
            }
            int locationsHash = current.hashCode();
            if (first || locationsHash != sentLocations) {
                pendingLocations.addAll(current);
                packet.putBoolean("LocationsReset", true);
                sentLocations = locationsHash;
            }
            Set<Long> alive = new HashSet<>();
            // The Controller manages inventories and zones; item rows are only sent to Terminals.
            if (!managesNetwork()) for (var entry : index.entries()) {
                alive.add(entry.id());
                if (!Objects.equals(sent.get(entry.id()), entry.revision())) pendingRows.add(entry);
            }
            sent.keySet().stream().filter(id -> !alive.contains(id)).forEach(pendingRemoved::add);
            sent.keySet().retainAll(alive);
        } else { sent.clear(); sentLocations = 0; packet.putBoolean("Reset", true); }
        packet.putBoolean("Header", true); packet.put("Zones", zoneTag);
        pendingHeader = packet; sentIndex = revision; sentMetadata = metadata;
    }

    /** Exact encoded NBT size; only the next fragment is materialized. */
    private static int encodedBytes(CompoundTag tag) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(tag, new java.io.DataOutputStream(bytes));
            return bytes.size();
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private CompoundTag nextPacket() {
        if (pendingHeader != null) { CompoundTag header = pendingHeader; pendingHeader = null; return header; }
        CompoundTag packet = new CompoundTag();
        int limit = StorageConfig.MAX_PACKET_BYTES.get() - 1024;
        int rowLimit = StorageConfig.NETWORK_ROWS.get();
        // Item changes first: a withdrawal must not wait behind the inventory list.
        if (!pendingRemoved.isEmpty()) {
            int count = Math.min(pendingRemoved.size(), Math.min(rowLimit * 16, limit / 16));
            long[] removed = new long[count]; for (int i = 0; i < count; i++) removed[i] = pendingRemoved.removeFirst();
            packet.putLongArray("Removed", removed); return packet;
        }
        if (pendingRows.isEmpty()) {
            ListTag locationBatch = new ListTag(); packet.put("Locations", locationBatch);
            int size = encodedBytes(packet);
            while (!pendingLocations.isEmpty() && locationBatch.size() < rowLimit) {
                var location = pendingLocations.peekFirst();
                CompoundTag tag = new CompoundTag(); tag.putUUID("Id", location.linkId()); tag.putLong("Pos", location.position().asLong());
                tag.putString("Name", location.name()); tag.putString("Zone", location.zone()); tag.putString("Status", location.status());
                // Each element's standalone size bounds its list encoding, so the running sum never undercounts.
                int bytes = encodedBytes(tag);
                if (size + bytes >= limit) break;
                locationBatch.add(tag); pendingLocations.removeFirst(); size += bytes;
            }
            return packet;
        }
        ListTag batch = new ListTag(); packet.put("Rows", batch);
        int size = encodedBytes(packet);
        while (!pendingRows.isEmpty() && batch.size() < rowLimit) {
            var entry = pendingRows.peekFirst();
            CompoundTag row = new CompoundTag(); row.putLong("Id", entry.id()); row.putLong("Count", entry.total());
            var stack = entry.display().save(viewer.registryAccess());
            if (stack.sizeInBytes() > Math.min(StorageConfig.MAX_COMPONENT_BYTES.get(), limit / 4)) {
                ItemStack neutral = new ItemStack(entry.display().getItem());
                neutral.set(DataComponents.CUSTOM_NAME, Component.translatable("screen.homelink_storage.complex_variant", entry.id()));
                stack = neutral.save(viewer.registryAccess());
                if (stack.sizeInBytes() > Math.min(StorageConfig.MAX_COMPONENT_BYTES.get(), limit / 4)) {
                    neutral = new ItemStack(net.minecraft.world.item.Items.PAPER);
                    neutral.set(DataComponents.CUSTOM_NAME, Component.translatable("screen.homelink_storage.complex_variant", entry.id()));
                    stack = neutral.save(viewer.registryAccess());
                }
            }
            row.put("Stack", stack);
            ListTag counts = new ListTag();
            entry.locations().forEach((position, count) -> { CompoundTag loc = new CompoundTag(); loc.putLong("Pos", position.asLong()); loc.putLong("Count", count); counts.add(loc); });
            row.put("Locations", counts);
            int bytes = encodedBytes(row);
            if (!batch.isEmpty() && size + bytes >= limit) break;
            batch.add(row); pendingRows.removeFirst(); sent.put(entry.id(), entry.revision()); size += bytes;
        }
        return packet;
    }

    public void applyData(CompoundTag data, HolderLookup.Provider registries) {
        if (data.contains("Locate")) {
            fr.lkdm.homelink.storage.network.LocateState.accept(BlockPos.of(data.getLong("Locate")), data.getString("Dimension"), data.getInt("Duration"));
            return;
        }
        if (data.getBoolean("Reset")) { rows.clear(); locations.clear(); zones.clear(); }
        if (data.getBoolean("Header")) {
            clientName = data.getString("Name");
            stats = new StorageData.Stats(data.getLong("Items"), data.getInt("Unique"), data.getInt("Inventories"), data.getInt("Occupied"), data.getInt("Slots"), data.getInt("Full"), data.getBoolean("Connected"));
            if (data.getBoolean("LocationsReset")) locations.clear();
            zones.clear();
            CompoundTag zoneTag = data.getCompound("Zones"); for (String key : zoneTag.getAllKeys()) zones.put(key, zoneTag.getString(key));
        }
        for (var value : data.getList("Locations", 10)) { CompoundTag loc = (CompoundTag) value; UUID id = loc.getUUID("Id"); locations.put(id, new StorageData.Location(id, BlockPos.of(loc.getLong("Pos")), loc.getString("Name"), loc.getString("Zone"), loc.getString("Status"))); }
        for (long id : data.getLongArray("Removed")) rows.remove(id);
        for (var value : data.getList("Rows", 10)) {
            CompoundTag row = (CompoundTag) value; Map<BlockPos, Long> counts = new LinkedHashMap<>();
            for (var entry : row.getList("Locations", 10)) { CompoundTag loc = (CompoundTag) entry; counts.put(BlockPos.of(loc.getLong("Pos")), loc.getLong("Count")); }
            long id = row.getLong("Id"); ItemStack stack = ItemStack.parseOptional(registries, row.getCompound("Stack"));
            if (!stack.isEmpty()) rows.put(id, new StorageData.Row(id, stack, row.getLong("Count"), Map.copyOf(counts)));
        }
        clientRevision++;
    }

    public void command(ServerPlayer player, String action, String target, String value) {
        long time = player.serverLevel().getGameTime();
        if (lastCommand != Long.MIN_VALUE && time - lastCommand < 4) return;
        lastCommand = time;
        StorageBlockEntity controller = controller();
        if (!stillValid(player) || controller == null || !controller.canAccess(player)) return;
        if (!allowed(action, managesNetwork())) return;
        var permission = action.equals("locate") ? fr.lkdm.homecore.api.security.Permission.VIEW
                : action.equals("refresh") || action.startsWith("withdraw") ? fr.lkdm.homecore.api.security.Permission.CONTROL : fr.lkdm.homecore.api.security.Permission.CONFIGURE;
        if (!controller.permission(player, permission)) return;
        try {
            switch (action) {
                case "rename_controller" -> controller.setLogicalName(value);
                case "rename_inventory" -> controller.setInventoryName(UUID.fromString(target), value);
                case "set_zone" -> controller.setZone(UUID.fromString(target), value);
                case "create_zone" -> controller.createZone(value);
                case "forget_inventory" -> controller.forget(UUID.fromString(target));
                case "refresh" -> controller.markIndexDirty();
                case "locate" -> locate(player, controller, UUID.fromString(target));
                case "withdraw" -> {
                    String[] parts = value.split(":", -1);
                    if (parts.length != 2 || !controller.id().equals(sentController)) return;
                    long rowId = Long.parseLong(parts[0]);
                    if (!sent.containsKey(rowId)) return;
                    int count = StorageWithdrawal.withdraw(
                            player, controller, UUID.fromString(target), rowId, Integer.parseInt(parts[1]));
                    player.displayClientMessage(Component.translatable(count > 0
                            ? "message.homelink_storage.withdrawn" : "message.homelink_storage.withdraw_failed", count), true);
                }
                case "withdraw_any" -> {
                    // target: preferred inventory or empty; value: "row:amount".
                    if (!controller.id().equals(sentController)) return;
                    UUID preferred = target.isEmpty() ? null : UUID.fromString(target);
                    String[] parts = value.split(":", -1);
                    if (parts.length != 2) return;
                    long rowId = Long.parseLong(parts[0]);
                    if (!sent.containsKey(rowId)) return;
                    int count = StorageWithdrawal.withdrawAny(player, controller, preferred, rowId, Integer.parseInt(parts[1]));
                    player.displayClientMessage(Component.translatable(count > 0
                            ? "message.homelink_storage.withdrawn" : "message.homelink_storage.withdraw_failed", count), true);
                }
                case "withdraw_batch" -> {
                    // value: "row:amount;row:amount..." planned by a recipe viewer; bounded in size and total.
                    if (!controller.id().equals(sentController)) return;
                    String[] requests = value.split(";", -1);
                    if (requests.length > MAX_BATCH) return;
                    Map<Long, Integer> planned = new LinkedHashMap<>();
                    for (String request : requests) {
                        String[] parts = request.split(":", -1);
                        if (parts.length != 2) return;
                        long rowId = Long.parseLong(parts[0]);
                        int amount = Integer.parseInt(parts[1]);
                        if (!sent.containsKey(rowId) || amount < 1) return;
                        planned.merge(rowId, amount, Integer::sum);
                    }
                    if (planned.values().stream().mapToLong(Integer::longValue).sum() > StorageWithdrawal.MAX_REQUEST) return;
                    int requested = 0, count = 0;
                    for (var request : planned.entrySet()) {
                        requested += request.getValue();
                        count += StorageWithdrawal.withdrawAny(player, controller, null, request.getKey(), request.getValue());
                    }
                    player.displayClientMessage(Component.translatable(count == 0 ? "message.homelink_storage.withdraw_failed"
                            : count < requested ? "message.homelink_storage.ingredients_partial" : "message.homelink_storage.ingredients_withdrawn",
                            count, requested), true);
                }
                default -> { }
            }
        } catch (IllegalArgumentException ignored) { /* Untrusted bounded input: reject without changing other state. */ }
        urgent = true; // Answer this command with the next broadcast, in the same tick.
    }
    private void locate(ServerPlayer player, StorageBlockEntity controller, UUID linkId) {
        var connection = controller.connections().get(linkId);
        var level = player.serverLevel();
        if (connection == null || connection.status != StorageInventoryAdapter.Status.ONLINE
                || !controller.isCoverageActive(connection.sourceId)
                || level.getChunkSource().getChunkNow(new net.minecraft.world.level.ChunkPos(connection.accessPos).x,
                        new net.minecraft.world.level.ChunkPos(connection.accessPos).z) == null) {
            player.displayClientMessage(Component.translatable("message.homelink_storage.inventory_offline"), true); return;
        }
        StorageInventoryAdapter.Resolution resolved;
        try { resolved = StorageInventoryAdapter.resolveAny(level, connection.accessPos); }
        catch (RuntimeException unavailable) {
            player.displayClientMessage(Component.translatable("message.homelink_storage.inventory_offline"), true); return;
        }
        if (resolved.adapter() == null || !resolved.adapter().identity().equals(connection.inventoryPos)) {
            player.displayClientMessage(Component.translatable("message.homelink_storage.inventory_offline"), true); return;
        }
        CompoundTag response = new CompoundTag(); response.putLong("Locate", resolved.adapter().identity().asLong());
        response.putString("Dimension", level.dimension().location().toString()); response.putInt("Duration", StorageConfig.LOCATE_DURATION.get());
        PacketDistributor.sendToPlayer(player, new StoragePackets.Data(containerId, response));
    }
    public BlockPos position() { return pos; }

    /** Opened on the Controller: network management. Otherwise opened on a Terminal: item browsing. */
    public boolean managesNetwork() { return source != null && source.isController(); }

    private static final Set<String> CONTROLLER_ACTIONS = Set.of("rename_controller", "rename_inventory", "set_zone",
            "create_zone", "forget_inventory", "refresh");
    private static final Set<String> TERMINAL_ACTIONS = Set.of("locate", "withdraw", "withdraw_any", "withdraw_batch");

    /** Each block only accepts its own role's commands, whatever a client sends. */
    public static boolean allowed(String action, boolean controller) {
        return controller ? CONTROLLER_ACTIONS.contains(action) : TERMINAL_ACTIONS.contains(action);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) {
        return player.level().isClientSide || (source != null && !source.isRemoved() && (source.isTerminal() || source.isController()) && player instanceof ServerPlayer serverPlayer && source.canAccess(serverPlayer)
                && player.level().getBlockEntity(pos) == source && player.distanceToSqr(pos.getCenter()) <= 64);
    }
}
