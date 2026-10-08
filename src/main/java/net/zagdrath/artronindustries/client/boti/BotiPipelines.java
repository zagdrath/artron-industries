/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.util.Optional;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.StencilManager;
import net.neoforged.neoforge.client.event.ConfigureMainRenderTargetEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.pipeline.PipelineModifier;
import net.neoforged.neoforge.client.pipeline.RegisterPipelineModifiersEvent;
import net.neoforged.neoforge.client.stencil.StencilOperation;
import net.neoforged.neoforge.client.stencil.StencilPerFaceTest;
import net.neoforged.neoforge.client.stencil.StencilTest;
import net.zagdrath.artronindustries.ArtronIndustries;

/**
 * Render pipelines for doorway rendering. The doorway is marked in one reserved bit of the main target's stencil buffer
 * (NeoForge's {@code ConfigureMainRenderTargetEvent} adds the stencil attachment; {@link StencilManager} hands out the
 * bit), everything seen through it is drawn with "stencil bit set" tests, and the doorway is sealed (bit cleared, real
 * doorway depth written) afterwards.
 */
public final class BotiPipelines {
    /** Reserved stencil bit mask, or 0 if none was available (then only the fallback doorway is drawn). */
    public static final int STENCIL_MASK;

    static {
        int bit = StencilManager.reserveBit();
        STENCIL_MASK = bit < 0 ? 0 : 1 << bit;
    }

    private static final StencilTest MARK = stencil(StencilOperation.REPLACE, CompareOp.ALWAYS_PASS, STENCIL_MASK);
    /** Passes only inside the marked doorway; leaves the stencil untouched. */
    public static final StencilTest INSIDE = stencil(StencilOperation.KEEP, CompareOp.EQUAL, 0);
    private static final StencilTest SEAL = stencil(StencilOperation.ZERO, CompareOp.EQUAL, STENCIL_MASK);

    private static final ColorTargetState NO_COLOR = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE);
    private static final DepthStencilState REVERSE_Z_DEFAULT = DepthStencilState.DEFAULT;

    private static final RenderPipeline.Snippet PORTAL_SNIPPET = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader(id("core/boti_portal"))
            .withFragmentShader(id("core/boti_portal"))
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withCull(false)
            .buildSnippet();

    private static final RenderPipeline.Snippet BLOCK_SNIPPET = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.GLOBALS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withVertexShader(id("core/boti_block"))
            .withFragmentShader(id("core/boti_block"))
            .withVertexBinding(0, DefaultVertexFormat.BLOCK)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .buildSnippet();

    /** Step 1: mark visible doorway pixels in the stencil bit (depth-tested so anything in front still hides the doorway). */
    public static final RenderPipeline MARK_DOORWAY = RenderPipeline.builder(PORTAL_SNIPPET)
            .withLocation(id("pipeline/boti_mark"))
            .withColorTargetState(NO_COLOR)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 0.0F, 0.0F, MARK))
            .build();

    /** Steps 2+3: inside the doorway, reset depth to the far plane and paint the backdrop colour. */
    public static final RenderPipeline BACKDROP = RenderPipeline.builder(PORTAL_SNIPPET)
            .withLocation(id("pipeline/boti_backdrop"))
            .withShaderDefine("FAR_DEPTH")
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true, 0.0F, 0.0F, INSIDE))
            .build();

    /** Step 5: write the real doorway depth back and clear the stencil bit, so later geometry sorts against the doorway. */
    public static final RenderPipeline SEAL_DOORWAY = RenderPipeline.builder(PORTAL_SNIPPET)
            .withLocation(id("pipeline/boti_seal"))
            .withColorTargetState(NO_COLOR)
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true, 0.0F, 0.0F, SEAL))
            .build();

    /** Fallback doorway (render mode DISABLED, shader packs, no stencil bit): an opaque dark shimmering surface. */
    public static final RenderPipeline FALLBACK_DOORWAY = RenderPipeline.builder(PORTAL_SNIPPET)
            .withLocation(id("pipeline/boti_fallback"))
            .withShaderDefine("SHIMMER")
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(REVERSE_Z_DEFAULT)
            .build();

    public static final RenderPipeline BLOCK_SOLID = blocks("solid", Optional.empty(), -1.0F, INSIDE);
    public static final RenderPipeline BLOCK_CUTOUT = blocks("cutout", Optional.empty(), 0.5F, INSIDE);
    public static final RenderPipeline BLOCK_TRANSLUCENT = blocks("translucent", Optional.of(BlendFunction.TRANSLUCENT), 0.1F, INSIDE);

    /** Unmasked variants used by the floating debug view ({@code /artronclient boti debug}). */
    public static final RenderPipeline DEBUG_BLOCK_SOLID = blocks("debug_solid", Optional.empty(), -1.0F, null);
    public static final RenderPipeline DEBUG_BLOCK_CUTOUT = blocks("debug_cutout", Optional.empty(), 0.5F, null);
    public static final RenderPipeline DEBUG_BLOCK_TRANSLUCENT = blocks("debug_translucent", Optional.of(BlendFunction.TRANSLUCENT), 0.1F, null);

    /**
     * Pipeline modifier applied while drawing block entities and entities seen through a doorway: every pipeline they use
     * gets the {@link #INSIDE} stencil test added, so they are clipped to the doorway exactly like the block mesh.
     */
    public static final ResourceKey<PipelineModifier> INSIDE_DOORWAY = ResourceKey.create(PipelineModifier.MODIFIERS_KEY, id("inside_doorway"));

    private BotiPipelines() {}

    public static boolean stencilAvailable() {
        return STENCIL_MASK != 0;
    }

    public static void init(IEventBus modEventBus) {
        // The stencil attachment is always requested: switching renderMode at runtime must not need a restart to work.
        modEventBus.addListener(ConfigureMainRenderTargetEvent.class, ConfigureMainRenderTargetEvent::enableStencil);
        modEventBus.addListener(RegisterRenderPipelinesEvent.class, e -> {
            for (RenderPipeline p : new RenderPipeline[]{MARK_DOORWAY, BACKDROP, SEAL_DOORWAY, FALLBACK_DOORWAY, BLOCK_SOLID, BLOCK_CUTOUT,
                    BLOCK_TRANSLUCENT, DEBUG_BLOCK_SOLID, DEBUG_BLOCK_CUTOUT, DEBUG_BLOCK_TRANSLUCENT}) {
                e.registerPipeline(p);
            }
        });
        modEventBus.addListener(RegisterPipelineModifiersEvent.class, e -> e.register(INSIDE_DOORWAY, BotiPipelines::insideDoorway));
    }

    private static RenderPipeline insideDoorway(RenderPipeline pipeline, Identifier name) {
        DepthStencilState state = pipeline.getDepthStencilState();
        if (state == null) {
            return pipeline;
        }
        return pipeline.toBuilder()
                .withLocation(name)
                .withDepthStencilState(new DepthStencilState(state.depthTest(), state.writeDepth(), state.depthBiasScaleFactor(),
                        state.depthBiasConstant(), INSIDE))
                .build();
    }

    private static RenderPipeline blocks(String name, Optional<BlendFunction> blend, float alphaCutout, StencilTest stencil) {
        RenderPipeline.Builder builder = RenderPipeline.builder(BLOCK_SNIPPET)
                .withLocation(id("pipeline/boti_block_" + name))
                .withColorTargetState(new ColorTargetState(blend, GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true, 0.0F, 0.0F, stencil));
        if (alphaCutout > 0.0F) {
            builder.withShaderDefine("ALPHA_CUTOUT", alphaCutout);
        }
        return builder.build();
    }

    private static StencilTest stencil(StencilOperation pass, CompareOp compare, int writeMask) {
        StencilPerFaceTest face = new StencilPerFaceTest(StencilOperation.KEEP, StencilOperation.KEEP, pass, compare);
        return new StencilTest(face, STENCIL_MASK, writeMask, STENCIL_MASK);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, path);
    }
}
