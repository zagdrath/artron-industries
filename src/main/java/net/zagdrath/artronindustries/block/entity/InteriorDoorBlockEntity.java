/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/** PLACEHOLDER interior door, generated as part of the starter interior. */
public class InteriorDoorBlockEntity extends PortalDoorBlockEntity {
    public InteriorDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ArtronBlockEntities.INTERIOR_DOOR.get(), pos, state);
    }

    @Override
    public PortalSide getPortalSide() {
        return PortalSide.INTERIOR;
    }

    @Override
    protected void onDoorRemoved(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
        manager.unlinkInteriorDoor(level.getServer(), record);
    }
}
