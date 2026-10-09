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

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
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
import net.zagdrath.artronindustries.boti.SnapshotSections;

/**
 * Tessellates a snapshot with the vanilla block and fluid renderers (so ambient occlusion, biome tint, fluids and model
 * quirks match the real terrain) into one mesh per {@link ChunkSectionLayer} for each requested section
 * ({@link SnapshotSections}), so a change only rebuilds the sections it touches. Vertices are box-local. It also builds the
 * ground skirt around a view looking out ({@link #buildSkirt}). Safe to run off the render thread: it only reads immutable
 * getters and immutable baked models.
 */
public final class BotiMeshBuilder {
    /** The layers of one section. A built section with no layers is empty, and replaces whatever was there. */
    public static final class SectionResult {
        final Map<ChunkSectionLayer, MeshData> meshes = new EnumMap<>(ChunkSectionLayer.class);
        final Map<ChunkSectionLayer, ByteBufferBuilder> buffers = new EnumMap<>(ChunkSectionLayer.class);

        void close() {
            this.meshes.values().forEach(MeshData::close);
            this.meshes.clear();
            this.buffers.values().forEach(ByteBufferBuilder::close);
            this.buffers.clear();
        }
    }

    /** Output of one rebuild. Owns native memory until {@link #close()}. */
    public static final class Result implements AutoCloseable {
        final SnapshotSections sections;
        /** Every section was built (a new snapshot): the result replaces the old mesh instead of patching it. */
        final boolean full;
        final Int2ObjectMap<SectionResult> built = new Int2ObjectOpenHashMap<>();
        long nanos;

        Result(SnapshotSections sections, boolean full) {
            this.sections = sections;
            this.full = full;
        }

        @Override
        public void close() {
            this.built.values().forEach(SectionResult::close);
            this.built.clear();
        }
    }

    private BotiMeshBuilder() {}

    /** Builds the given sections of the snapshot behind {@code level}, or every section when {@code sections} is null. */
    public static Result build(SnapshotBlockGetter level, @Nullable IntSet sections, boolean ambientOcclusion, boolean cutoutLeaves,
                               BlockStateModelSet blockModels, FluidStateModelSet fluidModels, BlockColors blockColors) {
        long start = System.nanoTime();
        SnapshotBox box = level.box();
        SnapshotSections grid = SnapshotSections.of(box);
        Result result = new Result(grid, sections == null);
        BlockPos origin = box.origin();
        Tessellator tessellator = new Tessellator(level, ambientOcclusion, cutoutLeaves, blockModels, fluidModels, blockColors);
        BlockModelLighter.enableCaching();
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            IntIterator todo = sections != null ? sections.iterator() : null;
            for (int next = 0; todo != null ? todo.hasNext() : next < grid.count(); next++) {
                int section = todo != null ? todo.nextInt() : next;
                if (section < 0 || section >= grid.count()) {
                    continue;
                }
                tessellator.begin();
                for (int ly = grid.minY(section); ly < grid.maxY(section); ly++) {
                    for (int lz = grid.minZ(section); lz < grid.maxZ(section); lz++) {
                        for (int lx = grid.minX(section); lx < grid.maxX(section); lx++) {
                            tessellator.block(pos.set(origin.getX() + lx, origin.getY() + ly, origin.getZ() + lz), lx, ly, lz);
                        }
                    }
                }
                result.built.put(section, tessellator.end());
            }
        } catch (RuntimeException | Error e) {
            tessellator.abandon();
            result.close();
            throw e;
        } finally {
            BlockModelLighter.clearCache();
        }
        result.nanos = System.nanoTime() - start;
        return result;
    }

    /**
     * Builds the ground skirt around a snapshot ({@link SkirtBlockGetter}) over {@code skirtBox}, in sections of
     * {@code sectionSize}: for every column of the skirt box outside the snapshot box, its ground block, and the blocks
     * under it down to its lowest neighbour's ground so steps between columns have sides. Vertices are local to
     * {@code skirtBox}.
     */
    public static Result buildSkirt(SkirtBlockGetter level, SnapshotBox skirtBox, int sectionSize, boolean ambientOcclusion, boolean cutoutLeaves,
                                    BlockStateModelSet blockModels, FluidStateModelSet fluidModels, BlockColors blockColors) {
        long start = System.nanoTime();
        SnapshotBox box = level.box();
        SnapshotSections grid = SnapshotSections.of(skirtBox, sectionSize);
        Result result = new Result(grid, true);
        BlockPos origin = skirtBox.origin();
        Tessellator tessellator = new Tessellator(level, ambientOcclusion, cutoutLeaves, blockModels, fluidModels, blockColors);
        BlockModelLighter.enableCaching();
        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int section = 0; section < grid.count(); section++) {
                tessellator.begin();
                int minY = origin.getY() + grid.minY(section);
                int maxY = origin.getY() + grid.maxY(section) - 1;
                for (int lz = grid.minZ(section); lz < grid.maxZ(section); lz++) {
                    for (int lx = grid.minX(section); lx < grid.maxX(section); lx++) {
                        int x = origin.getX() + lx;
                        int z = origin.getZ() + lz;
                        if (x >= box.origin().getX() && x <= box.maxX() && z >= box.origin().getZ() && z <= box.maxZ()) {
                            continue; // the snapshot's own blocks
                        }
                        int top = level.groundAt(x, z);
                        if (!SkirtBlockGetter.hasGround(top)) {
                            continue;
                        }
                        int bottom = top;
                        for (int[] n : NEIGHBOURS) {
                            int g = level.groundAt(x + n[0], z + n[1]);
                            bottom = Math.min(bottom, SkirtBlockGetter.hasGround(g) ? g + 1 : top);
                        }
                        for (int y = Math.max(bottom, minY); y <= Math.min(top, maxY); y++) {
                            tessellator.block(pos.set(x, y, z), lx, y - origin.getY(), lz);
                        }
                    }
                }
                result.built.put(section, tessellator.end());
            }
        } catch (RuntimeException | Error e) {
            tessellator.abandon();
            result.close();
            throw e;
        } finally {
            BlockModelLighter.clearCache();
        }
        result.nanos = System.nanoTime() - start;
        return result;
    }

    private static final int[][] NEIGHBOURS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Tessellates blocks one section at a time into per-layer buffers. */
    private static final class Tessellator {
        private final BlockAndTintGetter level;
        private final boolean cutoutLeaves;
        private final BlockStateModelSet blockModels;
        private final FluidStateModelSet fluidModels;
        private final ModelBlockRenderer blockRenderer;
        private final FluidRenderer fluidRenderer;
        private final Map<ChunkSectionLayer, BufferBuilder> builders = new EnumMap<>(ChunkSectionLayer.class);
        private final OffsetVertexConsumer fluidOffset = new OffsetVertexConsumer();
        private final BlockQuadOutput quadOutput;
        private final BlockQuadOutput opaqueOutput;
        private final FluidRenderer.Output fluidOutput;
        private @Nullable SectionResult current;

        Tessellator(BlockAndTintGetter level, boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet blockModels,
                    FluidStateModelSet fluidModels, BlockColors blockColors) {
            this.level = level;
            this.cutoutLeaves = cutoutLeaves;
            this.blockModels = blockModels;
            this.fluidModels = fluidModels;
            this.blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, blockColors);
            this.fluidRenderer = new FluidRenderer(fluidModels);
            this.quadOutput = (x, y, z, quad, instance) -> this.layer(quad.materialInfo().layer()).putBlockBakedQuad(x, y, z, quad, instance);
            this.opaqueOutput = (x, y, z, quad, instance) -> this.layer(ChunkSectionLayer.SOLID).putBlockBakedQuad(x, y, z, quad, instance);
            this.fluidOutput = layer -> this.fluidOffset.wrap(this.layer(layer));
        }

        void begin() {
            this.current = new SectionResult();
            this.builders.clear();
        }

        /** Tessellates the block at {@code pos} with its vertices at mesh-local {@code (lx, ly, lz)}. */
        void block(BlockPos pos, int lx, int ly, int lz) {
            BlockState state = this.level.getBlockState(pos);
            if (state.isAir()) {
                return;
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                // The fluid renderer writes section-relative positions (pos & 15); shift them to mesh-local.
                this.fluidOffset.set(lx - (pos.getX() & 15), ly - (pos.getY() & 15), lz - (pos.getZ() & 15));
                var custom = this.fluidModels.get(fluid).customRenderer();
                if (custom == null || !custom.renderFluid(this.fluidRenderer, fluid, this.level, pos, this.fluidOutput, state)) {
                    this.fluidRenderer.tesselate(this.level, pos, this.fluidOutput, state, fluid);
                }
            }
            if (state.getRenderShape() == RenderShape.MODEL) {
                this.blockRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(this.cutoutLeaves, state) ? this.opaqueOutput : this.quadOutput,
                        lx, ly, lz, this.level, pos, state, this.blockModels.get(state), state.getSeed(pos));
            }
        }

        SectionResult end() {
            SectionResult section = this.current;
            this.current = null;
            for (Map.Entry<ChunkSectionLayer, BufferBuilder> e : this.builders.entrySet()) {
                MeshData mesh = e.getValue().build();
                if (mesh != null) {
                    section.meshes.put(e.getKey(), mesh);
                }
            }
            return section;
        }

        /** Frees the section being built after a failure (finished ones belong to the result). */
        void abandon() {
            if (this.current != null) {
                this.current.close();
                this.current = null;
            }
        }

        private BufferBuilder layer(ChunkSectionLayer layer) {
            BufferBuilder builder = this.builders.get(layer);
            if (builder == null) {
                ByteBufferBuilder buffer = new ByteBufferBuilder(16 * 1024);
                this.current.buffers.put(layer, buffer);
                builder = new BufferBuilder(buffer, PrimitiveTopology.QUADS, layer.vertexFormat());
                this.builders.put(layer, builder);
            }
            return builder;
        }
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
