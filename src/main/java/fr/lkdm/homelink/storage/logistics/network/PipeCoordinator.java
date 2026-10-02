package fr.lkdm.homelink.storage.logistics.network;

import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/** Transport context, backed either by the existing Controller or by an authenticated pipe owner. */
final class PipeCoordinator {
    final ServerLevel level;
    final @Nullable StorageBlockEntity block;
    private final UUID owner;
    private final UUID id;
    private final BlockPos anchor;

    PipeCoordinator(ServerLevel level, StorageBlockEntity block) {
        this.level=level; this.block=block; owner=block.owner(); id=block.id(); anchor=block.getBlockPos();
    }
    PipeCoordinator(ServerLevel level, UUID owner, BlockPos anchor) {
        this.level=level; this.block=null; this.owner=owner; this.anchor=anchor;
        id=UUID.nameUUIDFromBytes(("homelink_storage:pipes:"+level.dimension().location()+":"+owner).getBytes(StandardCharsets.UTF_8));
    }
    UUID id(){return id;}
    UUID owner(){return owner;}
    @Nullable UUID networkId(){return block==null?null:block.networkId();}
    BlockPos getBlockPos(){return anchor;}
    boolean autonomous(){return block==null;}
    boolean pipesPaused(){return block!=null && block.pipesPaused();}
    boolean powered(){return block==null || block.powered();}
    boolean automationAvailable(){return block==null || block.automationAvailable();}
    boolean canPayLogistics(long cost){return block==null || block.canPayLogistics(cost);}
    boolean payLogistics(long cost){return block==null || block.payLogistics(cost);}
    void refundLogistics(long cost){if(block!=null)block.refundLogistics(cost);}
    void inventoryChanged(BlockPos identity){
        // Like a hopper, autonomous transport changes real stock; notify loaded indexed devices only.
        for(var pos:PipeAccess.devices(level).keySet())
            if(LoadedBlocks.entity(level,pos) instanceof StorageBlockEntity storage && storage.isController()) storage.inventoryChanged(identity);
    }
}
