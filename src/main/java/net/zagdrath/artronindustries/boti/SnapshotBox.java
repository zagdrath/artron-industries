/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.zagdrath.artronindustries.portal.PortalShape;

/**
 * World-aligned box of blocks on the far side of a door, in far-side world coordinates. It is defined in door-local terms
 * (width across the doorway, height, depth away from the door) by {@link #inFrontOf} but stored axis-aligned, so block
 * states never need rotating; the client applies the door transform at draw time.
 */
public record SnapshotBox(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
    public static final StreamCodec<ByteBuf, SnapshotBox> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SnapshotBox::origin,
            ByteBufCodecs.VAR_INT, SnapshotBox::sizeX,
            ByteBufCodecs.VAR_INT, SnapshotBox::sizeY,
            ByteBufCodecs.VAR_INT, SnapshotBox::sizeZ,
            SnapshotBox::new);

    /**
     * The box in front of a door (on its {@code facing} side). It starts at the first block layer entirely in front of the
     * doorway plane, so nothing behind the far door can ever be drawn in front of the near doorway. A quarter of the
     * height lies below the bottom of the opening so floors are visible.
     */
    public static SnapshotBox inFrontOf(BlockPos doorPos, Direction facing, PortalShape shape, int width, int height, int depth) {
        int firstLayer = (int) Math.ceil(shape.planeOffset() + 0.5F - 1.0E-4F);
        BlockPos start = doorPos.relative(facing, firstLayer);
        BlockPos end = start.relative(facing, depth - 1);
        Direction right = PortalShape.right(facing);
        int left = -(width / 2);
        BlockPos a = start.relative(right, left);
        BlockPos b = end.relative(right, left + width - 1);
        int bottom = doorPos.getY() + (int) Math.floor(shape.bottomOffset()) - height / 4;
        int minX = Math.min(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ());
        int sizeX = Math.abs(a.getX() - b.getX()) + 1;
        int sizeZ = Math.abs(a.getZ() - b.getZ()) + 1;
        return new SnapshotBox(new BlockPos(minX, bottom, minZ), sizeX, height, sizeZ);
    }

    public int volume() {
        return this.sizeX * this.sizeY * this.sizeZ;
    }

    /** Index of a box-local position: x fastest, then z, then y. */
    public int index(int x, int y, int z) {
        return (y * this.sizeZ + z) * this.sizeX + x;
    }

    public int indexOfWorld(int x, int y, int z) {
        return this.index(x - this.origin.getX(), y - this.origin.getY(), z - this.origin.getZ());
    }

    public boolean containsWorld(int x, int y, int z) {
        int lx = x - this.origin.getX();
        int ly = y - this.origin.getY();
        int lz = z - this.origin.getZ();
        return lx >= 0 && ly >= 0 && lz >= 0 && lx < this.sizeX && ly < this.sizeY && lz < this.sizeZ;
    }

    public int localX(int index) {
        return index % this.sizeX;
    }

    public int localZ(int index) {
        return (index / this.sizeX) % this.sizeZ;
    }

    public int localY(int index) {
        return index / (this.sizeX * this.sizeZ);
    }

    public BlockPos worldPos(int index) {
        return this.origin.offset(this.localX(index), this.localY(index), this.localZ(index));
    }

    public int maxX() {
        return this.origin.getX() + this.sizeX - 1;
    }

    public int maxY() {
        return this.origin.getY() + this.sizeY - 1;
    }

    public int maxZ() {
        return this.origin.getZ() + this.sizeZ - 1;
    }
}
