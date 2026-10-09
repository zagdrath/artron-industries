/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.MoonPhase;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalEnvironment;
import net.zagdrath.artronindustries.client.ArtronClientConfig;
import net.zagdrath.artronindustries.portal.PortalSide;

/**
 * The outside's sky seen from inside a TARDIS: vanilla's sky disc, sunrise glow, sun, moon, stars and clouds, drawn
 * inside the doorway (after its backdrop, before the outside's blocks) as they stand on the far side of the door.
 * <p>
 * The server samples the far side's sky every {@code boti.headerRefreshInterval} ticks; in between, the sun, moon and
 * stars keep moving at the rate seen between the last two samples, and the clouds drift with the far side's game time.
 * The interior has no sky of its own, so vanilla's sky renderer is free to borrow; the clouds get their own renderer,
 * as they are prepared ahead of the frame.
 */
public final class BotiSky {
    /** Made with the client's reload listeners: it allocates GPU buffers, so not before the render system is up. */
    private static @Nullable CloudRenderer clouds;
    /** Sky samples are at most this far apart; further extrapolation is not trusted. */
    private static final float MAX_EXTRAPOLATION_TICKS = 60.0F;

    /** Per view: the last two sky samples and when the latest arrived, for the sun's motion between samples. */
    private static final Map<BotiClientCache.View, Track> TRACKS = new WeakHashMap<>();
    private static @Nullable GpuBuffer fog;
    private static @Nullable GpuBuffer previousFog;
    private static boolean cloudsPrepared;

    /** What one doorway draws this frame. */
    record Frame(float sunAngle, float moonAngle, float starAngle, float starBrightness, MoonPhase moonPhase, Vector4f sunriseColor,
                 Vector4f skyColor, float rainBrightness, boolean clouds, int quarterTurns) {}

    private static final class Track {
        PortalEnvironment.Sky sky;
        long receivedAt;
        float sunPerTick;
        float moonPerTick;
        float starPerTick;

        Track(PortalEnvironment.Sky sky, long receivedAt) {
            this.sky = sky;
            this.receivedAt = receivedAt;
        }
    }

    private BotiSky() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(AddClientReloadListenersEvent.class, e -> {
            if (clouds == null) {
                clouds = new CloudRenderer();
            }
            e.addListener(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_clouds"), clouds);
        });
    }

    /**
     * Picks the doorway (the nearest one looking out at a sky) whose sky is drawn this frame, and prepares its clouds.
     * Runs before the frame's render passes, as the clouds upload their geometry.
     */
    static void prepare(List<BotiRenderer.DoorDraw> draws, float partialTick) {
        if (previousFog != null) {
            previousFog.close();
        }
        previousFog = fog;
        fog = null;
        cloudsPrepared = false;
        for (BotiRenderer.DoorDraw draw : draws) {
            draw.sky = null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ArtronClientConfig.RENDER_SKY.getAsBoolean()) {
            return;
        }
        for (BotiRenderer.DoorDraw draw : draws) {
            if (draw.hidden || draw.fallback || draw.debugFloating || draw.arrival || draw.view == null || draw.farCamera == null
                    || draw.key.nearSide() != PortalSide.INTERIOR) {
                continue;
            }
            PortalEnvironment env = draw.view.snapshot().environment();
            PortalEnvironment.Sky sky = env.sky();
            if (sky == null) {
                continue;
            }
            Track track = TRACKS.get(draw.view);
            long now = mc.level.getGameTime();
            if (track == null) {
                track = new Track(sky, now);
                TRACKS.put(draw.view, track);
            } else if (track.sky != sky) {
                long dt = sky.gameTime() - track.sky.gameTime();
                if (dt > 0 && dt <= MAX_EXTRAPOLATION_TICKS * 2) {
                    track.sunPerTick = Mth.wrapDegrees(sky.sunAngle() - track.sky.sunAngle()) / dt;
                    track.moonPerTick = Mth.wrapDegrees(sky.moonAngle() - track.sky.moonAngle()) / dt;
                    track.starPerTick = Mth.wrapDegrees(sky.starAngle() - track.sky.starAngle()) / dt;
                }
                track.sky = sky;
                track.receivedAt = now;
            }
            float ahead = Math.clamp(now - track.receivedAt + partialTick, 0.0F, MAX_EXTRAPOLATION_TICKS);

            boolean drawClouds = false;
            CloudStatus cloudStatus = mc.options.getCloudStatus();
            if (clouds != null && cloudStatus != CloudStatus.OFF && ARGB.alpha(sky.cloudColor()) > 0) {
                long whole = (long) Math.floor(ahead);
                clouds.prepare(sky.cloudColor(), cloudStatus, sky.cloudHeight(), mc.options.cloudRange().get(), draw.farCamera,
                        sky.gameTime() + whole, ahead - whole);
                cloudsPrepared = true;
                drawClouds = true;
            }
            // The doorway's own fog colour and distance (see BotiFog), so the disc's rim fades out exactly where the skirt
            // does, into the backdrop's colour.
            fog = fogBuffer(draw.backdropBottom, Math.min(draw.fogEnd, BotiFog.SKY_FOG_END),
                    Math.min(mc.options.cloudRange().get() * 16.0F, BotiFog.CLOUD_FOG_END));
            draw.sky = new Frame(
                    (sky.sunAngle() + track.sunPerTick * ahead) * Mth.DEG_TO_RAD,
                    (sky.moonAngle() + track.moonPerTick * ahead) * Mth.DEG_TO_RAD,
                    (sky.starAngle() + track.starPerTick * ahead) * Mth.DEG_TO_RAD,
                    sky.starBrightness(),
                    MoonPhase.values()[Math.floorMod(sky.moonPhase(), MoonPhase.COUNT)],
                    ARGB.vector4fFromARGB32(sky.sunriseColor()),
                    new Vector4f(ARGB.vector3fFromRGB24(env.skyColor()), 1.0F),
                    1.0F - env.rain(),
                    drawClouds,
                    draw.view.snapshot().geometry().nearToFar().invert().quarterTurns());
            // One sky per frame: the clouds have one set of geometry.
            return;
        }
    }

    /** Draws {@code draw}'s sky into the doorway's stencil, in the main render pass. */
    static void render(RenderPass pass, Matrix4fc view, BotiRenderer.DoorDraw draw) {
        Frame frame = draw.sky;
        Minecraft mc = Minecraft.getInstance();
        SkyRenderer sky = mc.levelRenderer.skyRenderer();
        if (frame == null || sky == null || fog == null) {
            return;
        }
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        GpuBufferSlice vanillaFog = RenderSystem.getShaderFog();
        modelView.pushMatrix();
        // Far-side directions to the near camera: the camera's rotation after the doorway's quarter turns.
        modelView.set(view).rotateY(-Mth.HALF_PI * frame.quarterTurns());
        RenderSystem.setShaderFog(fog.slice());
        RenderSystem.pushPipelineModifier(BotiPipelines.INSIDE_DOORWAY);
        try {
            pass.pushDebugGroup(() -> "BOTI sky");
            // The sky disc does not bind its own uniforms (vanilla binds them once per sky pass): bind ours, with the sky fog.
            RenderSystem.bindDefaultUniforms(pass);
            sky.renderSkyDisc(pass, new org.joml.Vector3f(frame.skyColor().x, frame.skyColor().y, frame.skyColor().z));
            PoseStack poseStack = new PoseStack();
            sky.renderSunriseAndSunset(pass, poseStack, frame.sunAngle(), frame.sunriseColor());
            sky.renderSunMoonAndStars(pass, poseStack, frame.sunAngle(), frame.moonAngle(), frame.starAngle(), frame.moonPhase(),
                    frame.rainBrightness(), frame.starBrightness() * frame.rainBrightness());
            if (frame.clouds() && cloudsPrepared && clouds != null) {
                clouds.render(mc.options.getCloudStatus(), pass);
            }
            pass.popDebugGroup();
        } finally {
            RenderSystem.popPipelineModifier();
            if (vanillaFog != null) {
                RenderSystem.setShaderFog(vanillaFog);
                pass.setUniform("Fog", vanillaFog);
            }
            modelView.popMatrix();
        }
    }

    static void endFrame() {
        if (cloudsPrepared && clouds != null) {
            clouds.endFrame();
        }
    }

    /** Fog for the sky disc and clouds: they fade into the far side's fog colour towards the horizon, as vanilla's do. */
    private static GpuBuffer fogBuffer(int color, float skyEnd, float cloudsEnd) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, FogRenderer.FOG_UBO_SIZE)
                    .putVec4(((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, 1.0F)
                    .putFloat(1.0E6F)
                    .putFloat(1.0E6F)
                    .putFloat(1.0E6F)
                    .putFloat(1.0E6F)
                    .putFloat(skyEnd)
                    .putFloat(cloudsEnd)
                    .get();
            return RenderSystem.getDevice().createBuffer(() -> "BOTI sky fog", GpuBuffer.USAGE_UNIFORM, data);
        }
    }
}
