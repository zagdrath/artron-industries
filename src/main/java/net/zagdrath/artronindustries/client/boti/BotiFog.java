/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.boti.PortalEnvironment;

/**
 * The far side's fog as vanilla sets it up for a camera standing there (FogRenderer with AtmosphericFogEnvironment),
 * from the snapshot's environment. Vanilla clears the screen to this same colour and fades terrain and the sky disc's rim
 * into it, which is what hides the horizon; looking out of a TARDIS, the backdrop, the block and skirt fog and the sky
 * fog all use it, so the far side's horizon is hidden the same way.
 */
final class BotiFog {
    /**
     * Vanilla's defaults for the sky and cloud fog end attributes, which no vanilla dimension or biome changes (so they are
     * not sent with the environment).
     */
    static final float SKY_FOG_END = 512.0F;
    static final float CLOUD_FOG_END = 2048.0F;

    private BotiFog() {}

    /**
     * The final fog colour (RGB) for a far-side camera looking along {@code farForward}: the fog colour tinted towards the
     * sunrise glow when facing the sun, blended towards the (weather-darkened) sky colour by render distance, and
     * brightened by the viewer's night vision, as vanilla does. Vanilla's darkening near the bottom of the world is left
     * out: it depends on the far dimension's floor, which the client does not know.
     */
    static int color(PortalEnvironment env, Vec3 farForward, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        int renderDistance = mc.options.getEffectiveRenderDistance();
        Vector3fc fog = ARGB.vector3fFromRGB24(env.fogColor());
        PortalEnvironment.Sky sky = env.sky();
        if (renderDistance >= 4 && sky != null) {
            float sunX = Mth.sin(sky.sunAngle() * Mth.DEG_TO_RAD) > 0.0F ? -1.0F : 1.0F;
            float lookingAtTheSun = (float) farForward.x * sunX;
            Vector4f sunrise = ARGB.vector4fFromARGB32(sky.sunriseColor());
            if (lookingAtTheSun > 0.0F && sunrise.w > 0.0F) {
                fog = ARGB.srgbLerp(lookingAtTheSun * sunrise.w, fog, new Vector3f(sunrise.x, sunrise.y, sunrise.z));
            }
        }
        Vector3fc skyColor = weatherDarken(ARGB.vector3fFromRGB24(env.skyColor()), env.rain(), env.thunder());
        float skyFogEnd = Math.min(SKY_FOG_END / 16.0F, renderDistance);
        float skyMix = 1.0F - (float) Math.pow(Mth.clampedLerp(skyFogEnd / 32.0F, 0.25F, 1.0F), 0.25);
        Vector3f color = new Vector3f(ARGB.srgbLerp(skyMix, fog, skyColor));
        brightenForNightVision(color, mc.getCameraEntity(), partialTick);
        return ARGB.colorFromVector3f(color) & 0xFFFFFF;
    }

    private static Vector3fc weatherDarken(Vector3fc color, float rain, float thunder) {
        if (rain > 0.0F) {
            color = ARGB.scaleRGB(color, 1.0F - rain * 0.5F, 1.0F - rain * 0.5F, 1.0F - rain * 0.4F);
        }
        if (thunder > 0.0F) {
            color = ARGB.scaleRGB(color, 1.0F - thunder * 0.5F);
        }
        return color;
    }

    private static void brightenForNightVision(Vector3f color, @Nullable Entity camera, float partialTick) {
        if (!(camera instanceof LivingEntity living) || !living.hasEffect(MobEffects.NIGHT_VISION) || living.hasEffect(MobEffects.DARKNESS)) {
            return;
        }
        float max = Math.max(color.x, Math.max(color.y, color.z));
        if (color.x != 0.0F && color.y != 0.0F && color.z != 0.0F) {
            color.lerp(new Vector3f(color).div(max), GameRenderer.nightVisionScale(living, partialTick));
        }
    }

    /**
     * Where render-distance fog ends, measured from the camera: vanilla's render distance, but never beyond the ground
     * skirt. The skirt is laid out from the far door out to {@link BotiRenderer#skirtRadius()}, so for a camera
     * {@code cameraToDoor} blocks from the door, everything within this distance of it is still on the skirt.
     */
    static float renderDistanceEnd(double cameraToDoor) {
        float radius = BotiRenderer.skirtRadius();
        return (float) Math.max(radius - cameraToDoor, Math.min(radius, 16.0F));
    }

    /** Where render-distance fog starts, for fog ending at {@code end}: vanilla's span. */
    static float renderDistanceStart(float end) {
        float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;
        return end - Mth.clamp(renderDistance / 10.0F, 4.0F, 64.0F);
    }

    /**
     * How strongly rain pulls the far side's environmental fog in, as vanilla's atmospheric fog works it out at the
     * camera: by the rain, the sky light there and whether it rains in that biome at all.
     */
    static float rainFogMultiplier(PortalEnvironment env, int skyLight, boolean precipitation) {
        float skyLightFactor = Mth.clamp((skyLight - 8.0F) / 7.0F, 0.0F, 1.0F);
        return env.rain() * skyLightFactor * (precipitation ? 1.0F : 0.5F);
    }
}
