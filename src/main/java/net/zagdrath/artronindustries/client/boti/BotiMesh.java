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

/**
 * Uploaded geometry of one view: a vertex buffer per layer, and for the translucent layer its own index buffer that is
 * re-sorted back-to-front when the viewer moves. Render thread only.
 */
public final class BotiMesh implements AutoCloseable {
    /** Re-sort translucent quads once the (far-side) camera has moved this far since the last sort. */
    private static final float RESORT_DISTANCE_SQR = 0.5F * 0.5F;

    public record Layer(GpuBuffer vertices, int indexCount, @Nullable GpuBuffer sortedIndices, @Nullable IndexType sortedIndexType) {}

    private final Map<ChunkSectionLayer, Layer> layers = new EnumMap<>(ChunkSectionLayer.class);
    private MeshData.@Nullable SortState translucentSort;
    private final Vector3f lastSortPos = new Vector3f(Float.NaN);
    private @Nullable ByteBufferBuilder sortScratch;
    private int maxSequentialIndices;

    /** Uploads a finished rebuild. Must run on the render thread outside any render pass. */
    static BotiMesh upload(BotiMeshBuilder.Result result, Vector3f sortOrigin) {
        BotiMesh mesh = new BotiMesh();
        GpuDevice device = RenderSystem.getDevice();
        for (Map.Entry<ChunkSectionLayer, MeshData> e : result.meshes.entrySet()) {
            ChunkSectionLayer layer = e.getKey();
            MeshData data = e.getValue();
            int indexCount = data.drawState().indexCount();
            GpuBuffer vertices = device.createBuffer(() -> "BOTI " + layer.label() + " vertices", GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
            GpuBuffer indices = null;
            IndexType indexType = null;
            if (layer.translucent()) {
                mesh.sortScratch = new ByteBufferBuilder(indexCount * 4);
                mesh.translucentSort = data.sortQuads(mesh.sortScratch, VertexSorting.byDistance(sortOrigin));
                ByteBuffer sorted = data.indexBuffer();
                if (mesh.translucentSort != null && sorted != null) {
                    indexType = mesh.translucentSort.indexType();
                    indices = device.createBuffer(() -> "BOTI translucent indices", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, sorted);
                    mesh.lastSortPos.set(sortOrigin);
                }
            }
            if (indices == null) {
                mesh.maxSequentialIndices = Math.max(mesh.maxSequentialIndices, indexCount);
            }
            mesh.layers.put(layer, new Layer(vertices, indexCount, indices, indexType));
        }
        return mesh;
    }

    public @Nullable Layer layer(ChunkSectionLayer layer) {
        return this.layers.get(layer);
    }

    public boolean isEmpty() {
        return this.layers.isEmpty();
    }

    /** Index count to request from the shared sequential quad index buffer before the frame's passes start. */
    public int maxSequentialIndices() {
        return this.maxSequentialIndices;
    }

    /** Re-sorts translucent quads for a camera at {@code boxLocalCamera} if it moved enough. Outside render passes only. */
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
