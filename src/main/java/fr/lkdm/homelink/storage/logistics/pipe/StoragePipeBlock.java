package fr.lkdm.homelink.storage.logistics.pipe;

import com.mojang.serialization.MapCodec;
import fr.lkdm.homecore.api.item.ItemApi;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.logistics.network.PipeInteraction;
import fr.lkdm.homelink.storage.logistics.network.PipeNetworkManager;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import org.jetbrains.annotations.Nullable;

/**
 * Transparent item pipe. Its six sides join other pipes, a Storage Controller (which coordinates
 * but is never crossed) or a container exposing an item capability on the touching face.
 * The block state only drives the model and shape; transport re-checks the live world.
 */
public final class StoragePipeBlock extends BaseEntityBlock {
    public static final MapCodec<StoragePipeBlock> CODEC = simpleCodec(StoragePipeBlock::new);
    public static final Map<Direction, EnumProperty<PipeConnection>> SIDES = new EnumMap<>(Direction.class);
    static {
        for (Direction direction : Direction.values())
            SIDES.put(direction, EnumProperty.create(direction.getSerializedName(), PipeConnection.class));
    }
    /** Glass body 7/16 wide, copper connectors 8/16 at containers and Controllers. */
    public static final double CORE_MIN = 4.5, CORE_MAX = 11.5, ARM_MIN = 5, ARM_MAX = 11, COLLAR_MIN = 4, COLLAR_MAX = 12;
    private static final VoxelShape CORE = Block.box(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
    private static final Map<BlockState, VoxelShape> SHAPES = new ConcurrentHashMap<>();

    public StoragePipeBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (var property : SIDES.values()) state = state.setValue(property, PipeConnection.NONE);
        registerDefaultState(state);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        SIDES.values().forEach(builder::add);
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new StoragePipeBlockEntity(pos, state); }

    public static PipeConnection side(BlockState state, Direction direction) {
        return state.hasProperty(SIDES.get(direction)) ? state.getValue(SIDES.get(direction)) : PipeConnection.NONE;
    }

    /** Classifies a neighbour in priority order: pipe, then Controller, then compatible container. */
    public static PipeConnection classify(BlockGetter getter, BlockPos pos, Direction direction) {
        BlockPos neighbour = pos.relative(direction);
        if (getter instanceof Level level && !level.isLoaded(neighbour)) return PipeConnection.NONE;
        BlockState state = getter.getBlockState(neighbour);
        if (state.getBlock() instanceof StoragePipeBlock) return PipeConnection.PIPE;
        if (!(getter instanceof Level level)) return PipeConnection.NONE;
        BlockEntity entity = level.getBlockEntity(neighbour);
        if (entity instanceof StorageBlockEntity storage && storage.isController()) return PipeConnection.CONTROLLER;
        Direction face = direction.getOpposite();
        // A HomeCore port has priority; without one, the ordinary handler of the same face.
        if (level.getCapability(ItemApi.BLOCK, neighbour, state, entity, face) != null) return PipeConnection.CONTAINER;
        if (level.getCapability(Capabilities.ItemHandler.BLOCK, neighbour, state, entity, face) != null) return PipeConnection.CONTAINER;
        return PipeConnection.NONE;
    }

    private BlockState connected(BlockGetter level, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) state = state.setValue(SIDES.get(direction), classify(level, pos, direction));
        return state;
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return connected(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState, LevelAccessor level,
                                               BlockPos pos, BlockPos neighbourPos) {
        PipeConnection before = state.getValue(SIDES.get(direction));
        PipeConnection after = classify(level, pos, direction);
        if (level instanceof ServerLevel server && server.getBlockEntity(pos) instanceof StoragePipeBlockEntity pipe)
            pipe.neighbourChanged(direction, before, after, neighbourState);
        return state.setValue(SIDES.get(direction), after);
    }

    @Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        super.onPlace(state, level, pos, old, moved);
        if (level instanceof ServerLevel server) {
            server.scheduleTick(pos, this, 1);
            if (old.is(this)) PipeNetworkManager.get(server).pipeChanged(pos);
            else PipeNetworkManager.get(server).pipeAdded(pos);
        }
    }

    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        BlockState updated = connected(level, pos, state);
        if (updated != state) {
            level.setBlock(pos, updated, Block.UPDATE_ALL);
            PipeNetworkManager.get(level).pipeChanged(pos);
        }
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        // Breaking a pipe frees no item: cargo on it goes to its Controller's recovery, never into the world twice.
        if (!state.is(replacement.getBlock()) && level instanceof ServerLevel server) PipeNetworkManager.get(server).pipeRemoved(pos);
        super.onRemove(state, level, pos, replacement, moved);
    }

    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour, BlockPos neighbourPos, boolean moved) {
        super.neighborChanged(state, level, pos, neighbour, neighbourPos, moved);
        if (level instanceof ServerLevel server) PipeNetworkManager.get(server).facesChanged(pos);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide && placer != null && level.getBlockEntity(pos) instanceof StoragePipeBlockEntity pipe) pipe.setOwner(placer.getUUID());
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                        InteractionHand hand, BlockHitResult hit) {
        // Holding a block places it against the pipe instead of opening the connection screen.
        if (stack.getItem() instanceof BlockItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof StoragePipeBlockEntity pipe)
            PipeInteraction.use(server, pipe, clickedSide(state, pos, hit.getLocation()));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Side whose connector holds the clicked point, or null for the centre of the pipe. */
    public static @Nullable Direction clickedSide(BlockState state, BlockPos pos, Vec3 location) {
        double x = location.x - pos.getX() - 0.5, y = location.y - pos.getY() - 0.5, z = location.z - pos.getZ() - 0.5;
        double limit = (CORE_MAX - 8) / 16.0 + 0.002;
        Direction best = null;
        double depth = limit;
        for (Direction direction : Direction.values()) {
            if (side(state, direction) == PipeConnection.NONE) continue;
            double along = x * direction.getStepX() + y * direction.getStepY() + z * direction.getStepZ();
            if (along > depth) { depth = along; best = direction; }
        }
        return best;
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.computeIfAbsent(state, StoragePipeBlock::shape);
    }

    private static VoxelShape shape(BlockState state) {
        VoxelShape shape = CORE;
        for (Direction direction : Direction.values()) {
            PipeConnection connection = side(state, direction);
            if (connection == PipeConnection.NONE) continue;
            shape = Shapes.or(shape, box(direction, ARM_MIN, ARM_MAX, 0, CORE_MIN));
            if (connection != PipeConnection.PIPE) shape = Shapes.or(shape, box(direction, COLLAR_MIN, COLLAR_MAX, 0, 1.5));
        }
        return shape.optimize();
    }

    /** Box spanning [low, high] across the side and [from, to] pixels inward from that side's face. */
    private static VoxelShape box(Direction direction, double low, double high, double from, double to) {
        double near = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 - to : from;
        double far = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 - from : to;
        return switch (direction.getAxis()) {
            case X -> Block.box(near, low, low, far, high, high);
            case Y -> Block.box(low, near, low, high, far, high);
            case Z -> Block.box(low, low, near, high, high, far);
        };
    }

    @Override protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }
    @Override protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
}
