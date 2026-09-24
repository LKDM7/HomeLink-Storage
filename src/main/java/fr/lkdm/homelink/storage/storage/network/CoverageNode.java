package fr.lkdm.homelink.storage.storage.network;

import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Persisted network node. Reachability is recomputed from loaded chunks. */
public final class CoverageNode {
    public final UUID id;
    public final BlockPos position;
    public final boolean repeater;
    public StorageInventoryAdapter.Status status = StorageInventoryAdapter.Status.UNLOADED;

    public CoverageNode(UUID id, BlockPos position, boolean repeater) {
        this.id = id;
        this.position = position.immutable();
        this.repeater = repeater;
    }

    public ChunkPos chunk() {
        return new ChunkPos(position);
    }
}
