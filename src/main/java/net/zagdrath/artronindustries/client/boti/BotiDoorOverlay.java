/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/**
 * Implemented by the renderer of a door block entity whose doors open inwards, behind the doorway plane. Inside the
 * doorway the far side is drawn over everything behind that plane, so {@link BotiRenderer} has the renderer submit those
 * doors again afterwards, inside the doorway: they then sort against the far side as if they swung into it.
 */
public interface BotiDoorOverlay<S extends BlockEntityRenderState> {
    /**
     * Submits only the parts that swing in behind the doorway plane, exactly as {@code submit} draws them.
     * {@code poseStack} is at the block entity's position, as for {@code submit}.
     */
    void submitBehindDoorway(S state, PoseStack poseStack, SubmitNodeCollector collector);
}
