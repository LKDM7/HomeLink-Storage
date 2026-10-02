package fr.lkdm.homelink.storage.storage.inventory;

import fr.lkdm.homelink.storage.config.StorageConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * A short-lived view of a loaded inventory. Resolve again before each inspection:
 * capability handlers must not be retained across block changes or chunk unloads.
 * Item stacks returned by the handler belong to the inventory and must not be mutated.
 * Identities are scoped to the controller's dimension.
 */
public final class StorageInventoryAdapter {
    public enum Status {
        ONLINE, OFFLINE, INVENTORY_MISSING, UNLOADED, INVALID
    }

    public record Resolution(Status status, @Nullable StorageInventoryAdapter adapter) {
        public Resolution {
            if ((status == Status.ONLINE) != (adapter != null)) {
                throw new IllegalArgumentException("Only ONLINE resolutions have an adapter");
            }
        }
    }

    private final BlockPos identity;
    private final BlockPos position;
    private final IItemHandler handler;
    private final IItemHandler extractionHandler;

    private StorageInventoryAdapter(BlockPos identity, BlockPos position, IItemHandler handler, IItemHandler extractionHandler) {
        this.identity = identity.immutable();
        this.position = position.immutable();
        this.handler = handler;
        this.extractionHandler = extractionHandler;
    }

    public BlockPos identity() {
        return identity;
    }

    public BlockPos position() {
        return position;
    }

    public IItemHandler handler() {
        return handler;
    }

    public int slots() {
        return handler.getSlots();
    }

    /** Preserve the capability provider's extraction restrictions, even when the read view is combined. */
    public IItemHandler extractionHandler() { return extractionHandler; }

    public static Resolution resolve(ServerLevel level, BlockPos inventoryPos, @Nullable Direction accessFace) {
        if (level.isOutsideBuildHeight(inventoryPos)) {
            return unavailable(Status.INVALID);
        }
        if (!level.hasChunkAt(inventoryPos)) {
            return unavailable(Status.UNLOADED);
        }

        BlockState state = level.getBlockState(inventoryPos);
        BlockPos identity = inventoryPos;
        Container combinedChest = null;
        if (state.getBlock() instanceof ChestBlock chest) {
            // Check both halves before asking vanilla's combiner or capability provider.
            // Those APIs access the neighbour and could otherwise load its chunk.
            if (!(level.getBlockEntity(inventoryPos) instanceof ChestBlockEntity)) {
                return unavailable(Status.INVENTORY_MISSING);
            }
            ChestType type = state.getValue(ChestBlock.TYPE);
            if (type != ChestType.SINGLE) {
                BlockPos partnerPos = inventoryPos.relative(ChestBlock.getConnectedDirection(state));
                if (!level.hasChunkAt(partnerPos)) {
                    return unavailable(Status.UNLOADED);
                }
                BlockState partner = level.getBlockState(partnerPos);
                if (!partner.is(state.getBlock())
                        || partner.getValue(ChestBlock.TYPE) != type.getOpposite()
                        || partner.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                        || !partnerPos.relative(ChestBlock.getConnectedDirection(partner)).equals(inventoryPos)
                        || !(level.getBlockEntity(partnerPos) instanceof ChestBlockEntity)) {
                    return unavailable(Status.INVALID);
                }
                identity = inventoryPos.compareTo(partnerPos) <= 0 ? inventoryPos : partnerPos;
            }
            // Like item capabilities, storage access does not open the lid. The
            // combined handler remains unavailable until BOTH halves are loaded.
            combinedChest = ChestBlock.getContainer(chest, state, level, inventoryPos, true);
            if (combinedChest == null) {
                return unavailable(Status.INVALID);
            }
        }

        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, inventoryPos, accessFace);
        if (handler == null) {
            return unavailable(Status.INVENTORY_MISSING);
        }
        IItemHandler extractionHandler = handler;
        // NeoForge's vanilla chest provider already combines both halves. Preserve
        // the standard capability; adapt only providers exposing a single half.
        if (combinedChest != null && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && handler.getSlots() * 2 == combinedChest.getContainerSize()) {
            handler = new InvWrapper(combinedChest);
        }
        if (handler.getSlots() <= 0 || handler.getSlots() > StorageConfig.maxSlots()) {
            return unavailable(Status.INVALID);
        }
        return new Resolution(Status.ONLINE, new StorageInventoryAdapter(identity, inventoryPos, handler, extractionHandler));
    }

    /**
     * Physical identity of the inventory at a loaded position, by the same rules as
     * {@link #resolve}: both halves of a double chest share the lower position. Returns null
     * when a double chest's partner is unloaded or inconsistent, without loading its chunk.
     * Unlike {@link #resolveAny}, this never chooses a capability face.
     */
    public static @Nullable BlockPos identity(ServerLevel level, BlockPos inventoryPos) {
        if (level.isOutsideBuildHeight(inventoryPos) || !level.isLoaded(inventoryPos)) return null;
        BlockState state = level.getBlockState(inventoryPos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return inventoryPos.immutable();
        BlockPos partnerPos = inventoryPos.relative(ChestBlock.getConnectedDirection(state));
        if (!level.isLoaded(partnerPos)) return null;
        BlockState partner = level.getBlockState(partnerPos);
        if (!partner.is(state.getBlock()) || partner.getValue(ChestBlock.TYPE) != state.getValue(ChestBlock.TYPE).getOpposite()
                || partner.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                || !partnerPos.relative(ChestBlock.getConnectedDirection(partner)).equals(inventoryPos)) return null;
        return (inventoryPos.compareTo(partnerPos) <= 0 ? inventoryPos : partnerPos).immutable();
    }

    /** Resolves a monitored block without assuming which side exposes its item capability. */
    public static Resolution resolveAny(ServerLevel level, BlockPos inventoryPos) {
        Resolution neutral = resolve(level, inventoryPos, null);
        if (neutral.status() == Status.ONLINE || neutral.status() == Status.UNLOADED || neutral.status() == Status.INVALID) return neutral;
        for (Direction direction : Direction.values()) {
            Resolution sided = resolve(level, inventoryPos, direction);
            if (sided.status() == Status.ONLINE || sided.status() == Status.UNLOADED || sided.status() == Status.INVALID) return sided;
        }
        return neutral;
    }

    private static Resolution unavailable(Status status) {
        return new Resolution(status, null);
    }
}
