/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

class DoorPairTransformTest {
    private static final double EPS = 1.0E-9;
    private static final BlockPos EXTERIOR = new BlockPos(-123, 70, 456);
    private static final BlockPos INTERIOR = new BlockPos(8192, 65, -4089);

    static Stream<Arguments> facingPairs() {
        List<Arguments> args = new ArrayList<>();
        for (Direction ext : Direction.Plane.HORIZONTAL) {
            for (Direction in : Direction.Plane.HORIZONTAL) {
                args.add(Arguments.of(ext, in));
            }
        }
        assertEquals(16, args.size());
        return args.stream();
    }

    private static DoorPairTransform transform(Direction ext, Direction in) {
        PortalShape shape = PortalShape.DEFAULT_DOOR;
        return DoorPairTransform.between(shape.anchor(EXTERIOR, ext), ext, shape.anchor(INTERIOR, in), in);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertTrue(expected.distanceTo(actual) < EPS, () -> "expected " + expected + " but was " + actual);
    }

    @ParameterizedTest
    @MethodSource("facingPairs")
    void positionsRoundTrip(Direction ext, Direction in) {
        DoorPairTransform t = transform(ext, in);
        DoorPairTransform inv = t.invert();
        for (Vec3 p : new Vec3[]{Vec3.ZERO, new Vec3(1.25, -3.5, 7.75), new Vec3(-123.4, 70.0, 456.6), new Vec3(1e5, 12, -1e5)}) {
            assertVec(p, inv.apply(t.apply(p)));
            assertVec(p, t.apply(inv.apply(p)));
        }
    }

    @ParameterizedTest
    @MethodSource("facingPairs")
    void anchorsMapOntoEachOther(Direction ext, Direction in) {
        DoorPairTransform t = transform(ext, in);
        assertVec(PortalShape.DEFAULT_DOOR.anchor(INTERIOR, in), t.apply(PortalShape.DEFAULT_DOOR.anchor(EXTERIOR, ext)));
        assertVec(PortalShape.DEFAULT_DOOR.anchor(EXTERIOR, ext), t.invert().apply(PortalShape.DEFAULT_DOOR.anchor(INTERIOR, in)));
    }

    @ParameterizedTest
    @MethodSource("facingPairs")
    void walkingIntoExteriorWalksOutOfInterior(Direction ext, Direction in) {
        DoorPairTransform t = transform(ext, in);
        Vec3 intoExterior = Vec3.atLowerCornerOf(ext.getOpposite().getUnitVec3i());
        assertVec(Vec3.atLowerCornerOf(in.getUnitVec3i()), t.applyVelocity(intoExterior));
        assertEquals(in, t.applyDirection(ext.getOpposite()));
        // A point just behind the exterior plane lands just in front of the interior plane.
        Vec3 behind = PortalShape.DEFAULT_DOOR.center(EXTERIOR, ext).add(intoExterior.scale(0.1));
        assertEquals(0.1, PortalShape.DEFAULT_DOOR.signedDistance(INTERIOR, in, t.apply(behind)), EPS);
    }

    @ParameterizedTest
    @MethodSource("facingPairs")
    void yawAndVelocityRoundTrip(Direction ext, Direction in) {
        DoorPairTransform t = transform(ext, in);
        DoorPairTransform inv = t.invert();
        Vec3 v = new Vec3(0.3, -0.08, -0.17);
        assertVec(v, inv.applyVelocity(t.applyVelocity(v)));
        for (float yaw : new float[]{0.0F, 37.5F, -170.0F, 359.0F}) {
            assertEquals(0.0F, wrap(inv.applyYaw(t.applyYaw(yaw)) - yaw), 1.0E-4);
        }
        // Yaw follows the rotation: looking into the exterior door becomes looking into the room.
        float lookIn = ext.getOpposite().toYRot();
        assertEquals(0.0F, wrap(t.applyYaw(lookIn) - in.toYRot()), 1.0E-4);
    }

    @ParameterizedTest
    @MethodSource("facingPairs")
    void lateralSideIsPreserved(Direction ext, Direction in) {
        // No mirroring: the viewer's right through the exterior door is the viewer's right when coming out inside.
        DoorPairTransform t = transform(ext, in);
        Vec3 right = Vec3.atLowerCornerOf(PortalShape.right(ext).getUnitVec3i());
        Vec3 mapped = t.applyVelocity(right);
        Vec3 expectedRight = Vec3.atLowerCornerOf(in.getClockWise().getUnitVec3i());
        assertVec(expectedRight, mapped);
    }

    private static float wrap(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }
}
