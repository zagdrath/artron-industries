/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.util.IdentityHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.util.ARGB;
import net.zagdrath.artronindustries.boti.PortalEnvironment;

/**
 * One vanilla {@link Lightmap} per cached view, lit like the far side: its sky light strength and colour, ambient colour
 * and block light tint come from the far side's environment ({@link PortalEnvironment.Light}), everything that belongs
 * to the viewer (gamma, night vision, darkness, block light flicker, boss fog) from the viewer's own lightmap. The block
 * mesh is meshed with the far side's real light levels and sampled through it, so night falls through a doorway without
 * a rebuild and a far side with no sky light (or a dim ambient, like the Nether) looks as it does there.
 * <p>
 * Block entities and entities seen through a doorway are drawn by vanilla's feature renderers, which bind the main
 * lightmap themselves; they keep {@link BotiRenderer}'s folded light levels.
 */
final class BotiLightmaps {
    private static final class Entry {
        final Lightmap lightmap = new Lightmap();
        PortalEnvironment.@Nullable Light light;
    }

    private static final Map<BotiClientCache.View, Entry> ENTRIES = new IdentityHashMap<>();
    private static final LightmapRenderState STATE = new LightmapRenderState();

    private BotiLightmaps() {}

    /**
     * The lightmap to draw {@code view}'s blocks with, updated when the viewer's lightmap was (once a tick) or the far side's
     * light changed. Render thread, outside any render pass (it draws into its own texture).
     */
    static GpuTextureView prepare(BotiClientCache.View view) {
        Entry entry = ENTRIES.get(view);
        boolean created = entry == null;
        if (created) {
            entry = new Entry();
            ENTRIES.put(view, entry);
        }
        LightmapRenderState viewer = Minecraft.getInstance().gameRenderer.gameRenderState().lightmapRenderState;
        PortalEnvironment.Light light = view.snapshot().environment().light();
        if (created || viewer.needsUpdate || !light.equals(entry.light)) {
            entry.light = light;
            STATE.needsUpdate = true;
            STATE.blockFactor = viewer.blockFactor;
            STATE.brightness = viewer.brightness;
            STATE.darknessEffectScale = viewer.darknessEffectScale;
            STATE.nightVisionEffectIntensity = viewer.nightVisionEffectIntensity;
            STATE.nightVisionColor = viewer.nightVisionColor;
            STATE.bossOverlayWorldDarkening = viewer.bossOverlayWorldDarkening;
            STATE.skyFactor = light.skyFactor();
            STATE.skyLightColor = ARGB.vector3fFromRGB24(light.skyColor());
            STATE.ambientColor = ARGB.vector3fFromRGB24(light.ambientColor());
            STATE.blockLightTint = ARGB.vector3fFromRGB24(light.blockTint());
            entry.lightmap.render(STATE);
        }
        return entry.lightmap.getTextureView();
    }

    /** Frees the lightmap of a removed view (render thread). */
    static void release(BotiClientCache.View view) {
        Entry entry = ENTRIES.remove(view);
        if (entry != null) {
            entry.lightmap.close();
        }
    }
}
