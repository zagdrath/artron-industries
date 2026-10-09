/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.block.entity.InteriorDoorwayBlockEntity;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;
import net.zagdrath.artronindustries.tardis.interior.InteriorDoorway;

/**
 * One cell of an {@link InteriorDoorway}, the door of a template interior made of its own blocks. Draws nothing: the
 * {@link InteriorDoorwayBlockEntity} in the {@link #MASTER} cell keeps the blocks the cells replaced and draws them as the
 * two leaves. A shut cell collides like the block it replaced did ({@link #SOLID}); an open one does not, but can still
 * be clicked, so the doors can be shut from inside.
 */
public class InteriorDoorwayBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty MASTER = BooleanProperty.create("master");
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final BooleanProperty SOLID = BooleanProperty.create("solid");

    public InteriorDoorwayBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MASTER, false)
                .setValue(OPEN, false).setValue(SOLID, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, MASTER, OPEN, SOLID);
    }

    /**
     * Turns the blocks in the doorway box at {@code master} into doorway cells and links the doorway to {@code record} as
     * its interior door. The replaced blocks' own block entity data (if any) is not kept.
     */
    public static void build(ServerLevel level, TardisRecord record, BlockPos master, Direction facing, InteriorDoorway doorway) {
        List<BlockState> leaves = new ArrayList<>(doorway.cellCount());
        List<Boolean> solid = new ArrayList<>(doorway.cellCount());
        for (int index = 0; index < doorway.cellCount(); index++) {
            BlockPos pos = doorway.cell(master, facing, index);
            BlockState original = level.getBlockState(pos);
            leaves.add(original);
            solid.add(!original.getCollisionShape(level, pos).isEmpty());
        }
        BlockState cell = ArtronBlocks.INTERIOR_DOORWAY.get().defaultBlockState().setValue(FACING, facing);
        for (int index = 0; index < doorway.cellCount(); index++) {
            level.setBlock(doorway.cell(master, facing, index), cell.setValue(MASTER, index == 0).setValue(SOLID, solid.get(index)), CELL_UPDATE);
        }
        if (level.getBlockEntity(master) instanceof InteriorDoorwayBlockEntity door) {
            door.install(doorway, leaves);
            door.link(record.uuid(), record.doorState());
        }
    }

    /** Cells change without shape updates, so the build around them keeps its connections. */
    public static final int CELL_UPDATE = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(SOLID) && !state.getValue(OPEN) ? Shapes.block() : Shapes.empty();
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level instanceof ServerLevel serverLevel) {
            Component failure = toggle(serverLevel, pos);
            if (failure != null) {
                player.sendOverlayMessage(failure);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** Moves the doors of the TARDIS this cell's doorway belongs to on to their next state. */
    private static @Nullable Component toggle(ServerLevel level, BlockPos pos) {
        TardisInteriorManager manager = TardisInteriorManager.get(level.getServer());
        TardisRecord record = TardisInteriorManager.isInterior(level) ? manager.byInteriorPos(pos) : null;
        PortalDoorBlockEntity door = record == null ? null : manager.loadedDoor(level.getServer(), record, PortalSide.INTERIOR);
        if (!(door instanceof InteriorDoorwayBlockEntity doorway) || !doorway.contains(pos)) {
            return Component.translatable("message.artronindustries.door.unlinked");
        }
        return doorway.toggle(level);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return ItemStack.EMPTY;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(MASTER) ? new InteriorDoorwayBlockEntity(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == ArtronBlockEntities.INTERIOR_DOORWAY.get() ? (l, p, s, be) -> ((InteriorDoorwayBlockEntity) be).tick() : null;
    }
}
