package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import java.util.UUID;

public final class LinkKeyChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        var previous = player.position();
        var held = player.getMainHandItem();
        long time = level.getGameTime();
        var positions = new BlockPos[]{new BlockPos(800,80,800), new BlockPos(802,80,800),
                new BlockPos(816,80,800), new BlockPos(804,80,800)};
        var blocks = new net.minecraft.world.level.block.Block[]{StorageRegistries.CONTROLLER.get(),
                StorageRegistries.LINK.get(), StorageRegistries.REPEATER.get(), StorageRegistries.TERMINAL.get()};
        try {
            var entities = new StorageBlockEntity[4];
            for (int i=0;i<4;i++) {
                level.getChunkAt(positions[i]);
                level.setBlockAndUpdate(positions[i],blocks[i].defaultBlockState());
                entities[i]=(StorageBlockEntity)level.getBlockEntity(positions[i]);
                entities[i].setOwner(player.getUUID());
            }
            ItemStack key=StorageRegistries.LINK_KEY.get().getDefaultInstance();
            player.setItemInHand(InteractionHand.MAIN_HAND,key);
            click(player,positions[3]);
            check(entities[3].controller()==null,"empty USB must not bind");
            click(player,positions[0]);
            check(key.has(DataComponents.CUSTOM_DATA),"USB must store selection");
            key=ItemStack.parseOptional(level.registryAccess(),(net.minecraft.nbt.CompoundTag) key.save(level.registryAccess()));
            player.setItemInHand(InteractionHand.MAIN_HAND,key);
            check(key.get(DataComponents.CUSTOM_DATA).copyTag().getUUID("Id").equals(entities[0].id()),"USB save/load");
            click(player,positions[2]);
            level.getServer().getWorldData().overworldData().setGameTime(200);
            tick(entities[2]);
            check(!level.getBlockState(positions[2]).getValue(StorageBlock.LIT),"isolated repeater must stay dark");
            click(player,positions[1]); click(player,positions[3]);
            entities[0].refreshConnections();
            for(int i=1;i<4;i++) {
                check(entities[i].controller()==entities[0],"USB binding " + i);
                tick(entities[i]);
                var state=level.getBlockState(positions[i]);
                check(state.getValue(StorageBlock.LIT),"connected LED " + i);
                check(state.getLightEmission(level,positions[i])==7,"emitted light " + i);
            }
            check(player.containerMenu==player.inventoryMenu,"USB must not open terminal GUI");
            entities[0].setOwner(UUID.randomUUID());
            var old=key.get(DataComponents.CUSTOM_DATA);
            click(player,positions[0]);
            check(old.equals(key.get(DataComponents.CUSTOM_DATA)),"unauthorized selection");
            entities[0].setOwner(player.getUUID());
            level.removeBlock(positions[1],false); entities[0].refreshConnections(); tick(entities[2]);
            check(!level.getBlockState(positions[2]).getValue(StorageBlock.LIT),"broken repeater chain LED");
            level.removeBlock(positions[0],false); tick(entities[3]);
            check(!level.getBlockState(positions[3]).getValue(StorageBlock.LIT),"missing controller LED");
            level.setBlockAndUpdate(positions[0],blocks[0].defaultBlockState());
            ((StorageBlockEntity)level.getBlockEntity(positions[0])).setOwner(player.getUUID());
            click(player,positions[3]);
            check(entities[3].controller()==null,"stale USB UUID must not bind replacement");
            check(level.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("homelink_storage","link_key")).isPresent(),"USB recipe");
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_LINK_KEY_CHECKS_OK actual_clicks=true persistence=true leds=true broken_chain=true stale_uuid=true recipe=true");
        } finally {
            level.getServer().getWorldData().overworldData().setGameTime(time);
            player.setItemInHand(InteractionHand.MAIN_HAND,held);
            player.teleportTo(previous.x,previous.y,previous.z);
            for(var pos:positions) level.removeBlock(pos,false);
        }
    }
    private static void click(ServerPlayer player,BlockPos pos) {
        player.teleportTo(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
        player.gameMode.useItemOn(player,player.serverLevel(),player.getMainHandItem(),InteractionHand.MAIN_HAND,
                new BlockHitResult(pos.getCenter(),Direction.UP,pos,false));
    }
    private static void tick(StorageBlockEntity entity) {
        StorageBlock.tick(entity.getLevel(),entity.getBlockPos(),entity.getBlockState(),entity);
    }
    private static void check(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
}
