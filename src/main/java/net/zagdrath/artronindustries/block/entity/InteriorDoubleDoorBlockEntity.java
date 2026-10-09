/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * An interior door of two leaves that swing out into the room, clear of the doorway plane: a template interior's
 * {@link InteriorDoorwayBlockEntity} or a placed {@link HellBentDoorBlockEntity}.
 * <p>
 * Walking through, the right-hand half of the exterior doorway comes out of the left-hand half of this one (as seen from
 * the room), so this door's left leaf is the one that opens first, with the exterior's right leaf.
 */
public abstract class InteriorDoubleDoorBlockEntity extends PortalDoorBlockEntity {
    protected InteriorDoubleDoorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Whether this door's {@code left} or right leaf is open in {@code state}: the left one with the exterior's right. */
    public static boolean leafOpen(DoorState state, boolean left) {
        return left ? state.rightOpen() : state.leftOpen();
    }

    /** 0 = shut, 1 = open, for this door's {@code left} or right leaf. */
    public float leafOpenAmount(boolean left, float partialTick) {
        return this.getLeafOpenAmount(!left, partialTick);
    }

    @Override
    public PortalSide getPortalSide() {
        return PortalSide.INTERIOR;
    }

    @Override
    public float getDoorOpenAmount(float partialTick) {
        return Math.max(this.leafOpenAmount(true, partialTick), this.leafOpenAmount(false, partialTick));
    }

    /** The leaves swing out into the room, so each half of the doorway shows the far side as soon as its leaf moves. */
    @Override
    public OpenSpan getOpenSpan(float partialTick) {
        return OpenSpan.ofHalves(this.leafOpenAmount(true, partialTick) > 0.001F, this.leafOpenAmount(false, partialTick) > 0.001F);
    }

    @Override
    public OpenSpan getPassableSpan() {
        return OpenSpan.ofHalves(this.leafOpenAmount(true, 1.0F) >= 0.5F, this.leafOpenAmount(false, 1.0F) >= 0.5F);
    }

    @Override
    protected void onDoorRemoved(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
        manager.unlinkInteriorDoor(level.getServer(), record);
    }
}
