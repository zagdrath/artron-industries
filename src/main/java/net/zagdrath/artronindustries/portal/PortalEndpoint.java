/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.portal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * A block entity that acts as one end of a TARDIS doorway. BOTI rendering, crossing detection and teleporting are driven
 * entirely through this interface, so any block entity (the placeholder doors today, the real exterior later) gets them
 * by implementing it and registering itself with {@link PortalEndpoints} while loaded.
 */
public interface PortalEndpoint {
    PortalShape getPortalShape();

    /** Direction pointing out of the doorway, towards the side a viewer stands on. */
    Direction getFacing();

    /** 0 = closed, 1 = fully open. Interpolated with {@code partialTick} on the client. */
    float getDoorOpenAmount(float partialTick);

    /** The TARDIS this door belongs to, or {@code null} if it is not linked. */
    @Nullable UUID getTardisId();

    PortalSide getPortalSide();

    /** Block position the {@link PortalShape} is measured from. */
    BlockPos getPortalPos();

    @Nullable Level getPortalLevel();

    /** {@code true} once removed or unloaded; trackers drop such endpoints. */
    boolean isPortalRemoved();
}
