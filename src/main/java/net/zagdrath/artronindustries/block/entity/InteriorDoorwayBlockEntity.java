/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block.entity;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.zagdrath.artronindustries.block.InteriorDoorwayBlock;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;
import net.zagdrath.artronindustries.tardis.interior.InteriorDoorway;

/**
 * The interior door of a template interior, in the master cell of its {@link InteriorDoorway}. Keeps the blocks its cells
 * replaced (drawn as the leaves by {@code InteriorDoorwayRenderer}) and opens and shuts the cells with the doors.
 * <p>
 * Walking through, the right-hand half of the exterior doorway comes out of the left-hand half of this one (as seen from
 * the room), so this door's left leaf is the one that opens first, with the exterior's right leaf.
 */
public class InteriorDoorwayBlockEntity extends PortalDoorBlockEntity {
    private @Nullable InteriorDoorway doorway;
    private List<BlockState> leaves = List.of();
    private boolean cellsChecked;

    public InteriorDoorwayBlockEntity(BlockPos pos, BlockState state) {
        super(ArtronBlockEntities.INTERIOR_DOORWAY.get(), pos, state);
    }

    public @Nullable InteriorDoorway doorway() {
        return this.doorway;
    }

    /** The blocks the cells replaced, by {@link InteriorDoorway#index}. */
    public List<BlockState> leaves() {
        return this.leaves;
    }

    /** Sets the doorway's shape and the blocks it is made of. Server only. */
    public void install(InteriorDoorway doorway, List<BlockState> leaves) {
        if (leaves.size() != doorway.cellCount()) {
            throw new IllegalArgumentException("Expected " + doorway.cellCount() + " leaf blocks, got " + leaves.size());
        }
        this.doorway = doorway;
        this.leaves = List.copyOf(leaves);
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    public boolean contains(BlockPos pos) {
        return this.doorway != null && this.doorway.contains(this.worldPosition, this.getFacing(), pos);
    }

    /** Whether the leaf over column {@code i} is open: the left one with the exterior's right leaf. */
    public static boolean leafOpen(InteriorDoorway doorway, DoorState state, int i) {
        return doorway.inLeftLeaf(i) ? state.rightOpen() : state.leftOpen();
    }

    /** 0 = shut, 1 = open, for this door's {@code left} or right leaf. */
    public float leafOpenAmount(boolean left, float partialTick) {
        return this.getLeafOpenAmount(!left, partialTick);
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
        if (this.doorway == null || !(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int index = 0; index < this.doorway.cellCount(); index++) {
            BlockPos pos = this.doorway.cell(this.worldPosition, this.getFacing(), index);
            BlockState cell = serverLevel.getBlockState(pos);
            boolean open = leafOpen(this.doorway, this.doorState(), this.doorway.i(index));
            if (cell.getBlock() instanceof InteriorDoorwayBlock && cell.getValue(InteriorDoorwayBlock.OPEN) != open) {
                serverLevel.setBlock(pos, cell.setValue(InteriorDoorwayBlock.OPEN, open), InteriorDoorwayBlock.CELL_UPDATE);
            }
        }
    }

    @Override
    public PortalSide getPortalSide() {
        return PortalSide.INTERIOR;
    }

    @Override
    public PortalShape getPortalShape() {
        return this.doorway == null ? PortalShape.DEFAULT_DOOR : this.doorway.shape();
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

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.doorway = input.read("doorway", InteriorDoorway.CODEC).orElse(null);
        List<BlockState> leaves = new ArrayList<>();
        input.read("leaves", BlockState.CODEC.listOf()).ifPresent(leaves::addAll);
        this.leaves = this.doorway != null && leaves.size() == this.doorway.cellCount() ? List.copyOf(leaves) : List.of();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("doorway", InteriorDoorway.CODEC, this.doorway);
        output.store("leaves", BlockState.CODEC.listOf(), this.leaves);
    }
}
