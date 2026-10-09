/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.zagdrath.artronindustries.block.TardisBlock;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;
import net.zagdrath.artronindustries.tardis.exterior.TardisExterior;
import net.zagdrath.artronindustries.tardis.exterior.TardisExteriors;
import net.zagdrath.artronindustries.tardis.interior.TardisInterior;
import net.zagdrath.artronindustries.tardis.interior.TardisInteriors;

/**
 * The TARDIS block entity: the outside end of the doorway, with double doors. Carries the TARDIS's two attributes, its
 * {@code exterior} (synced to clients, which draw it) and its {@code interior}. Both are read from the item's
 * {@code block_entity_data} when placed; once linked, the {@link TardisRecord} is kept in step with them.
 */
public class TardisBlockEntity extends PortalDoorBlockEntity {
    private TardisExterior exterior = TardisExteriors.DEFAULT;
    private TardisInterior interior = TardisInteriors.DEFAULT;
    private boolean topChecked;

    public TardisBlockEntity(BlockPos pos, BlockState state) {
        super(ArtronBlockEntities.TARDIS.get(), pos, state);
    }

    public TardisExterior exterior() {
        return this.exterior;
    }

    public TardisInterior interior() {
        return this.interior;
    }

    /** Sets both attributes, before the TARDIS is created. Server only. */
    public void setAttributes(TardisExterior exterior, TardisInterior interior) {
        this.exterior = exterior;
        this.interior = interior;
        this.setChanged();
        if (this.level != null) {
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void tick() {
        super.tick();
        // Boxes placed before the exterior had a top block get one (not in onLoad: no block changes while a chunk loads).
        if (!this.topChecked && this.level instanceof ServerLevel serverLevel) {
            this.topChecked = true;
            TardisBlock.placeTop(serverLevel, this.worldPosition, this.getFacing());
        }
    }

    @Override
    public PortalSide getPortalSide() {
        return PortalSide.EXTERIOR;
    }

    @Override
    public PortalShape getPortalShape() {
        return this.exterior.doorway();
    }

    /**
     * The leaves swing in behind the doorway plane and are drawn again over the far side, so each leaf's half of the
     * doorway shows the far side as soon as that leaf starts to move.
     */
    @Override
    public OpenSpan getOpenSpan(float partialTick) {
        return OpenSpan.ofHalves(this.getLeafOpenAmount(true, partialTick) > 0.001F, this.getLeafOpenAmount(false, partialTick) > 0.001F);
    }

    @Override
    public OpenSpan getPassableSpan() {
        return OpenSpan.ofHalves(this.getLeafOpenAmount(true, 1.0F) >= 0.5F, this.getLeafOpenAmount(false, 1.0F) >= 0.5F);
    }

    /**
     * The exterior is this block's: the record follows it (and records from before exteriors existed pick it up). The
     * interior is the record's: it may already be built.
     */
    @Override
    protected void reconcile(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
        if (record.exterior() != this.exterior || !this.exterior.doorway().equals(record.exteriorShape()) || record.exteriorFacing() != this.getFacing()) {
            manager.linkExterior(level.getServer(), record, level, this.worldPosition, this.getFacing(), this.exterior);
        }
        if (record.interior() != this.interior) {
            this.interior = record.interior();
            this.setChanged();
        }
    }

    @Override
    protected void onDoorRemoved(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
        manager.unlinkExterior(level.getServer(), record);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.exterior = input.read("exterior", TardisExteriors.CODEC).orElse(TardisExteriors.DEFAULT);
        this.interior = input.read("interior", TardisInteriors.CODEC).orElse(TardisInteriors.DEFAULT);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("exterior", TardisExteriors.CODEC, this.exterior);
        output.store("interior", TardisInteriors.CODEC, this.interior);
    }
}
