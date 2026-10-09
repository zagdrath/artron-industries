/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalShape;

/**
 * Decides, every frame, whether the camera is through a doorway, so the far side fills the screen the exact frame the
 * eye crosses the doorway plane: the eye's path since the last frame is tested against the open part of the opening.
 * After that, the far side keeps filling the screen while the camera stays behind the plane until the server's teleport
 * arrives ({@link SeamlessTransition}), or gives up after {@link #HOLD_MS} (the server refused the crossing). The server
 * still decides the crossing itself; this only decides what is drawn meanwhile.
 * <p>
 * Right in front of the plane the doorway quad is cut by the camera's near plane, so the far side also fills the screen
 * there, before the eye has crossed.
 */
final class DoorwayEye {
    /** Distance in front of a doorway plane within which the doorway quad could be cut by the near plane. */
    private static final double NEAR_PLANE = 0.06;
    /** How much bigger than the open part of the opening a crossing still counts (the eye grazing the frame). */
    private static final double MARGIN = 0.05;
    /** How long the far side is held after crossing without the teleport arriving. */
    private static final long HOLD_MS = 1000L;
    /** How far behind the plane the camera may go before the hold is dropped. */
    private static final double MAX_BEHIND = 4.0;

    private static @Nullable ResourceKey<Level> level;
    private static @Nullable Vec3 lastCamera;
    private static @Nullable Vec3 camera;
    private static @Nullable PortalViewKey crossed;
    private static long crossedUntil;

    private DoorwayEye() {}

    /** Called once per frame before any doorway is asked about. */
    static void beginFrame(ResourceKey<Level> frameLevel, Vec3 frameCamera) {
        if (!frameLevel.equals(level)) {
            level = frameLevel;
            lastCamera = null;
            crossed = null;
        } else {
            lastCamera = camera;
        }
        camera = frameCamera;
    }

    /**
     * Whether the far side of this doorway fills the screen this frame. {@code span} is the open part of the opening as
     * drawn; {@code passable} whether anything can walk through it at the moment.
     */
    static boolean fillsScreen(PortalViewKey key, PortalShape shape, BlockPos pos, Direction facing, OpenSpan span, boolean passable) {
        Vec3 now = camera;
        if (now == null || !passable) {
            if (key.equals(crossed)) {
                crossed = null;
            }
            return false;
        }
        double d1 = shape.signedDistance(pos, facing, now);
        Vec3 before = lastCamera;
        if (before != null && d1 <= 0.0) {
            double d0 = shape.signedDistance(pos, facing, before);
            if (d0 > 0.0 && shape.containsProjected(pos, facing, before.lerp(now, d0 / (d0 - d1)), -MARGIN, span)) {
                crossed = key;
                crossedUntil = Util.getMillis() + HOLD_MS;
            }
        }
        if (key.equals(crossed)) {
            if (d1 <= 0.0 && d1 > -MAX_BEHIND && Util.getMillis() <= crossedUntil) {
                return true;
            }
            crossed = null;
        }
        return d1 > 0.0 && d1 < NEAR_PLANE && shape.containsProjected(pos, facing, now, -MARGIN, span);
    }
}
