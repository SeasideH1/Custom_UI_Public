package com.lootmatrix.customui.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * A vertical slab block that can be placed on the north, south, east, or west face.
 * Two vertical half-slabs of the same type can be combined into a double slab.
 */
public class VerticalSlabBlock extends Block implements SimpleWaterloggedBlock {

    public static final EnumProperty<VerticalSlabType> TYPE = EnumProperty.create("type", VerticalSlabType.class);
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    // VoxelShapes for each direction (half block, 8 pixels deep)
    protected static final VoxelShape NORTH_SHAPE = Block.box(0, 0, 0, 16, 16, 8);
    protected static final VoxelShape SOUTH_SHAPE = Block.box(0, 0, 8, 16, 16, 16);
    protected static final VoxelShape EAST_SHAPE = Block.box(8, 0, 0, 16, 16, 16);
    protected static final VoxelShape WEST_SHAPE = Block.box(0, 0, 0, 8, 16, 16);
    protected static final VoxelShape DOUBLE_SHAPE = Shapes.block();

    public VerticalSlabBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(TYPE, VerticalSlabType.NORTH)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TYPE, WATERLOGGED);
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return state.getValue(TYPE) != VerticalSlabType.DOUBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShapeForType(state.getValue(TYPE));
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return getShapeForType(state.getValue(TYPE));
    }

    @Override
    public boolean skipRendering(BlockState state, BlockState adjacentState, Direction direction) {
        if (adjacentState.is(this)) {
            VerticalSlabType currentType = state.getValue(TYPE);
            VerticalSlabType adjacentType = adjacentState.getValue(TYPE);
            if (occupiesFace(currentType, direction) && occupiesFace(adjacentType, direction.getOpposite())) {
                return true;
            }
        }
        return super.skipRendering(state, adjacentState, direction);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        BlockState existingState = context.getLevel().getBlockState(pos);

        // If clicking on an existing vertical slab of the SAME type, make it double
        if (existingState.is(this)) {
            return existingState.setValue(TYPE, VerticalSlabType.DOUBLE).setValue(WATERLOGGED, false);
        }

        // Normal placement - new slab in empty space
        Direction facing = context.getHorizontalDirection();
        FluidState fluidState = context.getLevel().getFluidState(pos);
        boolean waterlogged = fluidState.getType() == Fluids.WATER;

        VerticalSlabType type = getTypeFromPlacement(context, facing);

        return this.defaultBlockState()
                .setValue(TYPE, type)
                .setValue(WATERLOGGED, waterlogged);
    }

    /**
     * Determine the vertical slab type from placement context.
     * The slab is placed flush against (attached to) the block that was right-clicked.
     * When clicking a horizontal face, the slab attaches to that adjacent block.
     * When clicking a top/bottom face, the closest horizontal edge determines attachment.
     */
    private VerticalSlabType getTypeFromPlacement(BlockPlaceContext context, Direction facing) {
        Direction clickedFace = context.getClickedFace();

        // If clicking on a horizontal face, place the slab flush against the clicked block.
        // The clicked face is the face of the adjacent block -> the slab should fill
        // the opposite side of the placement position (i.e., the side touching the adjacent block).
        if (clickedFace.getAxis().isHorizontal()) {
            return VerticalSlabType.fromDirection(clickedFace.getOpposite());
        }

        // For up/down faces, determine which horizontal edge the player clicked closest to.
        // The slab will attach to the adjacent block on that side.
        double hitX = context.getClickLocation().x - context.getClickedPos().getX();
        double hitZ = context.getClickLocation().z - context.getClickedPos().getZ();

        // Calculate distance to each edge
        double distNorth = hitZ;         // distance to north edge (z=0)
        double distSouth = 1.0 - hitZ;   // distance to south edge (z=1)
        double distWest = hitX;          // distance to west edge (x=0)
        double distEast = 1.0 - hitX;    // distance to east edge (x=1)

        // Find the minimum distance -> closest edge -> attach to that side
        double minDist = Math.min(Math.min(distNorth, distSouth), Math.min(distWest, distEast));

        if (minDist == distNorth) {
            return VerticalSlabType.NORTH;
        } else if (minDist == distSouth) {
            return VerticalSlabType.SOUTH;
        } else if (minDist == distWest) {
            return VerticalSlabType.WEST;
        } else {
            return VerticalSlabType.EAST;
        }
    }

    @Override
    public boolean canBeReplaced(BlockState state, BlockPlaceContext context) {
        VerticalSlabType currentType = state.getValue(TYPE);

        // Don't replace if already double
        if (currentType == VerticalSlabType.DOUBLE) {
            return false;
        }

        ItemStack handItem = context.getItemInHand();
        // Only allow combining same block type into double
        if (handItem.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == this) {

            if (context.replacingClickedOnBlock()) {
                Direction clickedFace = context.getClickedFace();

                // Check if clicking on the open side
                if (clickedFace.getAxis().isHorizontal()) {
                    VerticalSlabType clickType = VerticalSlabType.fromDirection(clickedFace);
                    return clickType != currentType;
                }
                // For up/down clicks, allow
                return true;
            }
        }
        return false;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                   LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
        return false;
    }

    private static VoxelShape getShapeForType(VerticalSlabType type) {
        return switch (type) {
            case NORTH -> NORTH_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            case DOUBLE -> DOUBLE_SHAPE;
        };
    }

    private static boolean occupiesFace(VerticalSlabType type, Direction face) {
        if (type == VerticalSlabType.DOUBLE) {
            return true;
        }
        return switch (face) {
            case NORTH -> type == VerticalSlabType.NORTH;
            case SOUTH -> type == VerticalSlabType.SOUTH;
            case EAST -> type == VerticalSlabType.EAST;
            case WEST -> type == VerticalSlabType.WEST;
            default -> false;
        };
    }

    /**
     * Enum for vertical slab types: NORTH, SOUTH, EAST, WEST, DOUBLE.
     */
    public enum VerticalSlabType implements StringRepresentable {
        NORTH("north"),
        SOUTH("south"),
        EAST("east"),
        WEST("west"),
        DOUBLE("double");

        private final String name;

        VerticalSlabType(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        @Override
        public String toString() {
            return this.name;
        }

        /**
         * Get the vertical slab type from a direction.
         */
        public static VerticalSlabType fromDirection(Direction direction) {
            return switch (direction) {
                case NORTH -> NORTH;
                case SOUTH -> SOUTH;
                case EAST -> EAST;
                case WEST -> WEST;
                default -> NORTH;
            };
        }
    }
}

