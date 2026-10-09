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
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalShape;

/**
 * Base for the two ends of a TARDIS doorway: the {@link TardisBlock} outside and the {@link InteriorDoorBlock} inside. A
 * two-block-tall door whose block entity lives in the lower half. By default its only solid part is a thin panel at the
 * back, so entities can walk up to (and through) the doorway plane on the front face.
 * <p>
 * BOTI does not depend on anything in this class, only on {@link net.zagdrath.artronindustries.portal.PortalEndpoint}.
 */
public abstract class PortalDoorBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    private static final Map<Direction, VoxelShape> SHAPES = Shapes.rotateHorizontal(Block.box(0, 0, 14, 16, 16, 16));

    protected PortalDoorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(HALF, DoubleBlockHalf.LOWER));
    }

    protected abstract BlockEntityType<? extends PortalDoorBlockEntity> blockEntityType();

    /** Called on the server once both halves exist and the block entity has been created. */
    protected abstract void onPlacedServer(ServerLevel level, BlockPos pos, BlockState state, PortalDoorBlockEntity door, @Nullable LivingEntity placer);

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() < level.getMaxY() && level.getBlockState(pos.above()).canBeReplaced(context)) {
            return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(HALF, DoubleBlockHalf.LOWER);
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        level.setBlockAndUpdate(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER));
        if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof PortalDoorBlockEntity door) {
            this.onPlacedServer(serverLevel, pos, state, door, by);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour,
                                     BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        DoubleBlockHalf half = state.getValue(HALF);
        boolean partnerDirection = directionToNeighbour == (half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN);
        if (partnerDirection) {
            return neighbourState.is(this) && neighbourState.getValue(HALF) != half ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // Only the lower half has a loot table entry; breaking the upper half breaks the lower one with drops instead.
        if (!level.isClientSide() && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            if (level.getBlockState(below).is(this)) {
                level.destroyBlock(below, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
        if (!(level.getBlockEntity(lower) instanceof PortalDoorBlockEntity door)) {
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
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
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

    /** The doorway of a door of this block; the TARDIS's comes from its exterior instead (see TardisBlockEntity). */
    public PortalShape portalShape(BlockState state) {
        return PortalShape.DEFAULT_DOOR;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos worldPosition, BlockState blockState) {
        return blockState.getValue(HALF) == DoubleBlockHalf.LOWER ? this.blockEntityType().create(worldPosition, blockState) : null;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState blockState, BlockEntityType<T> type) {
        return type == this.blockEntityType() ? (l, p, s, be) -> ((PortalDoorBlockEntity) be).tick() : null;
    }
}
