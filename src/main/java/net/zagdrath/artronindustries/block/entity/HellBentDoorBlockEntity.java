/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.artronindustries.block.HellBentDoorBlock;
import net.zagdrath.artronindustries.block.InteriorDoorwayBlock;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.DoorSounds;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.interior.InteriorDoorway;

/**
 * The Hell Bent door, in its master cell. Opens and shuts the door's cells with its leaves, and sounds the same whichever
 * TARDIS it is placed in.
 */
public class HellBentDoorBlockEntity extends InteriorDoubleDoorBlockEntity {
    private boolean cellsChecked;

    public HellBentDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ArtronBlockEntities.HELL_BENT_DOOR.get(), pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        // Not in onLoad: no block changes while a chunk loads.
        if (!this.cellsChecked && this.level instanceof ServerLevel) {
            this.cellsChecked = true;
            this.updateCells();
        }
    }

    @Override
    public void setDoorStateFromManager(DoorState state) {
        super.setDoorStateFromManager(state);
        this.updateCells();
    }

    /** Opens or shuts the cells to match the door state. */
    private void updateCells() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        InteriorDoorway doorway = HellBentDoorBlock.DOORWAY;
        for (int index = 0; index < doorway.cellCount(); index++) {
            BlockPos pos = doorway.cell(this.worldPosition, this.getFacing(), index);
            BlockState cell = serverLevel.getBlockState(pos);
            boolean open = leafOpen(this.doorState(), doorway.inLeftLeaf(doorway.i(index)));
            if (cell.getBlock() instanceof HellBentDoorBlock && cell.getValue(HellBentDoorBlock.OPEN) != open) {
                serverLevel.setBlock(pos, cell.setValue(HellBentDoorBlock.OPEN, open), InteriorDoorwayBlock.CELL_UPDATE);
            }
        }
    }

    @Override
    public PortalShape getPortalShape() {
        return HellBentDoorBlock.DOORWAY.shape();
    }

    @Override
    protected DoorSounds ownDoorSounds() {
        return HellBentDoorBlock.SOUNDS;
    }
}
