/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.IndexType;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.zagdrath.artronindustries.boti.SnapshotSections;

/**
 * Uploaded geometry of one view, one piece per {@link SnapshotSections section}: a vertex buffer per layer, and for the
 * translucent layer its own index buffer that is re-sorted back-to-front when the viewer moves. A rebuild replaces only
 * the sections it built. Render thread only.
 */
public final class BotiMesh implements AutoCloseable {
    /** Re-sort a section's translucent quads once the (far-side) camera has moved this far since its last sort. */
    private static final float RESORT_DISTANCE_SQR = 0.5F * 0.5F;

    public record Layer(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer sortedIndices, @Nullable IndexType sortedIndexType) {}

    /** One uploaded section. */
    static final class Section implements AutoCloseable {
        private final Map<ChunkSectionLayer, Layer> layers = new EnumMap<>(ChunkSectionLayer.class);
        private MeshData.@Nullable SortState translucentSort;
        private final Vector3f lastSortPos = new Vector3f(Float.NaN);
        private @Nullable ByteBufferBuilder sortScratch;
        private int maxSequentialIndices;

        static Section upload(int index, BotiMeshBuilder.SectionResult result, Vector3f sortOrigin) {
            Section section = new Section();
            GpuDevice device = RenderSystem.getDevice();
            for (Map.Entry<ChunkSectionLayer, MeshData> e : result.meshes.entrySet()) {
                ChunkSectionLayer layer = e.getKey();
                MeshData data = e.getValue();
                int indexCount = data.drawState().indexCount();
                GpuBuffer vertices = device.createBuffer(() -> "BOTI " + layer.label() + " vertices #" + index, GpuBuffer.USAGE_VERTEX,
                        data.vertexBuffer());
                GpuBuffer indices = null;
                IndexType indexType = null;
                if (layer.translucent()) {
                    section.sortScratch = new ByteBufferBuilder(indexCount * 4);
                    section.translucentSort = data.sortQuads(section.sortScratch, VertexSorting.byDistance(sortOrigin));
                    ByteBuffer sorted = data.indexBuffer();
                    if (section.translucentSort != null && sorted != null) {
                        indexType = section.translucentSort.indexType();
                        indices = device.createBuffer(() -> "BOTI translucent indices #" + index, GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                                sorted);
                        section.lastSortPos.set(sortOrigin);
                    }
                }
                if (indices == null) {
                    section.maxSequentialIndices = Math.max(section.maxSequentialIndices, indexCount);
                }
                section.layers.put(layer, new Layer(vertices, indexCount, indices, indexType));
            }
            return section;
        }

        @Nullable Layer layer(ChunkSectionLayer layer) {
            return this.layers.get(layer);
        }

        boolean isEmpty() {
            return this.layers.isEmpty();
        }

        void resortIfNeeded(Vector3f boxLocalCamera) {
            Layer translucent = this.layers.get(ChunkSectionLayer.TRANSLUCENT);
            if (this.translucentSort == null || translucent == null || translucent.sortedIndices() == null || this.sortScratch == null
                    || boxLocalCamera.distanceSquared(this.lastSortPos) < RESORT_DISTANCE_SQR) {
                return;
            }
            this.lastSortPos.set(boxLocalCamera);
            try (ByteBufferBuilder.Result sorted = this.translucentSort.buildSortedIndexBuffer(this.sortScratch, VertexSorting.byDistance(boxLocalCamera))) {
                if (sorted != null) {
                    RenderSystem.getDevice().createCommandEncoder().writeToBuffer(translucent.sortedIndices().slice(), sorted.byteBuffer());
                }
            }
        }

        @Override
        public void close() {
            for (Layer layer : this.layers.values()) {
                layer.vertices().close();
                if (layer.sortedIndices() != null) {
                    layer.sortedIndices().close();
                }
            }
            this.layers.clear();
            if (this.sortScratch != null) {
                this.sortScratch.close();
                this.sortScratch = null;
            }
        }
    }

    private final SnapshotSections grid;
    private final @Nullable Section[] sections;
    private int maxSequentialIndices;

    private BotiMesh(SnapshotSections grid) {
        this.grid = grid;
        this.sections = new Section[grid.count()];
    }

    /** Uploads a full rebuild. Must run on the render thread outside any render pass. */
    static BotiMesh upload(BotiMeshBuilder.Result result, Vector3f sortOrigin) {
        BotiMesh mesh = new BotiMesh(result.sections);
        mesh.patch(result, sortOrigin);
        return mesh;
    }

    /** Replaces the sections {@code result} built (render thread, outside any render pass). */
    void patch(BotiMeshBuilder.Result result, Vector3f sortOrigin) {
        for (var e : result.built.int2ObjectEntrySet()) {
            int index = e.getIntKey();
            Section old = this.sections[index];
            if (old != null) {
                old.close();
            }
            Section section = Section.upload(index, e.getValue(), sortOrigin);
            this.sections[index] = section.isEmpty() ? null : section;
        }
        this.maxSequentialIndices = 0;
        for (Section section : this.sections) {
            if (section != null) {
                this.maxSequentialIndices = Math.max(this.maxSequentialIndices, section.maxSequentialIndices);
            }
        }
    }

    public SnapshotSections grid() {
        return this.grid;
    }

    /** The uploaded section at {@code index}, or null when it holds nothing. */
    @Nullable Section section(int index) {
        return this.sections[index];
    }

    public boolean isEmpty() {
        for (Section section : this.sections) {
            if (section != null) {
                return false;
            }
        }
        return true;
    }

    /** Index count to request from the shared sequential quad index buffer before the frame's passes start. */
    public int maxSequentialIndices() {
        return this.maxSequentialIndices;
    }

    /**
     * Re-sorts the translucent quads of the {@code visible} sections for a camera at {@code boxLocalCamera}, where it
     * moved enough. Sections out of view keep their order until they come back into view. Outside render passes only.
     */
    void resortIfNeeded(Vector3f boxLocalCamera, int[] visible) {
        for (int index : visible) {
            Section section = this.sections[index];
            if (section != null) {
                section.resortIfNeeded(boxLocalCamera);
            }
        }
    }

    @Override
    public void close() {
        for (int i = 0; i < this.sections.length; i++) {
            if (this.sections[i] != null) {
                this.sections[i].close();
                this.sections[i] = null;
            }
        }
    }
}
