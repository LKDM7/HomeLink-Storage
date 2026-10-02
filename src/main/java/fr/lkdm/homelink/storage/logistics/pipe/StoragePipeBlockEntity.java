package fr.lkdm.homelink.storage.logistics.pipe;

import fr.lkdm.homecore.api.item.ItemApi;
import fr.lkdm.homecore.api.item.ItemPort;
import fr.lkdm.homelink.storage.config.StorageConfig;
import fr.lkdm.homelink.storage.logistics.filter.PipeFaceConfig;
import fr.lkdm.homelink.storage.logistics.network.PipeNetworkManager;
import fr.lkdm.homelink.storage.logistics.network.PipeStatus;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent data of one pipe: its owner and the settings of each face touching a container.
 * It never holds items: cargo belongs to the {@link fr.lkdm.homelink.storage.logistics.transit.TransitLedger}.
 * Clients only receive the lamp state, never filters or settings.
 */
public final class StoragePipeBlockEntity extends BlockEntity {
    private static final int FORMAT = 1;
    private @Nullable UUID owner;
    private final Map<Direction, PipeFaceConfig> faces = new EnumMap<>(Direction.class);
    private PipeStatus.Lamp lamp = PipeStatus.Lamp.OFF;
    private final Map<Direction, BlockCapabilityCache<ItemPort, @Nullable Direction>> ports = new EnumMap<>(Direction.class);
    private final Map<Direction, BlockCapabilityCache<IItemHandler, @Nullable Direction>> handlers = new EnumMap<>(Direction.class);

    public StoragePipeBlockEntity(BlockPos pos, BlockState state) { super(StorageRegistries.PIPE_ENTITY.get(), pos, state); }

    public @Nullable UUID owner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; setChanged(); }
    public PipeStatus.Lamp lamp() { return lamp; }

    public @Nullable PipeFaceConfig config(Direction direction) { return faces.get(direction); }
    public PipeFaceConfig configOrCreate(Direction direction) { return faces.computeIfAbsent(direction, ignored -> new PipeFaceConfig()); }
    public void configChanged() { setChanged(); }

    /** Server: the block on one side changed. Replacing or removing a container disarms its face. */
    void neighbourChanged(Direction direction, PipeConnection before, PipeConnection after, BlockState neighbour) {
        if (before != PipeConnection.CONTAINER) return;
        PipeFaceConfig config = faces.get(direction);
        if (config == null) return;
        String block = blockId(neighbour.getBlock());
        if ((after != PipeConnection.CONTAINER || !config.target().isEmpty() && !config.target().equals(block)) && config.disarm()) setChanged();
    }

    public static String blockId(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }

    /** HomeCore port of the neighbour on that side, through an invalidation-aware cache that never loads chunks. */
    public @Nullable ItemPort port(Direction direction) {
        if (!(level instanceof ServerLevel server)) return null;
        return ports.computeIfAbsent(direction, side -> BlockCapabilityCache.create(ItemApi.BLOCK, server, worldPosition.relative(side),
                side.getOpposite(), () -> !isRemoved(), this::capabilityInvalidated)).getCapability();
    }

    /** Ordinary item handler of the neighbour, on the face actually touching this pipe. */
    public @Nullable IItemHandler handler(Direction direction) {
        if (!(level instanceof ServerLevel server)) return null;
        return handlers.computeIfAbsent(direction, side -> BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, server,
                worldPosition.relative(side), side.getOpposite(), () -> !isRemoved(), this::capabilityInvalidated)).getCapability();
    }

    private void capabilityInvalidated() {
        if (level instanceof ServerLevel server && !isRemoved()) {
            PipeNetworkManager.get(server).capabilityChanged(worldPosition);
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    /** Server: shows a new circuit state on this pipe's light. Sent only when it actually changes. */
    public void setLamp(PipeStatus.Lamp value) {
        if (lamp == value || level == null) return;
        lamp = value;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            PipeNetworkManager.get(server).pipeLoaded(worldPosition);
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    @Override public void onChunkUnloaded() {
        if (level instanceof ServerLevel server) PipeNetworkManager.get(server).pipeUnloaded(worldPosition);
        super.onChunkUnloaded();
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("PipeFormat", FORMAT);
        if (owner != null) tag.putUUID("Owner", owner);
        CompoundTag saved = new CompoundTag();
        faces.forEach((direction, config) -> saved.put(direction.getSerializedName(), config.save()));
        tag.put("Faces", saved);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Lamp")) {
            int value = tag.getByte("Lamp");
            lamp = value >= 0 && value < PipeStatus.Lamp.values().length ? PipeStatus.Lamp.values()[value] : PipeStatus.Lamp.OFF;
        }
        if (!tag.contains("Faces")) return;
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        faces.clear();
        CompoundTag saved = tag.getCompound("Faces");
        int maximum = StorageConfig.pipe(StorageConfig.PIPE_MAX_FILTER);
        for (Direction direction : Direction.values())
            if (saved.contains(direction.getSerializedName())) faces.put(direction, PipeFaceConfig.load(saved.getCompound(direction.getSerializedName()), maximum));
    }

    /** Lamp only: filters and settings stay on the server. */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putByte("Lamp", (byte) lamp.ordinal());
        return tag;
    }

    @Override public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
