package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.block.StorageBlock;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

/** Actual BlockItem placement, wall contact and server-synchronized connection lamp. */
public final class ControllerChecks {
    public static void run(ServerPlayer player) {
        var level = player.serverLevel();
        var fixtures = new ArrayList<BlockPos>();
        ItemStack held = player.getMainHandItem();
        long originalTime = level.getGameTime();
        try {
            int n = 0;
            for (Direction face : Direction.values()) {
                BlockPos support = new BlockPos(320 + n++ * 4, 80, 320);
                BlockPos position = support.relative(face);
                level.getChunkAt(support); level.getChunkAt(position);
                fixtures.add(support); fixtures.add(position);
                level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
                level.removeBlock(position, false);
                ItemStack stack = StorageRegistries.CONTROLLER.get().asItem().getDefaultInstance();
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                var hit = new BlockHitResult(support.getCenter().add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5), face, support, false);
                var result = ((BlockItem) stack.getItem()).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
                check(result.consumesAction(), "Controller placement failed: " + face);
                var state = level.getBlockState(position);
                check(state.is(StorageRegistries.CONTROLLER.get()) && state.getValue(StorageBlock.TARGET) == face,
                        "Controller face must point away from its support: " + face);
                var bounds = state.getShape(level, position).bounds();
                double min = switch (face.getAxis()) { case X -> bounds.minX; case Y -> bounds.minY; case Z -> bounds.minZ; };
                double max = switch (face.getAxis()) { case X -> bounds.maxX; case Y -> bounds.maxY; case Z -> bounds.maxZ; };
                check(Math.abs(max - min - 6d / 16) < .00001, "Controller is not a shallow wall panel: " + face);
                check(face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? min == 0 : max == 1,
                        "Controller collision does not touch its support: " + face);
            }
            BlockPos controllerPos = new BlockPos(354, 80, 322), linkPos = controllerPos.offset(1, 0, 0), barrel = controllerPos.offset(2, 0, 0);
            level.getChunkAt(controllerPos);
            fixtures.add(controllerPos); fixtures.add(linkPos); fixtures.add(barrel);
            level.setBlockAndUpdate(controllerPos, StorageRegistries.CONTROLLER.get().defaultBlockState());
            level.setBlockAndUpdate(linkPos, StorageRegistries.LINK.get().defaultBlockState());
            level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
            var controller = (StorageBlockEntity) level.getBlockEntity(controllerPos);
            var link = (StorageBlockEntity) level.getBlockEntity(linkPos);
            controller.setOwner(player.getUUID()); link.setOwner(player.getUUID());
            check(link.bind(controller), "Lamp fixture Link failed to bind");
            controller.refreshIndex();
            level.getServer().getWorldData().overworldData().setGameTime(200);
            tick(controller);
            check(controller.getBlockState().getValue(StorageBlock.LIT), "Connected controller did not illuminate");
            check(controller.getBlockState().getLightEmission(level, controllerPos) == 7, "LED does not emit light");
            level.getServer().getWorldData().overworldData().setGameTime(210);
            tick(controller);
            check(!controller.getBlockState().getValue(StorageBlock.LIT), "Connected controller did not blink off");
            level.destroyBlock(barrel, false); controller.refreshIndex();
            level.getServer().getWorldData().overworldData().setGameTime(220);
            tick(controller);
            check(!controller.getBlockState().getValue(StorageBlock.LIT), "Disconnected controller lamp remained on");
            LogUtils.getLogger().info("STORAGE_CONTROLLER_CHECKS_OK placement_faces=6 wall_contact=true lamp_blinks=true disconnected_dark=true");
        } finally {
            level.getServer().getWorldData().overworldData().setGameTime(originalTime);
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            for (int i = fixtures.size() - 1; i >= 0; i--) level.destroyBlock(fixtures.get(i), false);
        }
    }
    private static void tick(StorageBlockEntity controller) {
        StorageBlock.tick(controller.getLevel(), controller.getBlockPos(), controller.getBlockState(), controller);
    }
    private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
    private ControllerChecks() { }
}
