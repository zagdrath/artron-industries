/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.portal.PortalShape;

class InteriorDoorwayTest {
    private static final double EPS = 1.0E-6;

    /** The Victorian Parlour's door: gray leaves at z 39 with buttons in front at z 38, facing north. */
    @Test
    void parlourDoorway() {
        InteriorDoorway.Placed placed = InteriorDoorway.fromBox(new BlockPos(12, 2, 38), new BlockPos(15, 5, 39), Direction.NORTH, 1.0F);
        InteriorDoorway doorway = placed.doorway();
        // Seen from the room (looking south), left is east: the master is the front bottom east cell.
        assertEquals(new BlockPos(15, 2, 38), placed.master());
        assertEquals(new InteriorDoorway(4, 4, 2, 1.0F), doorway);

        Vec3 anchor = doorway.shape().anchor(placed.master(), Direction.NORTH);
        assertTrue(anchor.distanceTo(new Vec3(14.0, 2.0, 39.0)) < EPS, () -> "anchor " + anchor);
        assertEquals(4.0F, doorway.shape().width());
        assertEquals(4.0F, doorway.shape().height());

        assertEquals(new BlockPos(12, 5, 39), doorway.cell(placed.master(), Direction.NORTH, doorway.index(3, 3, 1)));
        assertTrue(doorway.inLeftLeaf(1));
        assertFalse(doorway.inLeftLeaf(2));
    }

    @ParameterizedTest
    @EnumSource(value = Direction.class, names = {"NORTH", "EAST", "SOUTH", "WEST"})
    void cellsFillTheBox(Direction facing) {
        BlockPos a = new BlockPos(-3, 7, 10);
        BlockPos b = facing.getAxis() == Direction.Axis.Z ? a.offset(2, 3, 1) : a.offset(1, 3, 2);
        InteriorDoorway.Placed placed = InteriorDoorway.fromBox(a, b, facing, 0.5F);
        InteriorDoorway doorway = placed.doorway();
        assertEquals(3, doorway.width());
        assertEquals(2, doorway.depth());
        Set<BlockPos> cells = new HashSet<>();
        for (int index = 0; index < doorway.cellCount(); index++) {
            BlockPos cell = doorway.cell(placed.master(), facing, index);
            cells.add(cell);
            assertTrue(doorway.contains(placed.master(), facing, cell));
            assertEquals(index, doorway.index(doorway.i(index), doorway.j(index), doorway.d(index)));
        }
        Set<BlockPos> box = new HashSet<>();
        BlockPos.betweenClosed(a, b).forEach(p -> box.add(p.immutable()));
        assertEquals(box, cells);
        assertFalse(doorway.contains(placed.master(), facing, placed.master().relative(facing)));
        // The opening is centred on the box and its plane is half a block behind the front.
        Vec3 anchor = doorway.shape().anchor(placed.master(), facing);
        Vec3 centre = Vec3.atLowerCornerOf(BlockPos.min(a, b)).add(Vec3.atLowerCornerOf(BlockPos.max(a, b).offset(1, 0, 1))).scale(0.5);
        Vec3 front = centre.add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(doorway.depth() / 2.0 - 0.5));
        assertTrue(anchor.distanceTo(new Vec3(front.x, a.getY(), front.z)) < EPS, () -> facing + ": anchor " + anchor + ", expected " + front);
    }

    @Test
    void lateralOffsetMovesTheOpening() {
        PortalShape shape = new PortalShape(2.0F, 2.0F, 0.0F, 0.5F, 1.0F);
        BlockPos pos = new BlockPos(0, 0, 0);
        // Facing north, right is west.
        assertTrue(shape.anchor(pos, Direction.NORTH).distanceTo(new Vec3(-0.5, 0.0, 0.0)) < EPS);
        assertEquals(0.0, shape.lateral(pos, Direction.NORTH, new Vec3(-0.5, 0.0, -1.0)), EPS);
    }
}
