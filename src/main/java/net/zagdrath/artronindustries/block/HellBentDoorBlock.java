/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.block.entity.HellBentDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.registry.ArtronSounds;
import net.zagdrath.artronindustries.tardis.DoorSounds;
import net.zagdrath.artronindustries.tardis.interior.InteriorDoorway;

/**
 * The Hell Bent roundel door: a placeable TARDIS interior door two blocks wide and three tall, one block per cell. The
 * bottom left cell (as seen from the room) is the master and carries the {@link HellBentDoorBlockEntity}; breaking any cell
 * breaks the door, which drops one item from the master. Placed inside a TARDIS cell it becomes that TARDIS's interior
 * door, as {@link InteriorDoorBlock} does.
 * <p>
 * Its layout is an {@link InteriorDoorway} of one layer, the leaves 4 px thick at the front of the cells, so it opens like a
 * template interior's doorway (and like the Victorian Parlour's): its two leaves swing out into the room, clear of the
 * doorway plane just behind them. Like that doorway's cells, a shut cell collides with its leaf and an open one only keeps
 * a thin panel on the plane, so clicking the open doorway shuts it. The cells draw nothing: the block entity's renderer
 * draws the door, its frame and leaves cut from the door's texture.
 */
public class HellBentDoorBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** The cell's column, from the left as seen from the room. */
    public static final IntegerProperty COLUMN = IntegerProperty.create("column", 0, 1);
    public static final IntegerProperty ROW = IntegerProperty.create("row", 0, 2);
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    /** The leaves' thickness, in sixteenths. */
    public static final int THICKNESS = 4;
    public static final InteriorDoorway DOORWAY = new InteriorDoorway(2, 3, 1, THICKNESS / 16.0F);
    /** Until the door has sounds of its own, ArtronSounds plays the parlour's, so it swings for as long as they last. */
    public static final DoorSounds SOUNDS = new DoorSounds(ArtronSounds.HELL_BENT_DOOR_OPEN, ArtronSounds.HELL_BENT_DOOR_CLOSE, 50);
    /** North-facing: the front (room) face is at z = 0. */
    private static final Map<Direction, VoxelShape> SHUT_SHAPES = Shapes.rotateHorizontal(Block.box(0.0, 0.0, 0.0, 16.0, 16.0, THICKNESS));
    /** The open doorway's click panel, on the plane just behind the leaves. */
    private static final Map<Direction, VoxelShape> PLANE_SHAPES = Shapes.rotateHorizontal(Block.box(0.0, 0.0, THICKNESS, 16.0, 16.0, THICKNESS + 1.0));

    public HellBentDoorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COLUMN, 0).setValue(ROW, 0)
                .setValue(OPEN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COLUMN, ROW, OPEN);
    }

    public static boolean isMaster(BlockState state) {
        return state.getValue(COLUMN) == 0 && state.getValue(ROW) == 0;
    }

    /** The master cell of the door that the cell {@code state} at {@code pos} belongs to. */
    public static BlockPos master(BlockPos pos, BlockState state) {
        return pos.relative(PortalShape.right(state.getValue(FACING)), -state.getValue(COLUMN)).below(state.getValue(ROW));
    }

    /** The clicked position is the bottom left cell; the door needs the block to its right and the two rows above free. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos master = context.getClickedPos();
        Level level = context.getLevel();
        for (int index = 1; index < DOORWAY.cellCount(); index++) {
            BlockPos cell = DOORWAY.cell(master, facing, index);
            if (cell.getY() > level.getMaxY() || !level.getWorldBorder().isWithinBounds(cell) || !level.getBlockState(cell).canBeReplaced(context)) {
                return null;
            }
        }
        return this.defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        Direction facing = state.getValue(FACING);
        for (int index = 1; index < DOORWAY.cellCount(); index++) {
            level.setBlock(DOORWAY.cell(pos, facing, index), state.setValue(COLUMN, DOORWAY.i(index)).setValue(ROW, DOORWAY.j(index)), Block.UPDATE_ALL);
        }
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof HellBentDoorBlockEntity door) {
            InteriorDoorBlock.linkPlacedDoor(serverLevel, pos, facing, DOORWAY.shape(), door, by);
        }
    }

    /** A cell whose neighbour in the door is no longer that cell goes too, so the whole door goes together. */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour,
                                     BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        Direction facing = state.getValue(FACING);
        Direction right = PortalShape.right(facing);
        int i = state.getValue(COLUMN) + (directionToNeighbour == right ? 1 : directionToNeighbour == right.getOpposite() ? -1 : 0);
        int j = state.getValue(ROW) + (directionToNeighbour == Direction.UP ? 1 : directionToNeighbour == Direction.DOWN ? -1 : 0);
        boolean inDoor = directionToNeighbour.getAxis() != facing.getAxis() && i >= 0 && i < DOORWAY.width() && j >= 0 && j < DOORWAY.height();
        if (inDoor && !(neighbourState.is(this) && neighbourState.getValue(FACING) == facing && neighbourState.getValue(COLUMN) == i
                && neighbourState.getValue(ROW) == j)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    /** Only the master has a loot table entry: breaking another cell breaks the master with drops instead. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && !isMaster(state)) {
            BlockPos master = master(pos, state);
            if (level.getBlockState(master).is(this)) {
                level.destroyBlock(master, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(master(pos, state)) instanceof HellBentDoorBlockEntity door)) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel serverLevel) {
            Component failure = door.toggle(serverLevel);
            if (failure != null) {
                player.sendOverlayMessage(failure);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(OPEN) ? PLANE_SHAPES : SHUT_SHAPES).get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(OPEN) ? Shapes.empty() : SHUT_SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isMaster(state) ? new HellBentDoorBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == ArtronBlockEntities.HELL_BENT_DOOR.get() ? (l, p, s, be) -> ((HellBentDoorBlockEntity) be).tick() : null;
    }
}
