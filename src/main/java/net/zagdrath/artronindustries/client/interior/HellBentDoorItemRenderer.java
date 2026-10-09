/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import java.util.List;
import java.util.function.Consumer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.zagdrath.artronindustries.block.HellBentDoorBlock;

/**
 * The Hell Bent door item: the door shut, shrunk to fit the item's block space (three blocks tall into one), front to
 * the north like the TARDIS item.
 */
public class HellBentDoorItemRenderer implements NoDataSpecialModelRenderer {
    private static final float WIDTH = HellBentDoorBlock.DOORWAY.width();
    private static final float HEIGHT = HellBentDoorBlock.DOORWAY.height();
    private static final float DEPTH = HellBentDoorBlock.THICKNESS / 16.0F;

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        HellBentDoorModel.Parts parts = HellBentDoorModel.INSTANCE.parts();
        if (parts == null) {
            return;
        }
        RenderType type = RenderTypes.entitySolid(HellBentDoorModel.TEXTURE);
        poseStack.pushPose();
        poseStack.mulPose(pose());
        for (List<ExtrudedPixels.Quad> part : List.of(parts.frame(), parts.left(), parts.right())) {
            collector.submitCustomGeometry(poseStack, type, (p, buffer) -> HellBentDoorModel.emit(part, p, buffer, lightCoords, overlayCoords));
        }
        poseStack.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        Matrix4f pose = pose();
        for (int corner = 0; corner < 8; corner++) {
            output.accept(pose.transformPosition((corner & 1) * WIDTH, (corner >> 1 & 1) * HEIGHT, -(corner >> 2 & 1) * DEPTH, new Vector3f()));
        }
    }

    /** From door space: the door centred on the item's block space and scaled to fit, its front to the north. */
    private static Matrix4f pose() {
        float scale = 1.0F / HEIGHT;
        return new Matrix4f().translate(0.5F, 0.5F, 0.5F).scale(scale).rotateY((float) Math.PI)
                .translate(-WIDTH / 2.0F, -HEIGHT / 2.0F, DEPTH / 2.0F);
    }

    public record Unbaked() implements NoDataSpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public HellBentDoorItemRenderer bake(SpecialModelRenderer.BakingContext context) {
            return new HellBentDoorItemRenderer();
        }
    }
}
