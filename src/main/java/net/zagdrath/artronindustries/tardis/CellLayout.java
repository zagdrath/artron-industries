/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

/**
 * Maps a cell index to a grid cell using a square spiral around (0, 0): 0 -> (0,0), 1 -> (1,0), 2 -> (1,1), 3 -> (0,1),
 * 4 -> (-1,1), ... so the first TARDISes stay close to the origin of the interior dimension.
 */
public final class CellLayout {
    private CellLayout() {}

    public record Cell(int x, int z) {}

    public static Cell cell(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative cell index " + index);
        }
        if (index == 0) {
            return new Cell(0, 0);
        }
        // Ring k contains indices (2k-1)^2 .. (2k+1)^2 - 1.
        int k = (int) Math.ceil((Math.sqrt(index + 1) - 1) / 2);
        int side = 2 * k;
        int ringStart = (2 * k - 1) * (2 * k - 1);
        int offset = index - ringStart;
        int leg = offset / side;
        int along = offset % side;
        return switch (leg) {
            case 0 -> new Cell(k, -k + 1 + along);
            case 1 -> new Cell(k - 1 - along, k);
            case 2 -> new Cell(-k, k - 1 - along);
            default -> new Cell(-k + 1 + along, -k);
        };
    }

    /** Inverse of {@link #cell}, or -1 when the coordinates are not a valid cell. */
    public static int index(int x, int z) {
        int k = Math.max(Math.abs(x), Math.abs(z));
        if (k == 0) {
            return 0;
        }
        int side = 2 * k;
        int ringStart = (2 * k - 1) * (2 * k - 1);
        if (x == k && z > -k) {
            return ringStart + (z - (-k + 1));
        } else if (z == k && x < k) {
            return ringStart + side + (k - 1 - x);
        } else if (x == -k && z < k) {
            return ringStart + 2 * side + (k - 1 - z);
        } else {
            return ringStart + 3 * side + (x - (-k + 1));
        }
    }
}
