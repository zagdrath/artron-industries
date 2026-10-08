/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * The TARDIS exterior, for now always the Hudolin police box (drawn by {@code HudolinExteriorRenderer}). Placing it
 * allocates a new TARDIS and links this door as its exterior.
 * <p>
 * The box is 1 3/4 blocks square and 3 1/4 tall, centred on the lower block, with its double doors on the {@code FACING}
 * side. Shapes below are in block pixels for a north-facing box; the doors are 18 px wide, 41 px tall and stand on the
 * 2 px base, their front face 11 px in front of the block centre.
 */
public class TestExteriorDoorBlock extends PortalDoorBlock {
    /**
     * The doorway: the opening between the door frame, its plane a quarter pixel in front of the shut doors so they never
     * fight it for depth. The doors swing in behind it.
     */
    public static final PortalShape HUDOLIN_DOORWAY = new PortalShape(18.0F / 16.0F, 41.0F / 16.0F, 2.0F / 16.0F, 11.25F / 16.0F);

    private static final VoxelShape BASE = Block.box(-6, 0, -6, 22, 2, 22);
    /** Side walls with the corner posts, the back wall, and the front frame either side of the doorway. */
    private static final VoxelShape HULL = Shapes.or(
            Block.box(-5, 0, -5, -2, 16, 21),
            Block.box(18, 0, -5, 21, 16, 21),
            Block.box(-5, 0, 18, 21, 16, 21),
            Block.box(-5, 0, -5, -1, 16, -2),
            Block.box(17, 0, -5, 21, 16, -2));
    /** The shut leaves. "Right" is the right of someone outside looking in, west of a north-facing box. */
    private static final VoxelShape RIGHT_LEAF = Block.box(-1, 0, -3, 8, 16, -2);
    private static final VoxelShape LEFT_LEAF = Block.box(8, 0, -3, 17, 16, -2);
    private static final Map<Direction, VoxelShape> LOWER_OUTLINE = Shapes.rotateHorizontal(Block.box(-6, 0, -6, 22, 16, 22));
    private static final Map<Direction, VoxelShape> UPPER_OUTLINE = Shapes.rotateHorizontal(Block.box(-6, 0, -6, 22, 36, 22));
    /** Collision by half (lower first) and door state. */
    private static final Map<DoorState, Map<Direction, VoxelShape>>[] COLLISION = collisionShapes();

    public TestExteriorDoorBlock(Properties properties) {
        super(properties);
    }

    @SuppressWarnings("unchecked")
    private static Map<DoorState, Map<Direction, VoxelShape>>[] collisionShapes() {
        Map<DoorState, Map<Direction, VoxelShape>>[] shapes = new Map[2];
        for (int half = 0; half < 2; half++) {
            shapes[half] = new EnumMap<>(DoorState.class);
            for (DoorState doors : DoorState.values()) {
                VoxelShape shape = half == 0 ? Shapes.or(BASE, HULL) : HULL;
                if (!doors.rightOpen()) {
                    shape = Shapes.or(shape, RIGHT_LEAF);
                }
                if (!doors.leftOpen()) {
                    shape = Shapes.or(shape, LEFT_LEAF);
                }
                shapes[half].put(doors, Shapes.rotateHorizontal(shape));
            }
        }
        return shapes;
    }

    @Override
    protected BlockEntityType<? extends PortalDoorBlockEntity> blockEntityType() {
        return ArtronBlockEntities.TEST_EXTERIOR_DOOR.get();
    }

    @Override
    public PortalShape portalShape(BlockState state) {
        return HUDOLIN_DOORWAY;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        return (lower ? LOWER_OUTLINE : UPPER_OUTLINE).get(state.getValue(FACING));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        boolean lower = state.getValue(HALF) == DoubleBlockHalf.LOWER;
        DoorState doors = level.getBlockEntity(lower ? pos : pos.below()) instanceof PortalDoorBlockEntity door ? door.doorState() : DoorState.CLOSED;
        return COLLISION[lower ? 0 : 1].get(doors).get(state.getValue(FACING));
    }

    @Override
    protected void onPlacedServer(ServerLevel level, BlockPos pos, BlockState state, PortalDoorBlockEntity door, @Nullable LivingEntity placer) {
        TardisRecord record = TardisInteriorManager.get(level.getServer())
                .create(level, pos, state.getValue(FACING), this.portalShape(state));
        door.link(record.uuid(), DoorState.CLOSED);
        if (placer instanceof Player player) {
            player.sendOverlayMessage(Component.translatable("message.artronindustries.tardis.created", record.id()));
        }
    }

    /**
     * Places both halves of a door at {@code pos} and allocates a new TARDIS for it, as if a player had placed it.
     * Returns {@code null} if there is no room.
     */
    public @Nullable TardisRecord placeNewTardis(ServerLevel level, BlockPos pos, Direction facing) {
        if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.above()).canBeReplaced()) {
            return null;
        }
        BlockState lower = this.defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.LOWER);
        level.setBlockAndUpdate(pos, lower);
        level.setBlockAndUpdate(pos.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER));
        if (!(level.getBlockEntity(pos) instanceof PortalDoorBlockEntity door)) {
            return null;
        }
        TardisRecord record = TardisInteriorManager.get(level.getServer()).create(level, pos, facing, this.portalShape(lower));
        door.link(record.uuid(), DoorState.CLOSED);
        return record;
    }
}
