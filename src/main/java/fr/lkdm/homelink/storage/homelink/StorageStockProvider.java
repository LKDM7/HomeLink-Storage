package fr.lkdm.homelink.storage.homelink;

import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homecore.api.stock.StockAccess;
import fr.lkdm.homecore.api.stock.StockAvailability;
import fr.lkdm.homecore.api.stock.StockEntry;
import fr.lkdm.homecore.api.stock.StockProvider;
import fr.lkdm.homecore.api.stock.StockRequest;
import fr.lkdm.homecore.api.stock.StockSnapshot;
import fr.lkdm.homecore.api.stock.StockSourceId;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.storage.index.StorageIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Read-only answer to a stock question about one controller's own network.
 *
 * <p>Nothing here extracts, moves, reserves or rescans anything: the answer is read
 * from the index the controller already maintains. Quantities are attributed to the
 * canonical identity of each physical inventory, so a consumer aggregating two
 * controllers that cover the same chest counts it once instead of twice.</p>
 */
public final class StorageStockProvider implements StockProvider {
    private final StorageBlockEntity entity;

    /** Binds a provider to the controller that owns the index.
     * @param entity controller block entity
     */
    public StorageStockProvider(StorageBlockEntity entity) {
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    @Override
    public StockSnapshot observe(StockRequest request) {
        Objects.requireNonNull(request, "request");
        if (!(entity.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return StockSnapshot.unavailable(0L);
        }
        long tick = level.getGameTime();
        // A controller answers only about the network it is actually bound to.
        if (entity.isRemoved() || !entity.isController() || !request.network().equals(entity.networkId())) {
            return StockSnapshot.unavailable(tick);
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(request.player());
        // permission() re-checks membership, the HomeCore VIEW right and reachability together.
        if (player == null || !entity.permission(player, Permission.VIEW)) return StockSnapshot.unavailable(tick);
        // An unpowered controller keeps stale counts: they prove nothing about what is reachable.
        if (!entity.powered()) return StockSnapshot.unavailable(tick);

        StockAccess access = entity.permission(player, Permission.CONTROL)
                ? StockAccess.READ_AND_WITHDRAW : StockAccess.READ_ONLY;
        ResourceLocation dimension = level.dimension().location();
        // Inspect only the bounded topology already maintained by Storage, never inventory slots.
        Map<BlockPos, Long> accessible = new LinkedHashMap<>();
        boolean partial = !entity.indexComplete() || entity.discoveryTruncated();
        long oldest = tick;
        long maxAge = Math.max(20L, fr.lkdm.homelink.storage.config.StorageConfig.RESCAN_INTERVAL.get() * 2L);
        for (var connection : entity.connections().values()) {
            var source = entity.coverageNodes().get(connection.sourceId);
            if (connection.status != fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter.Status.ONLINE
                    || source == null || !entity.isCoverageActive(connection.sourceId)
                    || !level.hasChunkAt(source.position) || !level.hasChunkAt(connection.accessPos)
                    || !level.hasChunkAt(connection.inventoryPos)
                    || connection.observedTick < 0 || tick - connection.observedTick > maxAge) {
                partial = true;
                continue;
            }
            var loaded = fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter.resolveAny(level, connection.accessPos);
            if (!(level.getBlockEntity(source.position) instanceof StorageBlockEntity coverage)
                    || !coverage.id().equals(connection.sourceId) || coverage.controller() != entity
                    || loaded.adapter() == null || !loaded.adapter().identity().equals(connection.inventoryPos)) {
                partial = true;
                continue;
            }
            accessible.merge(connection.inventoryPos, connection.observedTick, Math::max);
            oldest = Math.min(oldest, connection.observedTick);
        }
        List<StockEntry> entries = new ArrayList<>();
        var reported = new LinkedHashSet<Long>();
        for (ItemStack variant : request.variants()) {
            StorageIndex.Entry found = entity.index().find(variant);
            // A variant the index does not hold is simply absent from the answer.
            if (found == null || !reported.add(found.id())) continue;
            Map<StockSourceId, Long> bySource = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, Long> location : found.locations().entrySet()) {
                if (location.getValue() > 0 && accessible.containsKey(location.getKey()))
                    bySource.put(new StockSourceId(dimension, location.getKey()), location.getValue());
                else if (location.getValue() > 0) partial = true;
            }
            if (!bySource.isEmpty()) entries.add(new StockEntry(found.display(), bySource));
        }
        // Truncated discovery or an inventory still to be scanned makes absence unprovable.
        StockAvailability availability = partial ? StockAvailability.PARTIAL : StockAvailability.COMPLETE;
        return new StockSnapshot(availability, access, entries, entity.index().revision(), oldest);
    }
}
