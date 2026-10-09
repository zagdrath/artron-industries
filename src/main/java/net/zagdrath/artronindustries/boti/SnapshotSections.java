/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import java.util.function.IntConsumer;

/**
 * Splits a {@link SnapshotBox} into cubes of {@code size} blocks (box-local, starting at the box's lowest corner; the
 * last cube on each axis is cut short by the box), so the client can mesh, rebuild, cull and sort the view in pieces.
 * Sections are numbered x fastest, then z, then y, like the blocks of the box.
 */
public record SnapshotSections(int sizeX, int sizeY, int sizeZ, int size) {
    /** Section size of a snapshot's own blocks. */
    public static final int SIZE = 16;

    public static SnapshotSections of(SnapshotBox box) {
        return of(box, SIZE);
    }

    public static SnapshotSections of(SnapshotBox box, int size) {
        return new SnapshotSections(box.sizeX(), box.sizeY(), box.sizeZ(), size);
    }

    public int countX() {
        return (this.sizeX + this.size - 1) / this.size;
    }

    public int countY() {
        return (this.sizeY + this.size - 1) / this.size;
    }

    public int countZ() {
        return (this.sizeZ + this.size - 1) / this.size;
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
        return this.sectionX(section) * this.size;
    }

    public int minY(int section) {
        return this.sectionY(section) * this.size;
    }

    public int minZ(int section) {
        return this.sectionZ(section) * this.size;
    }

    /** Box-local highest corner of a section, exclusive. */
    public int maxX(int section) {
        return Math.min(this.sizeX, this.minX(section) + this.size);
    }

    public int maxY(int section) {
        return Math.min(this.sizeY, this.minY(section) + this.size);
    }

    public int maxZ(int section) {
        return Math.min(this.sizeZ, this.minZ(section) + this.size);
    }

    /**
     * Every section whose mesh can change when the block at box-local {@code (x, y, z)} does: its own, and the neighbouring
     * ones when it is on their border, since face culling, ambient occlusion, smooth light and fluid heights all look one
     * block around each block.
     */
    public void forEachAffected(int x, int y, int z, IntConsumer out) {
        int x0 = Math.max(0, x - 1) / this.size;
        int x1 = Math.min(this.sizeX - 1, x + 1) / this.size;
        int y0 = Math.max(0, y - 1) / this.size;
        int y1 = Math.min(this.sizeY - 1, y + 1) / this.size;
        int z0 = Math.max(0, z - 1) / this.size;
        int z1 = Math.min(this.sizeZ - 1, z + 1) / this.size;
        for (int sy = y0; sy <= y1; sy++) {
            for (int sz = z0; sz <= z1; sz++) {
                for (int sx = x0; sx <= x1; sx++) {
                    out.accept(this.index(sx, sy, sz));
                }
            }
        }
    }
}
