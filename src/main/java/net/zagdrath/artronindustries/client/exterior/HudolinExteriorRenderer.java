/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.exterior;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.entity.TestExteriorDoorBlockEntity;
import net.zagdrath.artronindustries.client.boti.BotiDoorOverlay;

/**
 * Draws the TARDIS exterior as the Hudolin police box, centred on the lower block and turned to face {@code FACING}. The
 * doors swing in behind the doorway plane, so they are also drawn again inside the doorway ({@link BotiDoorOverlay}).
 */
public class HudolinExteriorRenderer implements BlockEntityRenderer<TestExteriorDoorBlockEntity, HudolinExteriorRenderer.RenderState>,
        BotiDoorOverlay<HudolinExteriorRenderer.RenderState> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "hudolin_exterior"), "main");
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "textures/block/exterior/hudolin.png");

    private final HudolinExteriorModel model;

    public static class RenderState extends BlockEntityRenderState {
        Direction facing = Direction.NORTH;
        float right;
        float left;
    }

    public HudolinExteriorRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new HudolinExteriorModel(context.bakeLayer(LAYER));
    }

    @Override
    public RenderState createRenderState() {
        return new RenderState();
    }

    @Override
    public void extractRenderState(TestExteriorDoorBlockEntity door, RenderState state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(door, state, partialTicks, cameraPosition, breakProgress);
        state.facing = door.getFacing();
        state.right = ease(door.getLeafOpenAmount(false, partialTicks));
        state.left = ease(door.getLeafOpenAmount(true, partialTicks));
    }

    @Override
    public void submit(RenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        HudolinExteriorModel.State modelState = new HudolinExteriorModel.State(state.right, state.left, false);
        poseStack.pushPose();
        orient(poseStack, state.facing);
        collector.submitModel(this.model, modelState, poseStack, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        if (state.breakProgress != null) {
            collector.order(1).submitCrumblingOverlay(this.model, modelState, poseStack, this.model.renderType(TEXTURE),
                    state.lightCoords, OverlayTexture.NO_OVERLAY, -1, state.breakProgress);
        }
        poseStack.popPose();
    }

    @Override
    public void submitBehindDoorway(RenderState state, PoseStack poseStack, SubmitNodeCollector collector) {
        if (state.right <= 0.0F && state.left <= 0.0F) {
            return;
        }
        poseStack.pushPose();
        orient(poseStack, state.facing);
        collector.submitModel(this.model, new HudolinExteriorModel.State(state.right, state.left, true), poseStack, TEXTURE,
                state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    /**
     * From the block's corner to model space: model y points down from 24 px above the ground, and model x is mirrored,
     * as for entity models. The model faces north as built.
     */
    private static void orient(PoseStack poseStack, Direction facing) {
        poseStack.translate(0.5F, 0.0F, 0.5F);
        poseStack.rotateDegrees(Axis.YP, 180.0F - facing.toYRot());
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.5F, 0.0F);
    }

    /** Doors start and stop gently. */
    private static float ease(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /** The box overhangs the block by 6 px on every side and is 52 px tall. */
    @Override
    public AABB getRenderBoundingBox(TestExteriorDoorBlockEntity door) {
        BlockPos pos = door.getBlockPos();
        return new AABB(pos.getX() - 0.5, pos.getY(), pos.getZ() - 0.5, pos.getX() + 1.5, pos.getY() + 3.5, pos.getZ() + 1.5);
    }
}
