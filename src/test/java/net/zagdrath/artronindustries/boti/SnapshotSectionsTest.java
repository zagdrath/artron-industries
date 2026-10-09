/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class SnapshotSectionsTest {
    /** A box that divides evenly into sections along z only. */
    private static final SnapshotSections GRID = SnapshotSections.of(new SnapshotBox(BlockPos.ZERO, 40, 24, 48));

    private static Set<Integer> affected(int x, int y, int z) {
        Set<Integer> out = new TreeSet<>();
        GRID.forEachAffected(x, y, z, out::add);
        return out;
    }

    @Test
    void sectionsCoverTheBoxExactlyOnce() {
        assertEquals(3, GRID.countX());
        assertEquals(2, GRID.countY());
        assertEquals(3, GRID.countZ());
        int[] covered = new int[40 * 24 * 48];
        for (int s = 0; s < GRID.count(); s++) {
            assertEquals(s, GRID.index(GRID.sectionX(s), GRID.sectionY(s), GRID.sectionZ(s)));
            for (int y = GRID.minY(s); y < GRID.maxY(s); y++) {
                for (int z = GRID.minZ(s); z < GRID.maxZ(s); z++) {
                    for (int x = GRID.minX(s); x < GRID.maxX(s); x++) {
                        covered[(y * 48 + z) * 40 + x]++;
                    }
                }
            }
        }
        for (int c : covered) {
            assertEquals(1, c);
        }
        // The last section on each axis is cut short by the box.
        int last = GRID.index(2, 1, 2);
        assertEquals(40, GRID.maxX(last));
        assertEquals(24, GRID.maxY(last));
        assertEquals(48, GRID.maxZ(last));
    }

    @Test
    void insideBlockOnlyAffectsItsOwnSection() {
        assertEquals(Set.of(GRID.index(1, 0, 1)), affected(20, 5, 20));
    }

    @Test
    void borderBlockAlsoAffectsItsNeighbours() {
        assertEquals(Set.of(GRID.index(0, 0, 0), GRID.index(1, 0, 0)), affected(15, 5, 5));
        assertEquals(Set.of(GRID.index(0, 0, 0), GRID.index(1, 0, 0)), affected(16, 5, 5));
        // A corner touches all eight sections around it.
        assertEquals(8, affected(16, 16, 16).size());
    }

    @Test
    void boxEdgesDoNotReachOutside() {
        Set<Integer> corner = affected(0, 0, 0);
        assertEquals(Set.of(GRID.index(0, 0, 0)), corner);
        Set<Integer> far = affected(39, 23, 47);
        assertEquals(Set.of(GRID.index(2, 1, 2)), far);
        for (int s : affected(31, 15, 31)) {
            assertTrue(s >= 0 && s < GRID.count());
        }
    }
}
