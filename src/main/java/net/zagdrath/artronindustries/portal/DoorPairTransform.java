/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.portal;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable rigid transform (rotation about Y in 90 degree steps, then translation) mapping exterior-door space onto
 * interior-door space. The two doorway planes are glued together: the exterior anchor maps onto the interior anchor, and
 * "into the exterior doorway" ({@code -exteriorFacing}) maps onto "out of the interior doorway into the room"
 * ({@code +interiorFacing}).
 * <p>
 * {@link #apply} goes exterior -> interior, {@link #invert} goes back.
 */
public final class DoorPairTransform {
    private final int quarterTurns;
    private final double tx;
    private final double ty;
    private final double tz;

    private DoorPairTransform(int quarterTurns, double tx, double ty, double tz) {
        this.quarterTurns = Math.floorMod(quarterTurns, 4);
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
    }

    /**
     * @param exteriorAnchor anchor of the exterior opening (see {@link PortalShape#anchor})
     * @param exteriorFacing facing of the exterior door
     * @param interiorAnchor anchor of the interior opening
     * @param interiorFacing facing of the interior door
     */
    public static DoorPairTransform between(Vec3 exteriorAnchor, Direction exteriorFacing, Vec3 interiorAnchor, Direction interiorFacing) {
        if (exteriorFacing.getAxis().isVertical() || interiorFacing.getAxis().isVertical()) {
            throw new IllegalArgumentException("Door facings must be horizontal");
        }
        int turns = Math.floorMod(interiorFacing.getOpposite().get2DDataValue() - exteriorFacing.get2DDataValue(), 4);
        Vec3 rotated = rotate(exteriorAnchor, turns);
        return new DoorPairTransform(turns, interiorAnchor.x - rotated.x, interiorAnchor.y - rotated.y, interiorAnchor.z - rotated.z);
    }

    /** A pure rotation of {@code quarterTurns} clockwise quarter turns about the origin. */
    public static DoorPairTransform rotation(int quarterTurns) {
        return new DoorPairTransform(quarterTurns, 0.0, 0.0, 0.0);
    }

    /** Clockwise (seen from above) quarter turns applied before translating. */
    public int quarterTurns() {
        return this.quarterTurns;
    }

    public Vec3 translation() {
        return new Vec3(this.tx, this.ty, this.tz);
    }

    public Vec3 apply(Vec3 pos) {
        Vec3 r = rotate(pos, this.quarterTurns);
        return new Vec3(r.x + this.tx, r.y + this.ty, r.z + this.tz);
    }

    public Vec3 applyVelocity(Vec3 velocity) {
        return rotate(velocity, this.quarterTurns);
    }

    public float applyYaw(float yaw) {
        return yaw + 90.0F * this.quarterTurns;
    }

    public Direction applyDirection(Direction direction) {
        Direction d = direction;
        for (int i = 0; i < this.quarterTurns; i++) {
            d = d.getAxis().isHorizontal() ? d.getClockWise() : d;
        }
        return d;
    }

    /** The interior -> exterior transform. */
    public DoorPairTransform invert() {
        int inv = (4 - this.quarterTurns) % 4;
        Vec3 t = rotate(new Vec3(-this.tx, -this.ty, -this.tz), inv);
        return new DoorPairTransform(inv, t.x, t.y, t.z);
    }

    /** Rotates clockwise as seen from above (north -> east -> south -> west). */
    static Vec3 rotate(Vec3 v, int quarterTurns) {
        return switch (Math.floorMod(quarterTurns, 4)) {
            case 0 -> v;
            case 1 -> new Vec3(-v.z, v.y, v.x);
            case 2 -> new Vec3(-v.x, v.y, -v.z);
            default -> new Vec3(v.z, v.y, -v.x);
        };
    }

    @Override
    public String toString() {
        return "DoorPairTransform[turns=" + this.quarterTurns + ", t=(" + this.tx + ", " + this.ty + ", " + this.tz + ")]";
    }
}
