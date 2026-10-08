/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.zagdrath.artronindustries.boti.SnapshotBox;

/**
 * Tessellates a snapshot with the vanilla block and fluid renderers (so ambient occlusion, biome tint, fluids and model
 * quirks match the real terrain) into one mesh per {@link ChunkSectionLayer}. Vertices are box-local. Safe to run off the
 * render thread: it only reads an immutable {@link SnapshotBlockGetter} and immutable baked models.
 */
public final class BotiMeshBuilder {
    /** Output of one rebuild. Owns native memory until {@link #close()}. */
    public static final class Result implements AutoCloseable {
        final Map<ChunkSectionLayer, MeshData> meshes = new EnumMap<>(ChunkSectionLayer.class);
        final Map<ChunkSectionLayer, ByteBufferBuilder> buffers = new EnumMap<>(ChunkSectionLayer.class);
        long nanos;

        public @Nullable MeshData mesh(ChunkSectionLayer layer) {
            return this.meshes.get(layer);
        }

        @Override
        public void close() {
            this.meshes.values().forEach(MeshData::close);
            this.meshes.clear();
            this.buffers.values().forEach(ByteBufferBuilder::close);
            this.buffers.clear();
        }
    }

    private BotiMeshBuilder() {}

    public static Result build(SnapshotBlockGetter level, boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet blockModels,
                               FluidStateModelSet fluidModels, BlockColors blockColors) {
        long start = System.nanoTime();
        Result result = new Result();
        Map<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
        SnapshotBox box = level.box();
        BlockPos origin = box.origin();

        BlockQuadOutput quadOutput = (x, y, z, quad, instance) ->
                layer(builders, result, quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
        BlockQuadOutput opaqueOutput = (x, y, z, quad, instance) ->
                layer(builders, result, ChunkSectionLayer.SOLID).putBlockBakedQuad(x, y, z, quad, instance);
        OffsetVertexConsumer fluidOffset = new OffsetVertexConsumer();
        FluidRenderer.Output fluidOutput = layer -> fluidOffset.wrap(layer(builders, result, layer));

        BlockModelLighter.enableCaching();
        try {
            ModelBlockRenderer blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, blockColors);
            FluidRenderer fluidRenderer = new FluidRenderer(fluidModels);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int ly = 0; ly < box.sizeY(); ly++) {
                for (int lz = 0; lz < box.sizeZ(); lz++) {
                    for (int lx = 0; lx < box.sizeX(); lx++) {
                        pos.set(origin.getX() + lx, origin.getY() + ly, origin.getZ() + lz);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            // The fluid renderer writes section-relative positions (pos & 15); shift them to box-local.
                            fluidOffset.set(lx - (pos.getX() & 15), ly - (pos.getY() & 15), lz - (pos.getZ() & 15));
                            var custom = fluidModels.get(fluid).customRenderer();
                            if (custom == null || !custom.renderFluid(fluidRenderer, fluid, level, pos, fluidOutput, state)) {
                                fluidRenderer.tesselate(level, pos, fluidOutput, state, fluid);
                            }
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            blockRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, state) ? opaqueOutput : quadOutput,
                                    lx, ly, lz, level, pos, state, blockModels.get(state), state.getSeed(pos));
                        }
                    }
                }
            }
        } finally {
            BlockModelLighter.clearCache();
        }

        for (Map.Entry<ChunkSectionLayer, BufferBuilder> e : builders.entrySet()) {
            MeshData mesh = e.getValue().build();
            if (mesh != null) {
                result.meshes.put(e.getKey(), mesh);
            }
        }
        result.nanos = System.nanoTime() - start;
        return result;
    }

    private static BufferBuilder layer(Map<ChunkSectionLayer, BufferBuilder> builders, Result result, ChunkSectionLayer layer) {
        BufferBuilder builder = builders.get(layer);
        if (builder == null) {
            ByteBufferBuilder buffer = new ByteBufferBuilder(64 * 1024);
            result.buffers.put(layer, buffer);
            builder = new BufferBuilder(buffer, PrimitiveTopology.QUADS, layer.vertexFormat());
            builders.put(layer, builder);
        }
        return builder;
    }

    /** Shifts vertex positions written by the fluid renderer. */
    private static final class OffsetVertexConsumer implements VertexConsumer {
        private VertexConsumer delegate;
        private float dx;
        private float dy;
        private float dz;

        void set(float dx, float dy, float dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        VertexConsumer wrap(VertexConsumer delegate) {
            this.delegate = delegate;
            return this;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(x + this.dx, y + this.dy, z + this.dz);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            this.delegate.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            this.delegate.setUv3(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            this.delegate.setLineWidth(width);
            return this;
        }
    }
}
