/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.zagdrath.artronindustries.block.PortalDoorBlock;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.registry.ArtronBlocks;

/**
 * Builds the placeholder starter room. It deliberately contains blocks that exercise the BOTI mesher: biome tint (grass,
 * leaves), translucency (water, stained glass), cutout (glass pane, flowers), emissive blocks and a block entity with a
 * renderer (chest).
 */
public final class InteriorGenerator {
    /** Room interior spans -HALF..HALF on X and Z around the origin; walls sit at +-(HALF + 1). */
    public static final int HALF = 6;
    public static final int ROOM_HEIGHT = 6;
    /** Largest horizontal distance from the origin that still belongs to a cell's interior. */
    public static final int CLAIM_RADIUS = 128;

    private InteriorGenerator() {}

    /** Lower half of the interior door: in the south wall, facing into the room. */
    public static BlockPos doorPos(BlockPos origin) {
        return origin.offset(0, 1, HALF + 1);
    }

    public static Direction doorFacing() {
        return Direction.NORTH;
    }

    public static void generate(ServerLevel level, TardisRecord record) {
        BlockPos o = record.interiorOrigin();
        int wall = HALF + 1;
        for (int cx = (o.getX() - wall - 1) >> 4; cx <= (o.getX() + wall + 1) >> 4; cx++) {
            for (int cz = (o.getZ() - wall - 1) >> 4; cz <= (o.getZ() + wall + 1) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }

        BlockState floor = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState walls = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
        BlockState roundel = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState ceiling = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
        BlockState lamp = Blocks.GLOWSTONE.defaultBlockState();

        for (int x = -wall; x <= wall; x++) {
            for (int z = -wall; z <= wall; z++) {
                set(level, o.offset(x, 0, z), floor);
                boolean lampSpot = Math.floorMod(x, 4) == 0 && Math.floorMod(z, 4) == 0;
                set(level, o.offset(x, ROOM_HEIGHT + 1, z), lampSpot ? lamp : ceiling);
                for (int y = 1; y <= ROOM_HEIGHT; y++) {
                    boolean edge = Math.abs(x) == wall || Math.abs(z) == wall;
                    BlockState state;
                    if (edge) {
                        boolean roundelSpot = y == 3 && Math.floorMod(x + z, 3) == 0;
                        state = roundelSpot ? roundel : walls;
                    } else {
                        state = Blocks.AIR.defaultBlockState();
                    }
                    set(level, o.offset(x, y, z), state);
                }
            }
        }

        // Console placeholder.
        set(level, o.offset(0, 1, 0), Blocks.BEACON.defaultBlockState());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            set(level, o.offset(d.getStepX(), 1, d.getStepZ()), Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState());
        }

        // Tint, translucency and cutout test corner (north-west).
        set(level, o.offset(-5, 1, -5), Blocks.GRASS_BLOCK.defaultBlockState());
        set(level, o.offset(-5, 2, -5), Blocks.POPPY.defaultBlockState());
        set(level, o.offset(-4, 1, -5), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        set(level, o.offset(-5, 1, -4), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        for (int x = 3; x <= 4; x++) {
            for (int z = -5; z <= -4; z++) {
                set(level, o.offset(x, 0, z), Blocks.WATER.defaultBlockState());
            }
        }
        for (int y = 1; y <= 3; y++) {
            set(level, o.offset(-HALF, y, 0), Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState());
            set(level, o.offset(HALF, y, 0), Blocks.GLASS_PANE.defaultBlockState()
                    .setValue(BlockStateProperties.NORTH, true).setValue(BlockStateProperties.SOUTH, true));
        }
        set(level, o.offset(4, 1, 4), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH));
        set(level, o.offset(-4, 1, 4), Blocks.TORCH.defaultBlockState());

        // The interior door and a backing wall behind it.
        BlockPos door = record.interiorDoorPos();
        Direction facing = record.interiorDoorFacing();
        BlockState lower = ArtronBlocks.INTERIOR_DOOR.get().defaultBlockState()
                .setValue(PortalDoorBlock.FACING, facing)
                .setValue(PortalDoorBlock.HALF, DoubleBlockHalf.LOWER);
        set(level, door, lower);
        set(level, door.above(), lower.setValue(PortalDoorBlock.HALF, DoubleBlockHalf.UPPER));
        set(level, door.relative(facing.getOpposite()), walls);
        set(level, door.above().relative(facing.getOpposite()), walls);
        if (level.getBlockEntity(door) instanceof PortalDoorBlockEntity be) {
            be.link(record.uuid(), record.doorState());
        }
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}
