/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import java.util.ArrayList;
import java.util.List;

/**
 * The pixels of one part of a {@link DoorTextureMask} made solid, the way an item's sprite is: a slab {@code depth} thick
 * whose front shows the texture, whose back shows it mirrored (the same pixels seen from behind) and whose edges, all
 * round the part's outline, show the colour of the pixel along them.
 * <p>
 * Pixels are merged into longer quads only along rows, and only between cuts (see {@code cuts}), so that the surface is
 * watertight: every corner of every quad is a corner of the quads next to it, never part way along one of their edges (a
 * T-junction, which rasterises as a crack showing whatever is behind).
 * <p>
 * Door space, in blocks, one texture pixel being {@code pixel} blocks across: x right and y up across the texture as seen
 * from the front, its bottom left corner at the origin, and z out of the front, which is at z = 0 (the back at z =
 * -depth).
 */
public final class ExtrudedPixels {
    /**
     * One quad, counter-clockwise seen from the side its normal points to.
     *
     * @param vertices x, y, z, u, v of each of the four corners in turn; u and v are fractions of the texture
     */
    public record Quad(float[] vertices, float nx, float ny, float nz) {}

    private final byte[] parts;
    private final int width;
    private final int height;
    private final byte part;
    private final float pixel;
    private final float depth;
    /** Every x at which, in some row, the part starts or stops: all quads along rows are cut there. */
    private final boolean[] cuts;
    private final List<Quad> quads = new ArrayList<>();

    private ExtrudedPixels(byte[] parts, int width, int height, byte part, float pixel, float depth) {
        this.parts = parts;
        this.width = width;
        this.height = height;
        this.part = part;
        this.pixel = pixel;
        this.depth = depth;
        this.cuts = new boolean[width + 1];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x <= width; x++) {
                this.cuts[x] |= this.in(x - 1, y) != this.in(x, y);
            }
        }
    }

    /** The faces of part {@code part} of a {@code width} by {@code height} mask (y down, as from DoorTextureMask). */
    public static List<Quad> extrude(byte[] parts, int width, int height, byte part, float pixel, float depth) {
        ExtrudedPixels pixels = new ExtrudedPixels(parts, width, height, part, pixel, depth);
        pixels.faces();
        pixels.sides();
        return List.copyOf(pixels.quads);
    }

    private boolean in(int x, int y) {
        return x >= 0 && x < this.width && y >= 0 && y < this.height && this.parts[y * this.width + x] == this.part;
    }

    /** Front and back: each row's pixels, from cut to cut. */
    private void faces() {
        for (int y = 0; y < this.height; y++) {
            for (int x = 0; x < this.width; ) {
                if (!this.in(x, y)) {
                    x++;
                    continue;
                }
                int start = x++;
                while (this.in(x, y) && !this.cuts[x]) {
                    x++;
                }
                this.rectangle(start, y, x, y + 1);
            }
        }
    }

    /** Pixels {@code x0} to {@code x1} (exclusive) across, rows {@code y0} to {@code y1} (exclusive) down the texture. */
    private void rectangle(int x0, int y0, int x1, int y1) {
        float left = x0 * this.pixel;
        float right = x1 * this.pixel;
        float bottom = (this.height - y1) * this.pixel;
        float top = (this.height - y0) * this.pixel;
        float u0 = (float) x0 / this.width;
        float u1 = (float) x1 / this.width;
        float v0 = (float) y0 / this.height;
        float v1 = (float) y1 / this.height;
        for (float z : new float[]{0.0F, -this.depth}) {
            this.quad(0.0F, 0.0F, z == 0.0F ? 1.0F : -1.0F, new float[]{
                    left, bottom, z, u0, v1,
                    right, bottom, z, u1, v1,
                    right, top, z, u1, v0,
                    left, top, z, u0, v0});
        }
    }

    /** The edges: wherever a pixel's neighbour is not in the part, a face one pixel wide and the slab deep. */
    private void sides() {
        for (int x = 0; x < this.width; x++) {
            this.columnEdges(x, -1);
            this.columnEdges(x, 1);
        }
        for (int y = 0; y < this.height; y++) {
            this.rowEdges(y, -1);
            this.rowEdges(y, 1);
        }
    }

    /** The left ({@code side} -1) or right edges of column {@code x}, a pixel each, as the faces they meet are a row tall. */
    private void columnEdges(int x, int side) {
        float plane = (side < 0 ? x : x + 1) * this.pixel;
        float u = (x + 0.5F) / this.width;
        for (int start = 0; start < this.height; start++) {
            if (!this.in(x, start) || this.in(x + side, start)) {
                continue;
            }
            int y = start + 1;
            float top = (this.height - start) * this.pixel;
            float bottom = (this.height - y) * this.pixel;
            float v0 = (float) start / this.height;
            float v1 = (float) y / this.height;
            this.quad(side, 0.0F, 0.0F, new float[]{
                    plane, bottom, 0.0F, u, v1,
                    plane, bottom, -this.depth, u, v1,
                    plane, top, -this.depth, u, v0,
                    plane, top, 0.0F, u, v0});
        }
    }

    /** The top ({@code side} -1, up the texture) or bottom edges of row {@code y}, in runs across it from cut to cut. */
    private void rowEdges(int y, int side) {
        float plane = (this.height - (side < 0 ? y : y + 1)) * this.pixel;
        float v = (y + 0.5F) / this.height;
        for (int x = 0; x < this.width; ) {
            if (!this.in(x, y) || this.in(x, y + side)) {
                x++;
                continue;
            }
            int start = x++;
            while (this.in(x, y) && !this.in(x, y + side) && !this.cuts[x]) {
                x++;
            }
            float left = start * this.pixel;
            float right = x * this.pixel;
            float u0 = (float) start / this.width;
            float u1 = (float) x / this.width;
            this.quad(0.0F, -side, 0.0F, new float[]{
                    left, plane, 0.0F, u0, v,
                    right, plane, 0.0F, u1, v,
                    right, plane, -this.depth, u1, v,
                    left, plane, -this.depth, u0, v});
        }
    }

    /** Adds a quad, turning its corners round if need be so they go counter-clockwise about its normal. */
    private void quad(float nx, float ny, float nz, float[] v) {
        float ax = v[5] - v[0];
        float ay = v[6] - v[1];
        float az = v[7] - v[2];
        float bx = v[10] - v[0];
        float by = v[11] - v[1];
        float bz = v[12] - v[2];
        float facing = (ay * bz - az * by) * nx + (az * bx - ax * bz) * ny + (ax * by - ay * bx) * nz;
        if (facing < 0.0F) {
            // Swap the second and fourth corners.
            for (int k = 0; k < 5; k++) {
                float t = v[5 + k];
                v[5 + k] = v[15 + k];
                v[15 + k] = t;
            }
        }
        this.quads.add(new Quad(v, nx, ny, nz));
    }
}
