/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import java.util.function.IntConsumer;

/**
 * Splits a {@link SnapshotBox} into cubes of {@link #SIZE} blocks (box-local, starting at the box's lowest corner; the
 * last cube on each axis is cut short by the box), so the client can mesh, rebuild, cull and sort the view in pieces.
 * Sections are numbered x fastest, then z, then y, like the blocks of the box.
 */
public record SnapshotSections(int sizeX, int sizeY, int sizeZ) {
    public static final int SIZE = 16;

    public static SnapshotSections of(SnapshotBox box) {
        return new SnapshotSections(box.sizeX(), box.sizeY(), box.sizeZ());
    }

    public int countX() {
        return (this.sizeX + SIZE - 1) / SIZE;
    }

    public int countY() {
        return (this.sizeY + SIZE - 1) / SIZE;
    }

    public int countZ() {
        return (this.sizeZ + SIZE - 1) / SIZE;
    }

    public int count() {
        return this.countX() * this.countY() * this.countZ();
    }

    public int index(int sx, int sy, int sz) {
        return (sy * this.countZ() + sz) * this.countX() + sx;
    }

    public int sectionX(int section) {
        return section % this.countX();
    }

    public int sectionZ(int section) {
        return (section / this.countX()) % this.countZ();
    }

    public int sectionY(int section) {
        return section / (this.countX() * this.countZ());
    }

    /** Box-local lowest corner of a section, inclusive. */
    public int minX(int section) {
        return this.sectionX(section) * SIZE;
    }

    public int minY(int section) {
        return this.sectionY(section) * SIZE;
    }

    public int minZ(int section) {
        return this.sectionZ(section) * SIZE;
    }

    /** Box-local highest corner of a section, exclusive. */
    public int maxX(int section) {
        return Math.min(this.sizeX, this.minX(section) + SIZE);
    }

    public int maxY(int section) {
        return Math.min(this.sizeY, this.minY(section) + SIZE);
    }

    public int maxZ(int section) {
        return Math.min(this.sizeZ, this.minZ(section) + SIZE);
    }

    /**
     * Every section whose mesh can change when the block at box-local {@code (x, y, z)} does: its own, and the neighbouring
     * ones when it is on their border, since face culling, ambient occlusion, smooth light and fluid heights all look one
     * block around each block.
     */
    public void forEachAffected(int x, int y, int z, IntConsumer out) {
        int x0 = Math.max(0, x - 1) / SIZE;
        int x1 = Math.min(this.sizeX - 1, x + 1) / SIZE;
        int y0 = Math.max(0, y - 1) / SIZE;
        int y1 = Math.min(this.sizeY - 1, y + 1) / SIZE;
        int z0 = Math.max(0, z - 1) / SIZE;
        int z1 = Math.min(this.sizeZ - 1, z + 1) / SIZE;
        for (int sy = y0; sy <= y1; sy++) {
            for (int sz = z0; sz <= z1; sz++) {
                for (int sx = x0; sx <= x1; sx++) {
                    out.accept(this.index(sx, sy, sz));
                }
            }
        }
    }
}
