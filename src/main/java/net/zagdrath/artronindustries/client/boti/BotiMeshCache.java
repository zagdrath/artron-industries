/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.client.ArtronClientConfig;

/**
 * Owns the GPU mesh of every cached view and rebuilds it when the view changes. Rebuilds run on the background executor
 * from an immutable copy of the snapshot; at most {@code boti.maxRebuildsPerFrame} start per frame and the finished result
 * is uploaded on the render thread. Meshes are never rebuilt per frame.
 */
public final class BotiMeshCache {
    private static final class Entry {
        @Nullable BotiMesh mesh;
        @Nullable CompletableFuture<BotiMeshBuilder.Result> pending;
        int pendingRevision = -1;
        int builtRevision = -1;
        float pendingSkyToBlock;
        float builtSkyToBlock = Float.NaN;
    }

    private static final Map<BotiClientCache.View, Entry> ENTRIES = new IdentityHashMap<>();
    private static int rebuildsThisFrame;
    private static double lastRebuildMs;

    private BotiMeshCache() {}

    /** Resets the per-frame rebuild budget. */
    static void beginFrame() {
        rebuildsThisFrame = 0;
    }

    /**
     * Returns the newest uploaded mesh for {@code view}, uploading a finished rebuild or starting a new one as needed.
     * Must be called on the render thread outside any render pass.
     *
     * @param skyToBlock sky-light folding factor for the mesher (see {@link SnapshotBlockGetter}), quantised by the caller
     */
    static @Nullable BotiMesh prepare(BotiClientCache.View view, float skyToBlock, Vector3f boxLocalCamera) {
        Entry entry = ENTRIES.computeIfAbsent(view, v -> new Entry());
        if (entry.pending != null && entry.pending.isDone()) {
            BotiMeshBuilder.Result result = entry.pending.getNow(null);
            entry.pending = null;
            if (result != null) {
                try {
                    if (entry.mesh != null) {
                        entry.mesh.close();
                    }
                    entry.mesh = BotiMesh.upload(result, boxLocalCamera);
                    entry.builtRevision = entry.pendingRevision;
                    entry.builtSkyToBlock = entry.pendingSkyToBlock;
                    lastRebuildMs = result.nanos / 1.0E6;
                    String msg = "BOTI mesh {} rebuilt in {} ms";
                    if (ArtronClientConfig.DEBUG_LOGGING.getAsBoolean()) {
                        ArtronIndustries.LOGGER.info(msg, view.snapshot().key(), String.format("%.2f", lastRebuildMs));
                    } else {
                        ArtronIndustries.LOGGER.debug(msg, view.snapshot().key(), String.format("%.2f", lastRebuildMs));
                    }
                } finally {
                    result.close();
                }
            }
        }
        boolean stale = entry.builtRevision != view.revision() || entry.builtSkyToBlock != skyToBlock;
        boolean alreadyBuilding = entry.pending != null && entry.pendingRevision == view.revision() && entry.pendingSkyToBlock == skyToBlock;
        if (stale && !alreadyBuilding && entry.pending == null && rebuildsThisFrame < ArtronClientConfig.MAX_REBUILDS_PER_FRAME.getAsInt()) {
            rebuildsThisFrame++;
            schedule(entry, view, skyToBlock);
        }
        if (entry.mesh != null) {
            entry.mesh.resortIfNeeded(boxLocalCamera);
        }
        return entry.mesh;
    }

    private static void schedule(Entry entry, BotiClientCache.View view, float skyToBlock) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        PortalSnapshot snapshot = view.snapshot();
        SnapshotBlockGetter getter = new SnapshotBlockGetter(snapshot, SnapshotBlockGetter.biomeRegistry(mc.level), skyToBlock);
        boolean ao = mc.options.ambientOcclusion().get();
        boolean cutoutLeaves = mc.options.cutoutLeaves().get();
        var blockModels = mc.getModelManager().getBlockStateModelSet();
        var fluidModels = mc.getModelManager().getFluidStateModelSet();
        var blockColors = mc.getBlockColors();
        entry.pendingRevision = view.revision();
        entry.pendingSkyToBlock = skyToBlock;
        view.clearMeshDirty();
        entry.pending = CompletableFuture.supplyAsync(
                () -> BotiMeshBuilder.build(getter, ao, cutoutLeaves, blockModels, fluidModels, blockColors), Util.backgroundExecutor())
                .exceptionally(t -> {
                    ArtronIndustries.LOGGER.error("BOTI mesh rebuild failed for {}", snapshot.key(), t);
                    return null;
                });
    }

    /** Frees the mesh of a removed view (render thread). */
    static void release(BotiClientCache.View view) {
        Entry entry = ENTRIES.remove(view);
        if (entry == null) {
            return;
        }
        if (entry.mesh != null) {
            entry.mesh.close();
        }
        if (entry.pending != null) {
            entry.pending.thenAccept(r -> {
                if (r != null) {
                    r.close();
                }
            });
        }
    }

    /** Forces every mesh to rebuild (resource reload, video settings change). */
    public static void invalidateAll() {
        for (Entry entry : ENTRIES.values()) {
            entry.builtRevision = -1;
        }
    }

    public static double lastRebuildMs() {
        return lastRebuildMs;
    }

    public static int meshCount() {
        return ENTRIES.size();
    }
}
