/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.artronindustries.block.TestExteriorDoorBlock;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/** The TARDIS exterior's block entity: the outside end of the doorway, with double doors. */
public class TestExteriorDoorBlockEntity extends PortalDoorBlockEntity {
    private boolean topChecked;

    public TestExteriorDoorBlockEntity(BlockPos pos, BlockState state) {
        super(ArtronBlockEntities.TEST_EXTERIOR_DOOR.get(), pos, state);
    }

    @Override
    public void tick() {
        super.tick();
        // Boxes placed before the exterior had a top block get one (not in onLoad: no block changes while a chunk loads).
        if (!this.topChecked && this.level instanceof ServerLevel serverLevel) {
            this.topChecked = true;
            TestExteriorDoorBlock.placeTop(serverLevel, this.worldPosition, this.getFacing());
        }
    }

    @Override
    public PortalSide getPortalSide() {
        return PortalSide.EXTERIOR;
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

    /** TARDISes made before the exterior had its own doorway still have the placeholder's in their record. */
    @Override
    protected void reconcile(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
        PortalShape shape = this.getPortalShape();
        if (!shape.equals(record.exteriorShape()) || record.exteriorFacing() != this.getFacing()) {
            manager.linkExterior(level.getServer(), record, level, this.worldPosition, this.getFacing(), shape);
        }
    }

    @Override
    protected void onDoorRemoved(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
        manager.unlinkExterior(level.getServer(), record);
    }
}
