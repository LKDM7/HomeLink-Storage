package fr.lkdm.homelink.storage.block;

import com.mojang.serialization.MapCodec;
import fr.lkdm.homelink.storage.blockentity.DepositBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class DepositBlock extends StorageBlock {
    public static final MapCodec<DepositBlock> CODEC = simpleCodec(DepositBlock::new);
    public DepositBlock(Properties properties) { super(properties); }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
            BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return net.minecraft.world.level.block.Block.box(0.7, 0, 0.7, 15.3, 15.25, 15.3);
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new DepositBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, StorageRegistries.DEPOSIT_ENTITY.get(), DepositBlockEntity::tick);
    }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof DepositBlockEntity deposit) {
            if (deposit.canAccess(server)) server.openMenu(deposit, buffer -> {
                buffer.writeBlockPos(pos);
                var controller = deposit.controller();
                buffer.writeUtf(controller == null ? "" : controller.logicalName(), 256);
            });
            else server.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.homelink_storage.denied"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof DepositBlockEntity deposit) {
            for (int slot = 0; slot < deposit.inventory().getSlots(); slot++) {
                var stack = deposit.inventory().extractItem(slot, Integer.MAX_VALUE, false);
                if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, replacement, moved);
    }
}
