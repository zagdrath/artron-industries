/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.exterior;

import java.util.function.Consumer;

import org.joml.Vector3fc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;

/**
 * The TARDIS item: the Hudolin box with its doors shut, shrunk to fit the item's block space (3 1/4 blocks tall into
 * one), front to the north so the inventory shows it like any block's front.
 */
public class HudolinExteriorItemRenderer implements NoDataSpecialModelRenderer {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "hudolin_exterior");
    private static final HudolinExteriorModel.State SHUT = new HudolinExteriorModel.State(0.0F, 0.0F, false);
    private static final float HEIGHT = 52.0F / 16.0F;

    private final HudolinExteriorModel model;

    public HudolinExteriorItemRenderer(HudolinExteriorModel model) {
        this.model = model;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        poseStack.pushPose();
        pose(poseStack);
        collector.submitModel(this.model, SHUT, poseStack, HudolinExteriorRenderer.TEXTURE, lightCoords, overlayCoords, outlineColor);
        HudolinExteriorRenderer.submitWindows(this.model, SHUT, poseStack, collector);
        poseStack.popPose();
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        PoseStack poseStack = new PoseStack();
        pose(poseStack);
        this.model.setupAnim(SHUT);
        this.model.root().getExtentsForGui(poseStack, output);
    }

    /** Centres the box on the item's block space and scales it to fit. */
    private static void pose(PoseStack poseStack) {
        float scale = 1.0F / HEIGHT;
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-0.5F, -HEIGHT / 2.0F, -0.5F);
        HudolinExteriorRenderer.orient(poseStack, Direction.NORTH);
    }

    public record Unbaked() implements NoDataSpecialModelRenderer.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public HudolinExteriorItemRenderer bake(SpecialModelRenderer.BakingContext context) {
            return new HudolinExteriorItemRenderer(new HudolinExteriorModel(context.entityModelSet().bakeLayer(HudolinExteriorRenderer.LAYER)));
        }
    }
}
