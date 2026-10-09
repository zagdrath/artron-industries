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
 * entirely through this interface, so any block entity (such as the TARDIS and its interior door) gets them
 * by implementing it and registering itself with {@link PortalEndpoints} while loaded.
 */
public interface PortalEndpoint {
    PortalShape getPortalShape();

    /** Direction pointing out of the doorway, towards the side a viewer stands on. */
    Direction getFacing();

    /** 0 = closed, 1 = fully open. Interpolated with {@code partialTick} on the client. */
    float getDoorOpenAmount(float partialTick);

    /**
     * The part of the opening the far side is drawn in. Defaults to the opening uncovered from the left as the door
     * opens; double doors open one half at a time.
     */
    default OpenSpan getOpenSpan(float partialTick) {
        return new OpenSpan(0.0F, this.getDoorOpenAmount(partialTick));
    }

    /** The part of the opening entities can walk through. Defaults to all of it once the door is half open. */
    default OpenSpan getPassableSpan() {
        return this.getDoorOpenAmount(1.0F) >= 0.5F ? OpenSpan.FULL : OpenSpan.NONE;
    }

    /** The TARDIS this door belongs to, or {@code null} if it is not linked. */
    @Nullable UUID getTardisId();

    PortalSide getPortalSide();

    /** Block position the {@link PortalShape} is measured from. */
    BlockPos getPortalPos();

    @Nullable Level getPortalLevel();

    /** {@code true} once removed or unloaded; trackers drop such endpoints. */
    boolean isPortalRemoved();
}
