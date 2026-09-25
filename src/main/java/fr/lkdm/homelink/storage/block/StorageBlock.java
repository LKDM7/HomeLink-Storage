package fr.lkdm.homelink.storage.block;

import com.mojang.serialization.MapCodec;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.storage.storage.network.StorageBinding;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class StorageBlock extends BaseEntityBlock {
    public static final MapCodec<StorageBlock> CODEC = simpleCodec(StorageBlock::new);
    public static final DirectionProperty TARGET = BlockStateProperties.FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    public static final BooleanProperty WALL_MOUNTED = BooleanProperty.create("wall_mounted");
    private static final VoxelShape[] TERMINAL_PANELS = {
            Block.box(1,12,1,15,16,15), Block.box(1,0,1,15,4,15),
            Block.box(1,1,12,15,15,16), Block.box(1,1,0,15,15,4),
            Block.box(12,1,1,16,15,15), Block.box(0,1,1,4,15,15)
    };
    private static final VoxelShape CONTROLLER_NORTH = Block.box(1, 1, 10, 15, 15, 16);
    private static final VoxelShape CONTROLLER_SOUTH = Block.box(1, 1, 0, 15, 15, 6);
    private static final VoxelShape CONTROLLER_EAST = Block.box(0, 1, 1, 6, 15, 15);
    private static final VoxelShape CONTROLLER_WEST = Block.box(10, 1, 1, 16, 15, 15);
    private static final VoxelShape CONTROLLER_UP = Block.box(1, 0, 1, 15, 6, 15);
    private static final VoxelShape CONTROLLER_DOWN = Block.box(1, 10, 1, 15, 16, 15);
    private static final VoxelShape TERMINAL_SHAPE = Shapes.or(Block.box(2, 0, 3, 14, 3, 14),
            Block.box(5, 3, 8, 11, 10, 14), Block.box(1, 8, 4, 15, 16, 15));
    private static final VoxelShape LINK_NORTH = Block.box(3, 3, 0, 13, 13, 6);
    private static final VoxelShape LINK_SOUTH = Block.box(3, 3, 10, 13, 13, 16);
    private static final VoxelShape LINK_WEST = Block.box(0, 3, 3, 6, 13, 13);
    private static final VoxelShape LINK_EAST = Block.box(10, 3, 3, 16, 13, 13);
    private static final VoxelShape LINK_UP = Block.box(3, 10, 3, 13, 16, 13);
    private static final VoxelShape LINK_DOWN = Block.box(3, 0, 3, 13, 6, 13);
    private static final VoxelShape REPEATER_NS = Shapes.or(Block.box(1, 0, 2, 15, 5, 14),
            Block.box(4, 5, 6, 6, 16, 8), Block.box(10, 5, 6, 12, 16, 8));
    private static final VoxelShape REPEATER_EW = Shapes.or(Block.box(2, 0, 1, 14, 5, 15),
            Block.box(8, 5, 4, 10, 16, 6), Block.box(8, 5, 10, 10, 16, 12));
    public StorageBlock(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(TARGET, Direction.NORTH).setValue(LIT, false).setValue(WALL_MOUNTED, false)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(TARGET, LIT, WALL_MOUNTED); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (this == StorageRegistries.TERMINAL.get() && context.getClickedFace() != Direction.UP)
            return defaultBlockState().setValue(TARGET, context.getClickedFace()).setValue(WALL_MOUNTED, true);
        Direction facing = this == StorageRegistries.LINK.get()
                ? context.getClickedFace().getOpposite()
                : this == StorageRegistries.CONTROLLER.get() ? context.getClickedFace()
                : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(TARGET, facing);
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide && placer != null && level.getBlockEntity(pos) instanceof StorageBlockEntity entity) entity.setOwner(placer.getUUID());
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, StorageRegistries.STORAGE_ENTITY.get(), StorageBlock::tick);
    }
    /** The server synchronizes the status LED; inventory contents are never sent for this effect. */
    public static void tick(Level level, BlockPos pos, BlockState state, StorageBlockEntity entity) {
        StorageBlockEntity.tick(level, pos, state, entity);
        if (level.getGameTime() % 10 != 0) return;
        var controller = entity.controller();
        boolean lit = controller != null && !controller.isRemoved();
        if (entity.isController()) lit = entity.connections().values().stream()
                .anyMatch(connection -> connection.status == StorageInventoryAdapter.Status.ONLINE)
                && (level.getGameTime() / 10) % 2 == 0;
        else if (entity.isCoverageNode()) lit = lit && controller.isCoverageActive(entity.id());
        if (state.getValue(LIT) != lit) level.setBlock(pos, state.setValue(LIT, lit), Block.UPDATE_CLIENTS);
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos, boolean moved) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof StorageBlockEntity storage) storage.markIndexDirty();
    }
    @Override protected boolean hasAnalogOutputSignal(BlockState state) { return state.is(StorageRegistries.CONTROLLER.get()); }
    @Override protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof StorageBlockEntity entity ? entity.comparatorSignal() : 0;
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && !level.isClientSide && level.getBlockEntity(pos) instanceof StorageBlockEntity entity) entity.destroyed();
        super.onRemove(state, level, pos, replacement, moved);
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.is(StorageRegistries.CONTROLLER.get())) return switch (state.getValue(TARGET)) {
            case NORTH -> CONTROLLER_NORTH;
            case SOUTH -> CONTROLLER_SOUTH;
            case EAST -> CONTROLLER_EAST;
            case WEST -> CONTROLLER_WEST;
            case UP -> CONTROLLER_UP;
            case DOWN -> CONTROLLER_DOWN;
        };
        if (state.is(StorageRegistries.TERMINAL.get())) return state.getValue(WALL_MOUNTED)
                ? TERMINAL_PANELS[state.getValue(TARGET).get3DDataValue()] : TERMINAL_SHAPE;
        if (state.is(StorageRegistries.REPEATER.get())) return state.getValue(TARGET).getAxis() == Direction.Axis.X ? REPEATER_EW : REPEATER_NS;
        if (state.is(StorageRegistries.LINK.get())) return switch (state.getValue(TARGET)) {
            case NORTH -> LINK_NORTH;
            case SOUTH -> LINK_SOUTH;
            case WEST -> LINK_WEST;
            case EAST -> LINK_EAST;
            case UP -> LINK_UP;
            case DOWN -> LINK_DOWN;
        };
        return Shapes.block();
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new StorageBlockEntity(pos, state); }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof fr.lkdm.homelink.storage.item.LinkKeyItem) {
            if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof StorageBlockEntity storage)
                fr.lkdm.homelink.storage.item.LinkKeyItem.connect(server, storage, stack);
            return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof StorageBlockEntity storage) {
            StorageBinding.interact(serverPlayer, storage);
        }
        // Second right-click on a Link or Repeater: hide its zone at once, before the server answers.
        if (level.isClientSide && !player.isShiftKeyDown() && (state.is(StorageRegistries.LINK.get()) || state.is(StorageRegistries.REPEATER.get())))
            fr.lkdm.homelink.storage.network.CoverageState.clickedLocally(pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
