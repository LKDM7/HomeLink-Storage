package fr.lkdm.homelink.storage.logistics.network;

import fr.lkdm.homecore.api.item.ItemPortType;
import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlock;
import fr.lkdm.homelink.storage.logistics.pipe.StoragePipeBlockEntity;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * A container face reached by a pipe. The access address is the pipe and its side; the
 * identity is the physical inventory behind it (both halves of a double chest share one).
 * {@code port} is the HomeCore direction, or null for a plain item handler whose slots decide.
 * Handlers are never kept: {@link #live} resolves the current one on every use.
 */
public record PipeEndpoint(BlockPos pipe, Direction side, BlockPos target, BlockPos identity,
                           @Nullable ItemPortType port, String block) {
    private static final java.util.Map<ServerLevel, java.util.LinkedHashMap<Key, Long>> REJECTED = new java.util.WeakHashMap<>();
    public record Live(PipeEndpoint endpoint, IItemHandler handler) { }

    /** Stable key of the access address. */
    public record Key(BlockPos pipe, Direction side) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int order = pipe.compareTo(other.pipe);
            return order != 0 ? order : Integer.compare(side.get3DDataValue(), other.side.get3DDataValue());
        }
    }
    public Key key() { return new Key(pipe, side); }

    /**
     * Resolves the face of the neighbour that actually touches the pipe. A HomeCore port always
     * wins and is never bypassed through a more permissive handler; without a port, the ordinary
     * handler of that same face is used. A null side is never queried, chunks are never loaded.
     */
    public static @Nullable Live resolve(ServerLevel level, BlockPos pipePos, Direction side) {
        var rejected = REJECTED.computeIfAbsent(level, ignored -> new java.util.LinkedHashMap<>());
        var key = new Key(pipePos.immutable(), side);
        long now = level.getGameTime();
        if (rejected.getOrDefault(key, 0L) > now) return null;
        try { return resolveChecked(level, pipePos, side); }
        catch (RuntimeException brokenCapability) {
            rejected.put(key, now + 1200);
            if (rejected.size() > 1024) rejected.remove(rejected.keySet().iterator().next());
            com.mojang.logging.LogUtils.getLogger().warn("Storage Pipe capability quarantined at {} / {} in {}", pipePos, side, level.dimension().location(), brokenCapability);
            return null;
        }
    }

    private static @Nullable Live resolveChecked(ServerLevel level, BlockPos pipePos, Direction side) {
        BlockPos target = pipePos.relative(side);
        if (!level.isLoaded(pipePos) || !level.isLoaded(target)) return null;
        if (!(LoadedBlocks.entity(level, pipePos) instanceof StoragePipeBlockEntity pipe) || pipe.isRemoved()) return null;
        var state = LoadedBlocks.state(level, target);
        if (state.getBlock() instanceof StoragePipeBlock) return null;
        // The Controller coordinates, and Terminals, Links and Repeaters hold no items: none is a container.
        if (LoadedBlocks.entity(level, target) instanceof StorageBlockEntity storage && !(storage instanceof DepositBlockEntity)) return null;
        var port = pipe.port(side);
        IItemHandler handler = port != null ? port : pipe.handler(side);
        if (handler == null) return null;
        int slots = handler.getSlots();
        if (slots <= 0 || slots > StorageConfig.maxSlots()) return null;
        BlockPos identity = StorageInventoryAdapter.identity(level, target);
        if (identity == null) return null;
        return new Live(new PipeEndpoint(pipePos.immutable(), side, target.immutable(), identity,
                port == null ? null : port.type(), StoragePipeBlockEntity.blockId(state.getBlock())), handler);
    }

    /** Re-resolves this endpoint; null when the container, its port or its identity changed. */
    public @Nullable Live live(ServerLevel level) {
        Live live = resolve(level, pipe, side);
        if (live == null || !live.endpoint().identity.equals(identity) || live.endpoint().port != port || !live.endpoint().block.equals(block)) return null;
        return live;
    }
}
