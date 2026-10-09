/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import java.util.List;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.block.entity.HellBentDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalShape;

/**
 * Draws the Hell Bent door ({@link HellBentDoorModel}): its frame where it stands and its leaves turned about their
 * hinges as they open, out into the room the way the Victorian Parlour's do ({@link InteriorDoorwayRenderer#leafTurn}).
 * Each leaf is hinged at its outer edge on the front face, so that the leaf, swinging out, clears the frame beside it and
 * never crosses the doorway plane behind it; being in front of that plane, it needs no {@code BotiDoorOverlay}.
 */
public class HellBentDoorRenderer implements BlockEntityRenderer<HellBentDoorBlockEntity, HellBentDoorRenderer.RenderState> {
    /** Hinges, from the master cell's centre: on the front face, at the door's left and right sides. */
    private static final float LEFT_HINGE = -0.5F;
    private static final float RIGHT_HINGE = 1.5F;
    private static final float HINGE_DEPTH = 0.5F;

    public static class RenderState extends BlockEntityRenderState {
        final Matrix4f frame = new Matrix4f();
        final Matrix4f left = new Matrix4f();
        final Matrix4f right = new Matrix4f();
        CardinalLighting lighting = CardinalLighting.DEFAULT;
    }

    public HellBentDoorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public RenderState createRenderState() {
        return new RenderState();
    }

    @Override
    public void extractRenderState(HellBentDoorBlockEntity door, RenderState state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(door, state, partialTicks, cameraPosition, breakProgress);
        Matrix4f doorToBlock = HellBentDoorModel.doorToBlock(door.getFacing());
        state.frame.set(doorToBlock);
        state.left.set(InteriorDoorwayRenderer.leafTurn(door, true, LEFT_HINGE, HINGE_DEPTH, partialTicks)).mul(doorToBlock);
        state.right.set(InteriorDoorwayRenderer.leafTurn(door, false, RIGHT_HINGE, HINGE_DEPTH, partialTicks)).mul(doorToBlock);
        state.lighting = door.getLevel() instanceof ClientLevel level ? level.cardinalLighting() : CardinalLighting.DEFAULT;
    }

    @Override
    public void submit(RenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        HellBentDoorModel.Parts parts = HellBentDoorModel.INSTANCE.parts();
        if (parts == null) {
            return;
        }
        // Drawn like a moving block, from the block atlas and shaded like one, so that shut it looks like the wall round it.
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, HellBentDoorModel.SPRITE));
        submitPart(parts.frame(), state.frame, state, sprite, poseStack, collector);
        submitPart(parts.left(), state.left, state, sprite, poseStack, collector);
        submitPart(parts.right(), state.right, state, sprite, poseStack, collector);
    }

    private static void submitPart(List<ExtrudedPixels.Quad> quads, Matrix4f toBlock, RenderState state, TextureAtlasSprite sprite, PoseStack poseStack,
                                   SubmitNodeCollector collector) {
        Matrix4f pose = new Matrix4f(toBlock);
        int light = state.lightCoords;
        CardinalLighting lighting = state.lighting;
        poseStack.pushPose();
        poseStack.mulPose(pose);
        collector.submitCustomGeometry(poseStack, RenderTypes.solidMovingBlock(),
                (p, buffer) -> HellBentDoorModel.emitBlock(quads, pose, p, buffer, light, sprite, lighting));
        poseStack.popPose();
    }

    /** The door and everything its leaves sweep through, a block out into the room. */
    @Override
    public AABB getRenderBoundingBox(HellBentDoorBlockEntity door) {
        Direction facing = door.getFacing();
        AABB cells = new AABB(door.getBlockPos()).expandTowards(Vec3.atLowerCornerOf(PortalShape.right(facing).getUnitVec3i()))
                .expandTowards(0.0, 2.0, 0.0);
        return cells.expandTowards(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(1.5)).inflate(0.25, 0.0, 0.25);
    }
}
