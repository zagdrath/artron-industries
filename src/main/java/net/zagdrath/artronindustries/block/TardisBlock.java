/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.block.entity.TardisBlockEntity;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;
import net.zagdrath.artronindustries.tardis.exterior.TardisExterior;
import net.zagdrath.artronindustries.tardis.exterior.TardisExteriors;
import net.zagdrath.artronindustries.tardis.interior.TardisInterior;
import net.zagdrath.artronindustries.tardis.interior.TardisInteriors;

/**
 * The TARDIS: its exterior, whose doors lead into its interior. Which exterior it shows and which interior it is built
 * with are attributes of its block entity ({@link TardisBlockEntity}); an item can set them through its
 * {@code block_entity_data} component, and they default to {@link TardisExteriors#DEFAULT} and {@link TardisInteriors#DEFAULT}.
 * Placing one allocates a new TARDIS and links this door as its exterior.
 * <p>
 * The box takes three blocks: the two halves of this block, and a {@link TardisTopBlock} above for the roof. The
 * exterior draws itself and supplies the outline and collision of all three.
 */
public class TardisBlock extends PortalDoorBlock {
    public TardisBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected BlockEntityType<? extends PortalDoorBlockEntity> blockEntityType() {
        return ArtronBlockEntities.TARDIS.get();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** Both halves outline the whole box, so it looks the same whichever half is aimed at. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(false, state, level, pos);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(true, state, level, pos);
    }

    private static VoxelShape shape(boolean collision, BlockState state, BlockGetter level, BlockPos pos) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        return shape(collision, lower ? 0 : 1, state.getValue(FACING), level, lower ? pos : pos.below());
    }

    /** The box's outline or collision for one of its parts (0 lower, 1 upper, 2 top), with the doors as they are. */
    static VoxelShape shape(boolean collision, int part, Direction facing, BlockGetter level, BlockPos lowerPos) {
        TardisExterior exterior = TardisExteriors.DEFAULT;
        DoorState doors = DoorState.CLOSED;
        if (level.getBlockEntity(lowerPos) instanceof TardisBlockEntity tardis) {
            exterior = tardis.exterior();
            doors = tardis.doorState();
        }
        return exterior.shape(collision, part, doors, facing);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        BlockPos top = context.getClickedPos().above(2);
        return state != null && top.getY() <= context.getLevel().getMaxY() && context.getLevel().getBlockState(top).canBeReplaced(context) ? state : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack) {
        super.setPlacedBy(level, pos, state, by, itemStack);
        placeTop(level, pos, state.getValue(FACING));
    }

    /** Puts the {@link TardisTopBlock} above a box that lacks one (boxes placed before it existed), if there is room. */
    public static void placeTop(Level level, BlockPos lowerPos, Direction facing) {
        BlockPos top = lowerPos.above(2);
        BlockState current = level.getBlockState(top);
        if (!current.is(ArtronBlocks.TARDIS_TOP.get()) && current.canBeReplaced()) {
            level.setBlockAndUpdate(top, ArtronBlocks.TARDIS_TOP.get().defaultBlockState().setValue(FACING, facing));
        }
    }

    /** The item's {@code block_entity_data}, if any, has already been applied to {@code door}. */
    @Override
    protected void onPlacedServer(ServerLevel level, BlockPos pos, BlockState state, PortalDoorBlockEntity door, @Nullable LivingEntity placer) {
        if (!(door instanceof TardisBlockEntity tardis)) {
            return;
        }
        TardisRecord record = this.createTardis(level, pos, state.getValue(FACING), tardis);
        if (placer instanceof Player player) {
            player.sendOverlayMessage(Component.translatable("message.artronindustries.tardis.created", record.id()));
        }
    }

    private TardisRecord createTardis(ServerLevel level, BlockPos pos, Direction facing, TardisBlockEntity tardis) {
        TardisInteriorManager manager = TardisInteriorManager.get(level.getServer());
        TardisRecord record = manager.create(level, pos, facing, tardis.exterior(), tardis.interior());
        tardis.link(record.uuid(), DoorState.CLOSED);
        // Built now rather than on first opening, so the view through the doors can be streamed before they open.
        manager.ensureInterior(level.getServer(), record);
        return record;
    }

    /** {@link #placeNewTardis(ServerLevel, BlockPos, Direction, TardisExterior, TardisInterior)} with the default exterior and interior. */
    public @Nullable TardisRecord placeNewTardis(ServerLevel level, BlockPos pos, Direction facing) {
        return this.placeNewTardis(level, pos, facing, TardisExteriors.DEFAULT, TardisInteriors.DEFAULT);
    }

    /**
     * Places all three blocks of a TARDIS at {@code pos} and allocates a new TARDIS for it, as if a player had placed it.
     * Returns {@code null} if there is no room.
     */
    public @Nullable TardisRecord placeNewTardis(ServerLevel level, BlockPos pos, Direction facing, TardisExterior exterior, TardisInterior interior) {
        if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.above()).canBeReplaced()
                || !level.getBlockState(pos.above(2)).canBeReplaced()) {
            return null;
        }
        BlockState lower = this.defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.LOWER);
        level.setBlockAndUpdate(pos, lower);
        level.setBlockAndUpdate(pos.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER));
        placeTop(level, pos, facing);
        if (!(level.getBlockEntity(pos) instanceof TardisBlockEntity tardis)) {
            return null;
        }
        tardis.setAttributes(exterior, interior);
        return this.createTardis(level, pos, facing, tardis);
    }
}
