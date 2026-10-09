/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * PLACEHOLDER interior door. Generated in the starter interior; placing one by hand inside a TARDIS cell moves that
 * TARDIS's interior door to the new position.
 */
public class InteriorDoorBlock extends PortalDoorBlock {
    public InteriorDoorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected BlockEntityType<? extends PortalDoorBlockEntity> blockEntityType() {
        return ArtronBlockEntities.INTERIOR_DOOR.get();
    }

    @Override
    protected void onPlacedServer(ServerLevel level, BlockPos pos, BlockState state, PortalDoorBlockEntity door, @Nullable LivingEntity placer) {
        linkPlacedDoor(level, pos, state.getValue(FACING), this.portalShape(state), door, placer);
    }

    /**
     * Makes {@code door}, just placed at {@code pos}, the interior door of the TARDIS whose cell it is in, unlinking that
     * TARDIS's previous interior door; outside any cell, tells the placer it does nothing.
     */
    public static void linkPlacedDoor(ServerLevel level, BlockPos pos, Direction facing, PortalShape shape, PortalDoorBlockEntity door,
                                      @Nullable LivingEntity placer) {
        TardisInteriorManager manager = TardisInteriorManager.get(level.getServer());
        TardisRecord record = TardisInteriorManager.isInterior(level) ? manager.byInteriorPos(pos) : null;
        if (record == null) {
            if (placer instanceof Player player) {
                player.sendOverlayMessage(Component.translatable("message.artronindustries.door.not_in_interior"));
            }
            return;
        }
        // The previous interior door (if any) loses its link when it next loads or is toggled.
        PortalDoorBlockEntity old = manager.loadedDoor(level.getServer(), record, door.getPortalSide());
        manager.setDoorOpen(level.getServer(), record, false);
        manager.linkInteriorDoor(level.getServer(), record, pos, facing, shape);
        if (old != null && old != door) {
            old.link(null, DoorState.CLOSED);
        }
        door.link(record.uuid(), DoorState.CLOSED);
    }
}
