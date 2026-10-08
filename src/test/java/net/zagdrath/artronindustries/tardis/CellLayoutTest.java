/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CellLayoutTest {
    @Test
    void spiralIsUniqueCompactAndInvertible() {
        Set<CellLayout.Cell> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            CellLayout.Cell c = CellLayout.cell(i);
            assertTrue(seen.add(c), "duplicate cell " + c + " at " + i);
            assertEquals(i, CellLayout.index(c.x(), c.z()));
        }
        // The first (2k+1)^2 cells fill the square of radius k exactly.
        for (int i = 0; i < 81; i++) {
            CellLayout.Cell c = CellLayout.cell(i);
            assertTrue(Math.abs(c.x()) <= 4 && Math.abs(c.z()) <= 4);
        }
    }

    @Test
    void firstCells() {
        assertEquals(new CellLayout.Cell(0, 0), CellLayout.cell(0));
        assertEquals(new CellLayout.Cell(1, 0), CellLayout.cell(1));
        assertEquals(new CellLayout.Cell(1, 1), CellLayout.cell(2));
        assertEquals(new CellLayout.Cell(0, 1), CellLayout.cell(3));
        assertEquals(new CellLayout.Cell(1, -1), CellLayout.cell(8));
    }
}
