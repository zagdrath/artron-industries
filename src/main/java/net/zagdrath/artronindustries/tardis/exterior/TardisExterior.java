/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.exterior;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.tardis.DoorState;

/**
 * One look the TARDIS exterior can take: its doorway and the outline and collision of the box. Every exterior stands on
 * the same three blocks (the two halves of {@code TardisBlock} and a {@code TardisTopBlock} above), overhanging them as
 * it needs. Its model is drawn on the client by the renderer registered for its id.
 */
public abstract class TardisExterior {
    private final Identifier id;

    protected TardisExterior(Identifier id) {
        this.id = id;
    }

    public final Identifier id() {
        return this.id;
    }

    /** Where the doors are, for BOTI and walking through. */
    public abstract PortalShape doorway();

    /**
     * The outline or collision of one of the three blocks ({@code part} 0 lower, 1 upper, 2 top) with the doors in
     * {@code doors}, for an exterior facing {@code facing}, in that block's coordinates.
     */
    public abstract VoxelShape shape(boolean collision, int part, DoorState doors, Direction facing);

    @Override
    public String toString() {
        return this.id.toString();
    }
}
