/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.portal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The single definition of a doorway opening. Rendering, culling, crossing detection and teleporting all derive their
 * geometry from this record; doorway dimensions must never be hard-coded anywhere else.
 * <p>
 * Door-local frame for a door block at {@code pos} facing {@code facing}:
 * <ul>
 *     <li>forward = {@code facing} (out of the doorway, towards the side a viewer stands on)</li>
 *     <li>up = +Y</li>
 *     <li>right = {@code facing.getCounterClockWise()} (the viewer's right while looking into the doorway)</li>
 * </ul>
 * The doorway plane passes through the block centre offset by {@link #planeOffset} along forward. The opening is
 * centred laterally on the block, starts {@link #bottomOffset} blocks above the bottom of the block and spans
 * {@link #width} x {@link #height}.
 *
 * @param width        opening width in blocks
 * @param height       opening height in blocks
 * @param bottomOffset y offset of the bottom edge of the opening from the bottom of the door block
 * @param planeOffset  distance of the doorway plane from the block centre along {@code facing} (0.5 = front face)
 */
public record PortalShape(float width, float height, float bottomOffset, float planeOffset) {
    /** Default for the interior door: 1 wide, 2 tall, plane on the block's front face. */
    public static final PortalShape DEFAULT_DOOR = new PortalShape(1.0F, 2.0F, 0.0F, 0.5F);

    public static final Codec<PortalShape> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("width").forGetter(PortalShape::width),
            Codec.FLOAT.fieldOf("height").forGetter(PortalShape::height),
            Codec.FLOAT.fieldOf("bottom_offset").forGetter(PortalShape::bottomOffset),
            Codec.FLOAT.fieldOf("plane_offset").forGetter(PortalShape::planeOffset)
    ).apply(i, PortalShape::new));

    public static final StreamCodec<ByteBuf, PortalShape> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.FLOAT, PortalShape::width,
            ByteBufCodecs.FLOAT, PortalShape::height,
            ByteBufCodecs.FLOAT, PortalShape::bottomOffset,
            ByteBufCodecs.FLOAT, PortalShape::planeOffset,
            PortalShape::new);

    public PortalShape {
        if (width <= 0.0F || height <= 0.0F) {
            throw new IllegalArgumentException("Portal opening must have a positive size");
        }
    }

    public static Direction right(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** Bottom-centre of the opening on the doorway plane, in world space. Paired doors map these points onto each other. */
    public Vec3 anchor(BlockPos pos, Direction facing) {
        return new Vec3(
                pos.getX() + 0.5 + facing.getStepX() * this.planeOffset,
                pos.getY() + this.bottomOffset,
                pos.getZ() + 0.5 + facing.getStepZ() * this.planeOffset);
    }

    /** Centre of the opening on the doorway plane, in world space. */
    public Vec3 center(BlockPos pos, Direction facing) {
        return this.anchor(pos, facing).add(0.0, this.height * 0.5, 0.0);
    }

    /** Signed distance of {@code point} in front of the doorway plane (positive = viewer side). */
    public double signedDistance(BlockPos pos, Direction facing, Vec3 point) {
        Vec3 a = this.anchor(pos, facing);
        return (point.x - a.x) * facing.getStepX() + (point.z - a.z) * facing.getStepZ();
    }

    /** Lateral offset of {@code point} from the opening's centre line along {@link #right(Direction)}. */
    public double lateral(BlockPos pos, Direction facing, Vec3 point) {
        Vec3 a = this.anchor(pos, facing);
        Direction r = right(facing);
        return (point.x - a.x) * r.getStepX() + (point.z - a.z) * r.getStepZ();
    }

    /**
     * Whether {@code point} projects onto the opening, shrunk by {@code margin} on every side (a negative margin grows it).
     */
    public boolean containsProjected(BlockPos pos, Direction facing, Vec3 point, double margin) {
        return this.containsProjected(pos, facing, point, margin, OpenSpan.FULL);
    }

    /** Whether {@code point} projects onto the {@code span} part of the opening, shrunk by {@code margin} on every side. */
    public boolean containsProjected(BlockPos pos, Direction facing, Vec3 point, double margin, OpenSpan span) {
        double lat = this.lateral(pos, facing, point);
        double up = point.y - (pos.getY() + this.bottomOffset);
        return lat >= this.lateralAt(span.from()) + margin && lat <= this.lateralAt(span.to()) - margin
                && up >= margin && up <= this.height - margin;
    }

    /** Lateral offset (see {@link #lateral}) of the point {@code fraction} of the width from the viewer's left edge. */
    public double lateralAt(float fraction) {
        return this.width * (fraction - 0.5);
    }

    /**
     * The four corners of the {@code span} part of the opening, counter-clockwise as seen from the viewer side (bottom-left,
     * bottom-right, top-right, top-left).
     */
    public Vec3[] corners(BlockPos pos, Direction facing, OpenSpan span) {
        Vec3 a = this.anchor(pos, facing);
        Direction r = right(facing);
        double left = this.lateralAt(span.from());
        double right = this.lateralAt(span.to());
        Vec3 l = new Vec3(r.getStepX() * left, 0.0, r.getStepZ() * left);
        Vec3 rr = new Vec3(r.getStepX() * right, 0.0, r.getStepZ() * right);
        Vec3 up = new Vec3(0.0, this.height, 0.0);
        Vec3 bl = a.add(l);
        Vec3 br = a.add(rr);
        return new Vec3[]{bl, br, br.add(up), bl.add(up)};
    }

    /** World-space bounds of the full opening, inflated by {@code inflate}. Used for culling and crossing checks. */
    public AABB bounds(BlockPos pos, Direction facing, double inflate) {
        Vec3[] c = this.corners(pos, facing, OpenSpan.FULL);
        return new AABB(c[0], c[2]).inflate(inflate);
    }
}
