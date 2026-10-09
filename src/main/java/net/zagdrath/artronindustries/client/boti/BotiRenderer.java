/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalEnvironment;
import net.zagdrath.artronindustries.boti.PortalGeometry;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.boti.SnapshotBox;
import net.zagdrath.artronindustries.client.ArtronClientConfig;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalEndpoint;
import net.zagdrath.artronindustries.portal.PortalEndpoints;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;

/**
 * Draws the view through every open, visible {@link PortalEndpoint}. Driven only by the endpoint tracker, so any block
 * entity implementing {@link PortalEndpoint} (such as the TARDIS and its interior door) gets BOTI for free.
 * <p>
 * Per frame: doors are culled and their draw data computed during level extraction; meshes are uploaded, translucent
 * quads re-sorted, doorway quads uploaded and block entities submitted in {@link PrepareRenderBuffersEvent} (no render pass
 * may be open while uploading); the draws happen in {@link RenderLevelStageEvent.AfterOpaqueFeatures}.
 * <p>
 * Why AfterOpaqueFeatures: all opaque terrain and opaque entities/block entities are already in the depth buffer (so the
 * stencil mark is correctly hidden by anything in front of the doorway), translucent terrain, translucent features,
 * particles and weather have not been drawn yet (so they sort and blend against the sealed doorway depth afterwards), and
 * it fires in both the classic and the improved-transparency (OIT) paths. Its render pass targets the main colour and
 * depth-stencil attachments.
 */
public final class BotiRenderer {
    private static final ContextKey<List<DoorDraw>> DRAWS = new ContextKey<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_draws"));
    private static final ChunkSectionLayer[] LAYERS = {ChunkSectionLayer.SOLID, ChunkSectionLayer.CUTOUT, ChunkSectionLayer.TRANSLUCENT};
    private static final int QUADS_PER_DOOR = 3;
    /** Camera distances (blocks) in front of / behind a doorway plane within which the far side fills the screen. */
    private static final double FULLSCREEN_IN_FRONT = 0.06;
    private static final double FULLSCREEN_BEHIND = 1.5;

    /** Everything needed to draw one doorway this frame. */
    /** A clip plane everything is in front of. */
    static final Vector3f NO_CLIP = new Vector3f(0.0F, 0.0F, 1.0F);

    static final class DoorDraw {
        final PortalViewKey key;
        final BotiClientCache.@Nullable View view;
        final Vector3f[] corners;
        final double distanceSqr;
        final boolean fallback;
        final boolean debugFloating;
        /** The arrival cover after walking through a doorway (see SeamlessTransition). */
        boolean arrival;
        @Nullable Matrix4f model;
        /** The far doorway plane in box-local space (see SnapshotBox#clipPlane); nothing behind it is drawn. */
        Vector3f clip = NO_CLIP;
        @Nullable Vector3f boxLocalCamera;
        @Nullable Vec3 farCamera;
        int backdropTop;
        int backdropBottom;
        /** The outside's sky drawn in this doorway this frame (see BotiSky), or null. */
        BotiSky.@Nullable Frame sky;
        float skyToBlock = -1.0F;
        @Nullable BotiMesh mesh;
        /** Fog uniform for everything drawn inside this doorway (fades the far side into its own fog colour). */
        @Nullable GpuBuffer fog;
        int firstQuad;
        boolean ownsBlockEntities;
        /** The near-side door this doorway belongs to, when it is a real one. */
        @Nullable PortalEndpoint endpoint;
        /** A shut door: only its mesh is built (see {@link #warm}), nothing is drawn. */
        boolean warmOnly;
        /** Not drawn this frame: warm only, or its view has no mesh yet. */
        boolean hidden;

        DoorDraw(PortalViewKey key, BotiClientCache.@Nullable View view, Vector3f[] corners, double distanceSqr, boolean fallback, boolean debugFloating) {
            this.key = key;
            this.view = view;
            this.corners = corners;
            this.distanceSqr = distanceSqr;
            this.fallback = fallback;
            this.debugFloating = debugFloating;
        }
    }

    private static @Nullable Boolean shaderModPresent;
    private static @Nullable BlockPos debugFloatingPos;
    private static @Nullable GpuBuffer quadBuffer;
    private static @Nullable GpuBuffer previousQuadBuffer;
    private static final List<GpuBuffer> FOG_BUFFERS = new ArrayList<>();
    private static final List<GpuBuffer> PREVIOUS_FOG_BUFFERS = new ArrayList<>();
    private static @Nullable RenderBuffers renderBuffers;
    private static @Nullable FeatureRenderDispatcher featureDispatcher;
    private static final SubmitNodeStorage SUBMITS = new SubmitNodeStorage();
    private static FeatureRenderDispatcher.@Nullable PreparedFrame featureFrame;
    private static int featureLogCounter;
    private static final java.util.Set<Object> LOGGED_TYPES = new java.util.HashSet<>();
    private static double lastDrawMs;
    private static int lastDrawCount;

    private BotiRenderer() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ExtractLevelRenderStateEvent.class, BotiRenderer::extract);
        NeoForge.EVENT_BUS.addListener(PrepareRenderBuffersEvent.class, BotiRenderer::prepare);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueFeatures.class, BotiRenderer::render);
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class, e -> endFrame());
    }

    /** Shows the first cached view floating at {@code pos} (no doorway, no stencil), or turns that off with null. */
    public static void setDebugFloatingPos(@Nullable BlockPos pos) {
        debugFloatingPos = pos;
    }

    public static boolean usesFallback() {
        if (shaderModPresent == null) {
            shaderModPresent = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
            if (shaderModPresent) {
                ArtronIndustries.LOGGER.info("Shader pack mod detected: TARDIS doorways use the fallback surface unless boti.forceWithShaders is set");
            }
        }
        return ArtronClientConfig.RENDER_MODE.get() == ArtronClientConfig.RenderMode.DISABLED
                || !BotiPipelines.stencilAvailable()
                || (shaderModPresent && !ArtronClientConfig.FORCE_WITH_SHADERS.getAsBoolean());
    }

    // --- Extract ------------------------------------------------------------------------------------------------------

    private static void extract(ExtractLevelRenderStateEvent event) {
        ClientLevel level = event.getLevel();
        Vec3 camera = event.getCamera().position();
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        boolean fallback = usesFallback();
        double maxDistance = ArtronClientConfig.RENDER_DISTANCE.getAsInt();
        List<DoorDraw> draws = new ArrayList<>();

        for (PortalEndpoint endpoint : PortalEndpoints.in(level)) {
            if (endpoint.getTardisId() == null) {
                continue;
            }
            OpenSpan span = endpoint.getOpenSpan(partialTick);
            if (span.isEmpty()) {
                if (!fallback) {
                    warm(endpoint, level, camera, maxDistance, draws);
                }
                continue;
            }
            PortalShape shape = endpoint.getPortalShape();
            BlockPos pos = endpoint.getPortalPos();
            Direction facing = endpoint.getFacing();
            double cameraDistance = shape.signedDistance(pos, facing, camera);
            // Camera in the doorway plane (the doorway quad would be cut by the near plane) or just through it while the
            // server has not moved the player yet: the whole screen shows the far side.
            boolean fullscreen = cameraDistance < FULLSCREEN_IN_FRONT && cameraDistance > -FULLSCREEN_BEHIND
                    && shape.containsProjected(pos, facing, camera, -0.05, span) && !endpoint.getPassableSpan().isEmpty();
            if (cameraDistance <= 0.0 && !fullscreen) {
                continue; // looking at the back of the doorway
            }
            double distanceSqr = shape.center(pos, facing).distanceToSqr(camera);
            if (distanceSqr > maxDistance * maxDistance || !event.getFrustum().isVisible(shape.bounds(pos, facing, 0.05))) {
                continue;
            }
            PortalViewKey key = new PortalViewKey(endpoint.getTardisId(), endpoint.getPortalSide());
            BotiClientCache.View view = BotiClientCache.get(key);
            if (view != null && !view.snapshot().geometry().nearPos().equals(pos)) {
                view = null; // stale view for a door that moved; show the backdrop until the new snapshot arrives
            }
            Vector3f[] corners = fullscreen ? screenQuad(event.getCamera().forwardVector(), event.getCamera().upVector(),
                    event.getCamera().leftVector()) : new Vector3f[4];
            if (!fullscreen) {
                Vec3[] world = shape.corners(pos, facing, span);
                for (int i = 0; i < 4; i++) {
                    corners[i] = new Vector3f((float) (world[i].x - camera.x), (float) (world[i].y - camera.y), (float) (world[i].z - camera.z));
                }
            }
            DoorDraw draw = new DoorDraw(key, view, corners, distanceSqr, fallback, false);
            draw.endpoint = endpoint;
            if (!fallback) {
                computeView(draw, level, camera);
            }
            draws.add(draw);
        }
        draws.sort(Comparator.comparingDouble(d -> d.distanceSqr));

        PortalViewKey arrival = SeamlessTransition.arrivalCover(level);
        BotiClientCache.View arrivalView = arrival == null ? null : BotiClientCache.get(arrival);
        if (arrivalView != null && !fallback) {
            // Just walked through a doorway: until the real chunks are compiled, draw the cached far side where it is.
            Vec3 origin = Vec3.atLowerCornerOf(arrivalView.snapshot().box().origin());
            DoorDraw draw = new DoorDraw(arrival, arrivalView, new Vector3f[0], 0.0, false, true);
            draw.arrival = true;
            draw.model = new Matrix4f().translation((float) (origin.x - camera.x), (float) (origin.y - camera.y), (float) (origin.z - camera.z));
            draw.boxLocalCamera = camera.subtract(origin).toVector3f();
            draw.farCamera = camera;
            draw.skyToBlock = skyToBlock(level, arrivalView.snapshot().environment());
            draws.add(draw);
        }

        if (debugFloatingPos != null && !fallback) {
            for (var entry : BotiClientCache.entries()) {
                DoorDraw draw = new DoorDraw(entry.getKey(), entry.getValue(), new Vector3f[0], 0.0, false, true);
                SnapshotBox box = entry.getValue().snapshot().box();
                draw.model = new Matrix4f().translation((float) (debugFloatingPos.getX() - camera.x), (float) (debugFloatingPos.getY() - camera.y),
                        (float) (debugFloatingPos.getZ() - camera.z));
                draw.boxLocalCamera = new Vector3f((float) (camera.x - debugFloatingPos.getX()), (float) (camera.y - debugFloatingPos.getY()),
                        (float) (camera.z - debugFloatingPos.getZ()));
                draw.farCamera = camera.subtract(Vec3.atLowerCornerOf(debugFloatingPos)).add(Vec3.atLowerCornerOf(box.origin()));
                draw.skyToBlock = skyToBlock(level, entry.getValue().snapshot().environment());
                draws.add(draw);
                break;
            }
        }
        event.getRenderState().setRenderData(DRAWS, draws);
    }

    /**
     * A shut door in front of the camera whose view the server already streams: build that view's mesh now, so the far
     * side is there the moment the doors start to open.
     */
    private static void warm(PortalEndpoint endpoint, ClientLevel level, Vec3 camera, double maxDistance, List<DoorDraw> draws) {
        PortalShape shape = endpoint.getPortalShape();
        BlockPos pos = endpoint.getPortalPos();
        Direction facing = endpoint.getFacing();
        double distanceSqr = shape.center(pos, facing).distanceToSqr(camera);
        if (distanceSqr > maxDistance * maxDistance || shape.signedDistance(pos, facing, camera) <= 0.0) {
            return;
        }
        PortalViewKey key = new PortalViewKey(endpoint.getTardisId(), endpoint.getPortalSide());
        BotiClientCache.View view = BotiClientCache.get(key);
        if (view == null || !view.snapshot().geometry().nearPos().equals(pos)) {
            return;
        }
        DoorDraw draw = new DoorDraw(key, view, new Vector3f[0], distanceSqr, false, false);
        draw.warmOnly = true;
        computeView(draw, level, camera);
        draws.add(draw);
    }

    /** A camera-facing quad just past the near plane that covers the whole view. */
    private static Vector3f[] screenQuad(org.joml.Vector3fc forward, org.joml.Vector3fc up, org.joml.Vector3fc left) {
        Vector3f center = new Vector3f(forward).mul(0.1F);
        Vector3f l = new Vector3f(left).mul(2.0F);
        Vector3f u = new Vector3f(up).mul(2.0F);
        return new Vector3f[]{
                new Vector3f(center).add(l).sub(u),
                new Vector3f(center).sub(l).sub(u),
                new Vector3f(center).sub(l).add(u),
                new Vector3f(center).add(l).add(u)};
    }

    /** Fills in the far -> near transform, sorting origin and backdrop for a door with a cached view. */
    private static void computeView(DoorDraw draw, ClientLevel level, Vec3 camera) {
        boolean interiorBeyond = draw.key.nearSide() == PortalSide.EXTERIOR;
        PortalEnvironment env = draw.view != null ? draw.view.snapshot().environment() : null;
        if (interiorBeyond || env == null) {
            int c = ArtronClientConfig.INTERIOR_BACKDROP_COLOR.getAsInt();
            draw.backdropTop = 0xFF000000 | c;
            draw.backdropBottom = 0xFF000000 | c;
        } else {
            float darken = 1.0F - env.rain() * 0.25F - env.thunder() * 0.25F;
            draw.backdropTop = 0xFF000000 | scale(env.hasSky() ? env.skyColor() : env.fogColor(), darken);
            draw.backdropBottom = 0xFF000000 | scale(env.fogColor(), darken);
        }
        if (draw.view == null) {
            return;
        }
        PortalSnapshot snapshot = draw.view.snapshot();
        PortalGeometry geometry = snapshot.geometry();
        DoorPairTransform nearToFar = geometry.nearToFar();
        DoorPairTransform farToNear = nearToFar.invert();
        SnapshotBox box = snapshot.box();
        Vec3 origin = Vec3.atLowerCornerOf(box.origin());
        draw.model = boxToCameraRelative(farToNear, origin, camera);
        draw.clip = box.clipPlane(geometry.farPos(), geometry.farFacing(), geometry.farShape());
        draw.farCamera = nearToFar.apply(camera);
        draw.boxLocalCamera = draw.farCamera.subtract(origin).toVector3f();
        draw.skyToBlock = skyToBlock(level, snapshot.environment());
    }

    /** Matrix taking box-local far-side positions to camera-relative near-side positions, built in double precision. */
    static Matrix4f boxToCameraRelative(DoorPairTransform farToNear, Vec3 boxOrigin, Vec3 camera) {
        Vec3 t = farToNear.apply(boxOrigin).subtract(camera);
        float angle = (float) (-farToNear.quarterTurns() * Math.PI / 2.0);
        Matrix4f m = new Matrix4f().rotationY(angle);
        m.setTranslation((float) t.x, (float) t.y, (float) t.z);
        return m;
    }

    /**
     * Sky-light folding factor: when the viewer's dimension has no sky light but the far side does, sky light is baked
     * into block light scaled by the far side's daylight, quantised to 1/16 so it only causes a rebuild when visibly
     * different. -1 = no folding.
     */
    private static float skyToBlock(ClientLevel level, PortalEnvironment env) {
        if (level.dimensionType().hasSkyLight() || !env.hasSky()) {
            return -1.0F;
        }
        double dayFraction = Math.floorMod(env.dayTime(), 24000L) / 24000.0;
        double sun = Math.cos((dayFraction - 0.25) * Math.PI * 2.0);
        double daylight = Math.clamp(sun * 2.0 + 0.5, 0.25, 1.0) * (1.0 - env.rain() * 0.3);
        return Math.round(daylight * 16.0) / 16.0F;
    }

    private static int scale(int rgb, float factor) {
        int r = Math.round(((rgb >> 16) & 0xFF) * factor);
        int g = Math.round(((rgb >> 8) & 0xFF) * factor);
        int b = Math.round((rgb & 0xFF) * factor);
        return Math.clamp(r, 0, 255) << 16 | Math.clamp(g, 0, 255) << 8 | Math.clamp(b, 0, 255);
    }

    // --- Prepare (uploads; no render pass open) ------------------------------------------------------------------------

    private static void prepare(PrepareRenderBuffersEvent event) {
        List<DoorDraw> draws = event.getLevelRenderState().getRenderData(DRAWS);
        if (draws == null || draws.isEmpty()) {
            return;
        }
        BotiMeshCache.beginFrame();
        PREVIOUS_FOG_BUFFERS.forEach(GpuBuffer::close);
        PREVIOUS_FOG_BUFFERS.clear();
        PREVIOUS_FOG_BUFFERS.addAll(FOG_BUFFERS);
        FOG_BUFFERS.clear();
        int sequentialIndices = 0;
        for (DoorDraw draw : draws) {
            if (draw.view != null && draw.boxLocalCamera != null) {
                draw.mesh = BotiMeshCache.prepare(draw.view, draw.skyToBlock, draw.boxLocalCamera);
                if (!draw.warmOnly) {
                    draw.fog = fogBuffer(draw);
                    FOG_BUFFERS.add(draw.fog);
                }
                if (draw.mesh != null) {
                    sequentialIndices = Math.max(sequentialIndices, draw.mesh.maxSequentialIndices());
                }
            }
            // Until the far side can be drawn, leave the doorway alone rather than flash its bare backdrop.
            draw.hidden = draw.warmOnly || (!draw.fallback && !draw.debugFloating && draw.mesh == null);
        }
        BotiSky.prepare(draws, Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));

        // Doorway quads for every door: mark, backdrop and seal (or a single fallback quad).
        int quads = 0;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(draws.size() * QUADS_PER_DOOR * 4 * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (DoorDraw draw : draws) {
                if (draw.debugFloating || draw.hidden) {
                    continue;
                }
                draw.firstQuad = quads;
                if (draw.fallback) {
                    quad(builder, draw.corners, 0xFF04050A, 0xFF04050A);
                    quads++;
                } else {
                    quad(builder, draw.corners, 0xFFFFFFFF, 0xFFFFFFFF);
                    quad(builder, draw.corners, draw.backdropBottom, draw.backdropTop);
                    quad(builder, draw.corners, 0xFFFFFFFF, 0xFFFFFFFF);
                    quads += QUADS_PER_DOOR;
                }
            }
            if (previousQuadBuffer != null) {
                previousQuadBuffer.close();
            }
            previousQuadBuffer = quadBuffer;
            quadBuffer = null;
            if (quads > 0) {
                try (MeshData mesh = builder.buildOrThrow()) {
                    quadBuffer = RenderSystem.getDevice().createBuffer(() -> "BOTI doorway quads", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                }
            }
        }
        sequentialIndices = Math.max(sequentialIndices, quads * 6);
        if (sequentialIndices > 0) {
            RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(sequentialIndices);
        }

        prepareFeatures(draws, event.getLevelRenderState());
    }

    /**
     * Looking out of a TARDIS, terrain fades into the far side's fog colour towards the far end of the snapshot box, which
     * hides where the streamed region stops. Looking in, interiors are small enough that no fog is applied.
     */
    private static GpuBuffer fogBuffer(DoorDraw draw) {
        PortalSnapshot snapshot = draw.view.snapshot();
        float start = 1.0E6F;
        float end = 1.0E6F;
        if (draw.key.nearSide() == PortalSide.INTERIOR && !draw.debugFloating) {
            SnapshotBox box = snapshot.box();
            float depth = snapshot.geometry().farFacing().getAxis() == Direction.Axis.X ? box.sizeX() : box.sizeZ();
            start = depth * 0.55F;
            end = depth * 0.95F;
        }
        int c = draw.backdropBottom;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = Std140Builder.onStack(stack, FogRenderer.FOG_UBO_SIZE)
                    .putVec4(((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F, 1.0F)
                    .putFloat(start)
                    .putFloat(end)
                    .putFloat(1.0E6F)
                    .putFloat(1.0E6F)
                    .putFloat(1.0E6F)
                    .putFloat(1.0E6F)
                    .get();
            return RenderSystem.getDevice().createBuffer(() -> "BOTI fog", GpuBuffer.USAGE_UNIFORM, data);
        }
    }

    private static void quad(BufferBuilder builder, Vector3f[] c, int bottomColor, int topColor) {
        builder.addVertex(c[0].x, c[0].y, c[0].z).setColor(bottomColor);
        builder.addVertex(c[1].x, c[1].y, c[1].z).setColor(bottomColor);
        builder.addVertex(c[2].x, c[2].y, c[2].z).setColor(topColor);
        builder.addVertex(c[3].x, c[3].y, c[3].z).setColor(topColor);
    }

    /**
     * Submits the nearest drawn view's block entities and entity stand-ins, and its own door's leaves if they swing in
     * behind the doorway ({@link BotiDoorOverlay}), into BOTI's own feature dispatcher (vanilla's prepared frame is in use
     * for the main view). They are drawn later inside that view's doorway stencil, after the far side's blocks.
     */
    private static void prepareFeatures(List<DoorDraw> draws, LevelRenderState levelState) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        DoorDraw owner = null;
        Collection<BlockEntity> blockEntities = List.of();
        Collection<Entity> entities = List.of();
        @Nullable BlockEntity overlayDoor = null;
        for (DoorDraw draw : draws) {
            if (draw.hidden || draw.fallback || draw.view == null || draw.model == null || draw.mesh == null) {
                continue;
            }
            blockEntities = ArtronClientConfig.RENDER_BLOCK_ENTITIES.getAsBoolean() ? BotiBlockEntities.get(draw.view, mc.level) : List.of();
            if (draw.arrival) {
                // Out of a TARDIS, the snapshot holds what is in front of its doorway but not the police box itself, which
                // stands behind it; until the box's own chunk is here, a stand-in is drawn so looking back shows it.
                BlockEntity box = BotiBlockEntities.arrivalExterior(draw.view, mc.level);
                if (box != null) {
                    List<BlockEntity> withBox = new ArrayList<>(blockEntities);
                    withBox.add(box);
                    blockEntities = withBox;
                }
            }
            entities = BotiEntities.get(draw.key);
            overlayDoor = draw.endpoint instanceof BlockEntity be && mc.getBlockEntityRenderDispatcher().getRenderer(be) instanceof BotiDoorOverlay<?> ? be : null;
            if (!blockEntities.isEmpty() || !entities.isEmpty() || overlayDoor != null) {
                owner = draw;
                break;
            }
        }
        if (owner == null) {
            return;
        }
        if (featureDispatcher == null) {
            renderBuffers = new RenderBuffers(0);
            featureDispatcher = new FeatureRenderDispatcher(renderBuffers, mc.getModelManager(), mc.getAtlasManager(), mc.font, mc.gameRenderer.gameRenderState());
        }
        PortalSnapshot snapshot = owner.view.snapshot();
        SnapshotBox box = snapshot.box();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(owner.model);

        BlockEntityRenderDispatcher beDispatcher = mc.getBlockEntityRenderDispatcher();
        for (BlockEntity be : blockEntities) {
            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = beDispatcher.getRenderer(be);
            if (renderer == null) {
                continue;
            }
            BlockEntityRenderState state = renderer.createRenderState();
            try {
                renderer.extractRenderState(be, state, partialTick, owner.farCamera, null);
            } catch (RuntimeException ex) {
                continue;
            }
            BlockPos pos = be.getBlockPos();
            // A block entity behind the far doorway (the arrival's stand-in police box) is lit as just in front of it.
            BlockPos lightPos = box.containsWorld(pos.getX(), pos.getY(), pos.getZ()) ? pos : pos.relative(snapshot.geometry().farFacing());
            state.lightCoords = lightAt(snapshot, owner, lightPos.getX(), lightPos.getY(), lightPos.getZ());
            poseStack.pushPose();
            poseStack.translate(pos.getX() - box.origin().getX(), pos.getY() - box.origin().getY(), pos.getZ() - box.origin().getZ());
            beDispatcher.submit(state, poseStack, SUBMITS, levelState.cameraRenderState);
            poseStack.popPose();
        }

        EntityRenderDispatcher entityDispatcher = mc.getEntityRenderDispatcher();
        for (Entity entity : entities) {
            EntityRenderState state;
            try {
                state = entityDispatcher.extractEntity(entity, partialTick);
            } catch (RuntimeException ex) {
                if (LOGGED_TYPES.add(entity.getType())) {
                    ArtronIndustries.LOGGER.warn("Cannot draw BOTI stand-in {}", entity.getType(), ex);
                }
                continue;
            }
            // Light and shadows come from the snapshot, not from the level the stand-in is attached to.
            state.lightCoords = lightAt(snapshot, owner, Mth.floor(state.x), Mth.floor(state.y + 0.5), Mth.floor(state.z));
            state.shadowRadius = 0.0F;
            state.shadowPieces.clear();
            entityDispatcher.submit(state, levelState.cameraRenderState, state.x - box.origin().getX(), state.y - box.origin().getY(),
                    state.z - box.origin().getZ(), poseStack, SUBMITS);
        }

        if (overlayDoor != null) {
            submitDoorOverlay(overlayDoor, beDispatcher, partialTick, levelState);
        }
        featureFrame = featureDispatcher.prepareFrame(SUBMITS);
        owner.ownsBlockEntities = true;
        if (++featureLogCounter % 200 == 0) {
            ArtronIndustries.LOGGER.debug("BOTI features for {}: {} block entities, {} entities", owner.key, blockEntities.size(), entities.size());
        }
    }

    /** The near-side door's own leaves, in near-side camera space like the far side's blocks are drawn. */
    @SuppressWarnings("unchecked")
    private static void submitDoorOverlay(BlockEntity door, BlockEntityRenderDispatcher beDispatcher, float partialTick, LevelRenderState levelState) {
        BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = beDispatcher.getRenderer(door);
        if (!(renderer instanceof BotiDoorOverlay<?> overlay)) {
            return;
        }
        Vec3 camera = levelState.cameraRenderState.pos;
        BlockEntityRenderState state = renderer.createRenderState();
        renderer.extractRenderState(door, state, partialTick, camera, null);
        BlockPos pos = door.getBlockPos();
        PoseStack poseStack = new PoseStack();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        ((BotiDoorOverlay<BlockEntityRenderState>) overlay).submitBehindDoorway(state, poseStack, SUBMITS);
    }

    private static int lightAt(PortalSnapshot snapshot, DoorDraw owner, int x, int y, int z) {
        SnapshotBox box = snapshot.box();
        if (!box.containsWorld(x, y, z)) {
            return LightCoordsUtil.pack(0, owner.skyToBlock >= 0.0F ? 0 : 15);
        }
        byte light = snapshot.light()[box.indexOfWorld(x, y, z)];
        int block = PortalSnapshot.blockLight(light);
        int sky = PortalSnapshot.skyLight(light);
        if (owner.skyToBlock >= 0.0F) {
            block = Math.max(block, Math.round(sky * owner.skyToBlock));
            sky = 0;
        }
        return LightCoordsUtil.pack(block, sky);
    }

    // --- Render (inside vanilla's main render pass) --------------------------------------------------------------------

    private static void render(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        List<DoorDraw> draws = event.getLevelRenderState().getRenderData(DRAWS);
        if (draws == null || draws.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        RenderPass pass = event.getRenderPass();
        Matrix4fc view = event.getModelViewMatrix();
        Minecraft mc = Minecraft.getInstance();
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer sequential = indices.getBuffer();

        int drawn = 0;
        for (DoorDraw draw : draws) {
            if (draw.hidden) {
                continue;
            }
            drawn++;
            pass.pushDebugGroup(() -> "BOTI " + draw.key);
            if (draw.debugFloating) {
                drawMesh(pass, draw, view, sequential, indices.type(), mc, true);
                if (draw.ownsBlockEntities && featureFrame != null) {
                    featureFrame.executeSolid(pass);
                    featureFrame.executeTranslucent(pass);
                }
                pass.popDebugGroup();
                continue;
            }
            if (quadBuffer == null) {
                pass.popDebugGroup();
                continue;
            }
            GpuBufferSlice identity = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view), new Vector4f(1.0F), new Vector3f(), new Matrix4f());
            if (draw.fallback) {
                drawQuad(pass, BotiPipelines.FALLBACK_DOORWAY, identity, sequential, indices.type(), draw.firstQuad);
                pass.popDebugGroup();
                continue;
            }
            // 1. Mark the visible doorway in the stencil bit.
            drawQuad(pass, BotiPipelines.MARK_DOORWAY, identity, sequential, indices.type(), draw.firstQuad);
            // 2+3. Inside it: depth back to the far plane, backdrop colour.
            drawQuad(pass, BotiPipelines.BACKDROP, identity, sequential, indices.type(), draw.firstQuad + 1);
            // 3b. Looking out: the outside's sky, sun, moon, stars and clouds in front of the backdrop, behind everything else.
            if (draw.sky != null) {
                BotiSky.render(pass, view, draw);
            }
            // 4. The far side: block mesh, then block entities and entities through the stencil pipeline modifier.
            if (draw.fog != null) {
                pass.setUniform("Fog", draw.fog.slice());
            }
            drawMesh(pass, draw, view, sequential, indices.type(), mc, false);
            if (draw.ownsBlockEntities && featureFrame != null) {
                RenderSystem.pushPipelineModifier(BotiPipelines.INSIDE_DOORWAY);
                try {
                    featureFrame.executeSolid(pass);
                    featureFrame.executeTranslucent(pass);
                } finally {
                    RenderSystem.popPipelineModifier();
                }
            }
            GpuBufferSlice vanillaFog = RenderSystem.getShaderFog();
            if (draw.fog != null && vanillaFog != null) {
                pass.setUniform("Fog", vanillaFog);
            }
            // 5. Seal: doorway depth back in, stencil bit cleared.
            drawQuad(pass, BotiPipelines.SEAL_DOORWAY, identity, sequential, indices.type(), draw.firstQuad + 2);
            pass.popDebugGroup();
        }
        lastDrawCount = drawn;
        lastDrawMs = (System.nanoTime() - start) / 1.0E6;
    }

    private static void drawQuad(RenderPass pass, RenderPipeline pipeline, GpuBufferSlice transforms, GpuBuffer indexBuffer,
                                 com.mojang.renderpearl.api.pipeline.IndexType indexType, int quad) {
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        pass.setUniform("DynamicTransforms", transforms);
        pass.setVertexBuffer(0, quadBuffer.slice());
        pass.setIndexBuffer(indexBuffer, indexType);
        pass.drawIndexed(6, 1, 0, quad * 4, 0);
    }

    private static void drawMesh(RenderPass pass, DoorDraw draw, Matrix4fc view, GpuBuffer sequential,
                                 com.mojang.renderpearl.api.pipeline.IndexType sequentialType, Minecraft mc, boolean debug) {
        BotiMesh mesh = draw.mesh;
        if (mesh == null || mesh.isEmpty() || draw.model == null) {
            return;
        }
        // The block shader reads the clip plane from ModelOffset, which it has no other use for.
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(view), new Vector4f(1.0F), new Vector3f(draw.clip), draw.model);
        pass.setUniform("Sampler0", mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true));
        pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        for (ChunkSectionLayer layer : LAYERS) {
            BotiMesh.Layer data = mesh.layer(layer);
            if (data == null || data.indexCount() == 0) {
                continue;
            }
            RenderPipeline pipeline = switch (layer) {
                case SOLID -> debug ? BotiPipelines.DEBUG_BLOCK_SOLID : BotiPipelines.BLOCK_SOLID;
                case CUTOUT -> debug ? BotiPipelines.DEBUG_BLOCK_CUTOUT : BotiPipelines.BLOCK_CUTOUT;
                case TRANSLUCENT -> debug ? BotiPipelines.DEBUG_BLOCK_TRANSLUCENT : BotiPipelines.BLOCK_TRANSLUCENT;
            };
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, data.vertices().slice());
            if (data.sortedIndices() != null) {
                pass.setIndexBuffer(data.sortedIndices(), data.sortedIndexType());
            } else {
                pass.setIndexBuffer(sequential, sequentialType);
            }
            pass.drawIndexed(data.indexCount(), 1, 0, 0, 0);
        }
    }

    private static void endFrame() {
        BotiSky.endFrame();
        if (featureFrame != null) {
            featureFrame.close();
            featureFrame = null;
        }
        if (renderBuffers != null) {
            renderBuffers.endFrame();
        }
    }

    /** Last frame's BOTI draw time on the CPU (command recording), in milliseconds. */
    public static double lastDrawMs() {
        return lastDrawMs;
    }

    public static int lastDrawCount() {
        return lastDrawCount;
    }
}
