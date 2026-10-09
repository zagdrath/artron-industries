/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * The out-of-box light lookup SnapshotBlockGetter#getBrightness does ({@link SnapshotBox#clampedIndexOfWorld}): outside
 * the box, the nearest box position's light, so the box's outermost row is not smooth-lit against darkness.
 */
class SnapshotLightClampTest {
    /** 4 wide, 3 high, 5 deep; ground (no light) in the bottom layer, open air (sky 15) above it. */
    private static final SnapshotBox BOX = new SnapshotBox(new BlockPos(10, 60, 20), 4, 3, 5);
    private static final byte[] LIGHT = new byte[BOX.volume()];

    static {
        for (int i = 0; i < LIGHT.length; i++) {
            int y = BOX.localY(i);
            LIGHT[i] = y == 0 ? PortalSnapshot.packLight(0, 0) : PortalSnapshot.packLight(BOX.localX(i), 15);
        }
    }

    private static int sky(int x, int y, int z) {
        return PortalSnapshot.skyLight(LIGHT[BOX.clampedIndexOfWorld(x, y, z)]);
    }

    private static int block(int x, int y, int z) {
        return PortalSnapshot.blockLight(LIGHT[BOX.clampedIndexOfWorld(x, y, z)]);
    }

    @Test
    void insideIsUnchanged() {
        for (int i = 0; i < BOX.volume(); i++) {
            BlockPos p = BOX.worldPos(i);
            assertEquals(i, BOX.clampedIndexOfWorld(p.getX(), p.getY(), p.getZ()));
        }
    }

    @Test
    void airBesideTheBoxIsLitLikeTheEdgeColumn() {
        // The air just above the ground, one and many blocks outside each side: full sky light, not 0.
        for (int out : new int[]{1, 50}) {
            assertEquals(15, sky(10 - out, 61, 22));
            assertEquals(15, sky(13 + out, 61, 22));
            assertEquals(15, sky(11, 61, 20 - out));
            assertEquals(15, sky(11, 61, 24 + out));
            assertEquals(15, sky(13 + out, 61, 24 + out));
        }
        // The edge column's own value, not just any: block light varies along x here.
        assertEquals(0, block(10 - 3, 61, 22));
        assertEquals(3, block(13 + 3, 61, 22));
        assertEquals(2, block(12, 61, 24 + 3));
    }

    @Test
    void aboveAndBelowClampToTheTopAndBottomLayers() {
        assertEquals(15, sky(11, 62 + 10, 22));
        assertEquals(0, sky(11, 60 - 10, 22));
        assertEquals(0, sky(10 - 5, 60 - 5, 20 - 5));
    }
}
