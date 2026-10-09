/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.block.entity.InteriorDoorwayBlockEntity;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.tardis.interior.InteriorDoorway;

/**
 * Draws an {@link InteriorDoorway}'s leaves from the blocks they were built of, each leaf turned about its hinge as it
 * opens. The blocks are lit with the light at the door, so they light the same through BOTI as they do in the world.
 */
public class InteriorDoorwayRenderer implements BlockEntityRenderer<InteriorDoorwayBlockEntity, InteriorDoorwayRenderer.RenderState> {
    /** How far a fully open leaf has swung out into the room. */
    private static final float OPEN_DEGREES = 90.0F;

    public static class RenderState extends BlockEntityRenderState {
        final List<Piece> pieces = new ArrayList<>();
    }

    /** One block of a leaf, with its pose relative to the master cell. */
    record Piece(Matrix4f pose, LitBlock block) {}

    /** A lone block lit uniformly with the door's light, whatever level it was taken from. */
    static final class LitBlock extends MovingBlockRenderState {
        int light;

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return layer == LightLayer.SKY ? LightCoordsUtil.sky(this.light) : LightCoordsUtil.block(this.light);
        }
    }

    public InteriorDoorwayRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public RenderState createRenderState() {
        return new RenderState();
    }

    @Override
    public void extractRenderState(InteriorDoorwayBlockEntity door, RenderState state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(door, state, partialTicks, cameraPosition, breakProgress);
        state.pieces.clear();
        InteriorDoorway doorway = door.doorway();
        List<BlockState> leaves = door.leaves();
        if (doorway == null || leaves.isEmpty()) {
            return;
        }
        Direction facing = door.getFacing();
        Direction right = PortalShape.right(facing);
        // Turning by +90 degrees about Y takes (x, z) to (z, -x); the left leaf turns whichever way takes right onto facing.
        float sign = right.getStepZ() == facing.getStepX() && -right.getStepX() == facing.getStepZ() ? 1.0F : -1.0F;
        float plane = 0.5F - doorway.planeDepth();
        float leftAngle = sign * OPEN_DEGREES * ease(door.leafOpenAmount(true, partialTicks));
        float rightAngle = -sign * OPEN_DEGREES * ease(door.leafOpenAmount(false, partialTicks));
        Matrix4f leftHinge = hingeTurn(doorway.hinge(true), plane, right, facing, leftAngle);
        Matrix4f rightHinge = hingeTurn(doorway.hinge(false), plane, right, facing, rightAngle);
        ClientLevel level = door.getLevel() instanceof ClientLevel clientLevel ? clientLevel : null;
        BlockPos master = door.getBlockPos();
        for (int index = 0; index < leaves.size(); index++) {
            BlockState block = leaves.get(index);
            if (block.isAir()) {
                continue;
            }
            BlockPos pos = doorway.cell(master, facing, index);
            Matrix4f pose = new Matrix4f(doorway.inLeftLeaf(doorway.i(index)) ? leftHinge : rightHinge)
                    .translate(pos.getX() - master.getX(), pos.getY() - master.getY(), pos.getZ() - master.getZ());
            LitBlock lit = new LitBlock();
            lit.blockPos = pos;
            lit.randomSeedPos = pos;
            lit.blockState = block;
            if (level != null) {
                lit.biome = level.getBiome(pos);
                lit.cardinalLighting = level.cardinalLighting();
            }
            state.pieces.add(new Piece(pose, lit));
        }
    }

    /** Turns by {@code degrees} about the vertical line through the hinge, given relative to the master cell's centre. */
    private static Matrix4f hingeTurn(float alongRight, float alongFacing, Direction right, Direction facing, float degrees) {
        float x = 0.5F + right.getStepX() * alongRight + facing.getStepX() * alongFacing;
        float z = 0.5F + right.getStepZ() * alongRight + facing.getStepZ() * alongFacing;
        return new Matrix4f().translate(x, 0.0F, z).rotate(Axis.YP.rotationDegrees(degrees)).translate(-x, 0.0F, -z);
    }

    @Override
    public void submit(RenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        for (Piece piece : state.pieces) {
            piece.block().light = state.lightCoords;
            poseStack.pushPose();
            poseStack.mulPose(piece.pose());
            collector.submitMovingBlock(poseStack, piece.block(), 0);
            poseStack.popPose();
        }
    }

    /** Doors start and stop gently. */
    private static float ease(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    /** The leaves and everything they sweep through as they open. */
    @Override
    public AABB getRenderBoundingBox(InteriorDoorwayBlockEntity door) {
        InteriorDoorway doorway = door.doorway();
        int reach = doorway == null ? 1 : doorway.width() + doorway.depth() + 1;
        return new AABB(door.getBlockPos()).inflate(reach, 0.0, reach).expandTowards(0.0, doorway == null ? 1 : doorway.height(), 0.0);
    }
}
