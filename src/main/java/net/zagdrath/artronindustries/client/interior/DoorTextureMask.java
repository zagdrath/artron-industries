/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Splits the front texture of a double door into the parts that move and the part that does not. The frame is the
 * background showing round the outside of the leaves: every pixel of one of the frame colours that can be reached from
 * the texture's left or right edge through pixels of those colours only. Everything else is a leaf, the left one in the
 * left half of the texture and the right one in the right half; fully transparent pixels are nothing.
 * <p>
 * Taken from the texture rather than written down, so a resource pack that redraws the door (keeping its frame colours)
 * still gets leaves that follow its outlines.
 */
public final class DoorTextureMask {
    public static final byte EMPTY = -1;
    public static final byte FRAME = 0;
    public static final byte LEFT = 1;
    public static final byte RIGHT = 2;

    /** The Hell Bent door's frame: the roundel wall's background, #6A6E72 speckled with #656A6E. */
    public static final int[] HELL_BENT_FRAME_COLOURS = {0xFF6A6E72, 0xFF656A6E};

    private DoorTextureMask() {}

    /**
     * The part each pixel of an ARGB texture ({@code argb[y * width + x]}, y down) belongs to, in the same layout.
     */
    public static byte[] split(int[] argb, int width, int height, int[] frameColours) {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("Expected " + width * height + " pixels, got " + argb.length);
        }
        byte[] parts = new byte[argb.length];
        for (int index = 0; index < argb.length; index++) {
            int x = index % width;
            parts[index] = argb[index] >>> 24 == 0 ? EMPTY : x < width / 2 ? LEFT : RIGHT;
        }
        Deque<Integer> queue = new ArrayDeque<>();
        for (int y = 0; y < height; y++) {
            queue.add(y * width);
            queue.add(y * width + width - 1);
        }
        while (!queue.isEmpty()) {
            int index = queue.poll();
            if (parts[index] == FRAME || parts[index] == EMPTY || !isFrameColour(argb[index], frameColours)) {
                continue;
            }
            parts[index] = FRAME;
            int x = index % width;
            int y = index / width;
            if (x > 0) {
                queue.add(index - 1);
            }
            if (x < width - 1) {
                queue.add(index + 1);
            }
            if (y > 0) {
                queue.add(index - width);
            }
            if (y < height - 1) {
                queue.add(index + width);
            }
        }
        return parts;
    }

    private static boolean isFrameColour(int argb, int[] frameColours) {
        for (int colour : frameColours) {
            if (argb == colour) {
                return true;
            }
        }
        return false;
    }
}
