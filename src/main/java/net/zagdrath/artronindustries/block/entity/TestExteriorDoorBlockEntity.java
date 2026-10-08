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

/** PLACEHOLDER exterior door. The real TARDIS exterior block entity replaces this by implementing PortalEndpoint itself. */
public class TestExteriorDoorBlockEntity extends PortalDoorBlockEntity {
    public TestExteriorDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ArtronBlockEntities.TEST_EXTERIOR_DOOR.get(), pos, state);
    }

    @Override
    public PortalSide getPortalSide() {
        return PortalSide.EXTERIOR;
    }

    @Override
    protected void onDoorRemoved(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
        manager.unlinkExterior(level.getServer(), record);
    }
}
