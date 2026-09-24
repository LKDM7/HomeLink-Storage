package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import java.util.ArrayList;

public final class TerminalMountChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        var previous = player.position();
        var held = player.getMainHandItem();
        var fixtures = new ArrayList<BlockPos>();
        try {
            int index = 0;
            for (Direction face : Direction.values()) {
                BlockPos support = new BlockPos(640 + index++ * 4, 80, 640);
                BlockPos pos = support.relative(face);
                level.getChunkAt(support); level.getChunkAt(pos);
                fixtures.add(support); fixtures.add(pos);
                level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
                ItemStack item = StorageRegistries.TERMINAL.get().asItem().getDefaultInstance();
                player.setItemInHand(InteractionHand.MAIN_HAND, item);
                var hit = new BlockHitResult(support.getCenter().add(face.getStepX()*.5,face.getStepY()*.5,face.getStepZ()*.5),face,support,false);
                check(((BlockItem)item.getItem()).place(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,item,hit)).consumesAction(), "placement " + face);
                var state = level.getBlockState(pos);
                check(state.getValue(StorageBlock.WALL_MOUNTED) == (face != Direction.UP), "mount variant " + face);
                if (face != Direction.UP) {
                    check(state.getValue(StorageBlock.TARGET) == face, "screen orientation " + face);
                    var box = state.getShape(level,pos).bounds();
                    double min = switch(face.getAxis()) { case X -> box.minX; case Y -> box.minY; case Z -> box.minZ; };
                    double max = switch(face.getAxis()) { case X -> box.maxX; case Y -> box.maxY; case Z -> box.maxZ; };
                    check(Math.abs(max-min-.25)<.0001, "panel depth " + face);
                    check(face.getAxisDirection()==Direction.AxisDirection.POSITIVE ? min==0 : max==1, "wall contact " + face);
                }
                var terminal = (StorageBlockEntity)level.getBlockEntity(pos);
                check(terminal != null && player.getUUID().equals(terminal.owner()), "terminal owner");
                player.teleportTo(pos.getX()+.5,pos.getY()+1,pos.getZ()+.5);
                player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                player.gameMode.useItemOn(player,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,new BlockHitResult(pos.getCenter(),face,pos,false));
                check(player.containerMenu instanceof StorageMenu menu && menu.position().equals(pos), "wall terminal interaction");
                player.closeContainer();
            }
            com.mojang.logging.LogUtils.getLogger().info("STORAGE_TERMINAL_MOUNT_CHECKS_OK faces=6 wall_panel=true floor_stand=true collision=true menu=true");
        } finally {
            player.teleportTo(previous.x,previous.y,previous.z);
            player.setItemInHand(InteractionHand.MAIN_HAND,held);
            for (int i=fixtures.size()-1;i>=0;i--) level.removeBlock(fixtures.get(i),false);
        }
    }
    private static void check(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
}
