/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.registry.ArtronBlocks;

/**
 * The third block of the TARDIS exterior, above its upper half. It draws nothing; it only carries the top of the box's
 * outline and collision (sign plates, roof), which entities would otherwise never test: collision is only checked for
 * blocks next to an entity, and the roof is more than a block above the upper half. It goes when the box goes, and
 * breaking it breaks the box.
 */
public class ExteriorTopBlock extends Block {
    public ExteriorTopBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(PortalDoorBlock.FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PortalDoorBlock.FACING);
    }

    private static boolean isExteriorUpper(BlockState state) {
        return state.is(ArtronBlocks.TEST_EXTERIOR_DOOR.get()) && state.getValue(PortalDoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction directionToNeighbour,
                                     BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (directionToNeighbour == Direction.DOWN && !isExteriorUpper(neighbourState)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // The top has no drops of its own: breaking it breaks the box from its lower half, which drops the item.
        BlockPos lower = pos.below(2);
        if (!level.isClientSide() && level.getBlockState(lower).is(ArtronBlocks.TEST_EXTERIOR_DOOR.get())) {
            level.destroyBlock(lower, !player.isCreative(), player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return new ItemStack(ArtronBlocks.TEST_EXTERIOR_DOOR_ITEM.get());
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return TestExteriorDoorBlock.shape(false, 2, state.getValue(PortalDoorBlock.FACING), level, pos.below(2));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return TestExteriorDoorBlock.shape(true, 2, state.getValue(PortalDoorBlock.FACING), level, pos.below(2));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(PortalDoorBlock.FACING, rotation.rotate(state.getValue(PortalDoorBlock.FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(PortalDoorBlock.FACING)));
    }
}
