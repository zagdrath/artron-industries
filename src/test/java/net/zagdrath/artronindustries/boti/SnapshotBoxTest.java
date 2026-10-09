/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.portal.PortalShape;

class SnapshotBoxTest {
    private static final BlockPos DOOR = new BlockPos(100, 64, -50);
    /** Doors set back into the block in front, like the police box's. */
    private static final PortalShape SET_BACK = new PortalShape(1.125F, 2.5625F, 0.125F, 0.6875F);

    private static double side(Vector3f plane, BlockPos origin, Vec3 world) {
        return plane.x * (world.x - origin.getX()) + plane.y * (world.z - origin.getZ()) + plane.z;
    }

    @ParameterizedTest
    @EnumSource(value = Direction.class, names = {"NORTH", "EAST", "SOUTH", "WEST"})
    void startsAtTheLayerThePlaneCuts(Direction facing) {
        SnapshotBox box = SnapshotBox.inFrontOf(DOOR, facing, SET_BACK, 9, 8, 10);
        // The block right in front of the door (its ground included) is in the box; the door's own block is not.
        BlockPos inFront = DOOR.relative(facing).below();
        assertTrue(box.containsWorld(inFront.getX(), inFront.getY(), inFront.getZ()), () -> facing + ": " + box);
        assertTrue(!box.containsWorld(DOOR.getX(), DOOR.getY(), DOOR.getZ()), () -> facing + ": " + box);

        Vector3f plane = box.clipPlane(DOOR, facing, SET_BACK);
        Vec3 anchor = SET_BACK.anchor(DOOR, facing);
        Vec3 out = Vec3.atLowerCornerOf(facing.getUnitVec3i());
        assertEquals(0.0, side(plane, box.origin(), anchor), 1.0E-5);
        assertTrue(side(plane, box.origin(), anchor.add(out.scale(0.1))) > 0.0);
        assertTrue(side(plane, box.origin(), anchor.subtract(out.scale(0.1))) < 0.0);
    }
}
