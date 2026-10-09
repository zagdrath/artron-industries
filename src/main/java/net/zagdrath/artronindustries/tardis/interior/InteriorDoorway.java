/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.zagdrath.artronindustries.portal.PortalShape;

/**
 * A double door built from an interior's own blocks: a box {@code width} wide, {@code height} tall and {@code depth} deep,
 * whose blocks swing open as two leaves. The back layer is the leaves themselves, drawn squashed to {@code thickness}
 * blocks from its front face (1 = as built, 0.5 = a vertical slab); layers in front of it (panelling, handles) are drawn
 * as they are. The left part ({@code width / 2} blocks, as seen from the room) is one leaf, hinged on its back left
 * corner; the rest is the other, hinged on its back right corner. Both swing out into the room, clear of the opening. The
 * doorway plane lies just behind the leaves, so they never cross it.
 * <p>
 * Door space, for a doorway facing {@code facing} (out of the doorway, into the room): {@code i} counts across from the
 * left, {@code j} up and {@code d} back from the front layer. Cell (0, 0, 0), front bottom left, is the master, which
 * carries the block entity and is the door's position.
 */
public record InteriorDoorway(int width, int height, int depth, float thickness) {
    /** How far behind the back of the leaves the doorway plane is, so the leaves' back faces never touch it. */
    static final float PLANE_GAP = 1.0F / 32.0F;

    public static final Codec<InteriorDoorway> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("width").forGetter(InteriorDoorway::width),
            Codec.INT.fieldOf("height").forGetter(InteriorDoorway::height),
            Codec.INT.fieldOf("depth").forGetter(InteriorDoorway::depth),
            Codec.FLOAT.optionalFieldOf("thickness", 1.0F).forGetter(InteriorDoorway::thickness)
    ).apply(i, InteriorDoorway::new));

    public InteriorDoorway {
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("Doorway must be at least one block in every direction");
        }
        if (thickness <= 0.0F || thickness > 1.0F) {
            throw new IllegalArgumentException("Leaf thickness must be more than 0 and at most 1 block");
        }
    }

    /** Distance of the front face of the leaves (the back layer) behind the front of the box. */
    public float leafFront() {
        return this.depth - 1;
    }

    /** Distance of the doorway plane, and the hinges, behind the front of the box. */
    public float planeDepth() {
        return this.leafFront() + this.thickness + PLANE_GAP;
    }

    /** Whether layer {@code d} is the leaves themselves, rather than something in front of them. */
    public boolean isLeafLayer(int d) {
        return d == this.depth - 1;
    }

    /** A doorway filling the box between two opposite corners, facing {@code facing}. */
    public static Placed fromBox(BlockPos a, BlockPos b, Direction facing, float thickness) {
        Direction right = PortalShape.right(facing);
        BlockPos min = BlockPos.min(a, b);
        BlockPos max = BlockPos.max(a, b);
        int spanX = max.getX() - min.getX() + 1;
        int spanZ = max.getZ() - min.getZ() + 1;
        boolean acrossX = right.getAxis() == Direction.Axis.X;
        int width = acrossX ? spanX : spanZ;
        int depth = acrossX ? spanZ : spanX;
        // Front bottom left: least far along right, furthest along facing.
        boolean leastX = acrossX ? right.getStepX() > 0 : facing.getStepX() < 0;
        boolean leastZ = acrossX ? facing.getStepZ() < 0 : right.getStepZ() > 0;
        int x = leastX ? min.getX() : max.getX();
        int z = leastZ ? min.getZ() : max.getZ();
        return new Placed(new BlockPos(x, min.getY(), z), new InteriorDoorway(width, max.getY() - min.getY() + 1, depth, thickness));
    }

    /** A doorway together with its master cell's position. */
    public record Placed(BlockPos master, InteriorDoorway doorway) {}

    /** The opening, relative to the master cell. */
    public PortalShape shape() {
        return new PortalShape(this.width, this.height, 0.0F, 0.5F - this.planeDepth(), (this.width - 1) / 2.0F);
    }

    public int cellCount() {
        return this.width * this.height * this.depth;
    }

    /** Index of cell (i, j, d) in a list of all cells. */
    public int index(int i, int j, int d) {
        return (d * this.height + j) * this.width + i;
    }

    public int i(int index) {
        return index % this.width;
    }

    public int j(int index) {
        return index / this.width % this.height;
    }

    public int d(int index) {
        return index / (this.width * this.height);
    }

    /** World position of cell {@code index} for a doorway whose master cell is at {@code master}. */
    public BlockPos cell(BlockPos master, Direction facing, int index) {
        return master.relative(PortalShape.right(facing), this.i(index)).above(this.j(index)).relative(facing, -this.d(index));
    }

    /** Whether column {@code i} belongs to the left leaf. */
    public boolean inLeftLeaf(int i) {
        return i < this.width / 2;
    }

    /** Distance of the hinge of the left or right leaf from the master cell's centre, along right. */
    public float hinge(boolean left) {
        return left ? -0.5F : this.width - 0.5F;
    }

    /** Whether {@code pos} is one of this doorway's cells. */
    public boolean contains(BlockPos master, Direction facing, BlockPos pos) {
        Direction right = PortalShape.right(facing);
        BlockPos rel = pos.subtract(master);
        int i = rel.getX() * right.getStepX() + rel.getZ() * right.getStepZ();
        int d = -(rel.getX() * facing.getStepX() + rel.getZ() * facing.getStepZ());
        return i >= 0 && i < this.width && rel.getY() >= 0 && rel.getY() < this.height && d >= 0 && d < this.depth;
    }
}
