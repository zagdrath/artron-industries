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

import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.SnapshotBox;
import net.zagdrath.artronindustries.boti.SnapshotSections;
import net.zagdrath.artronindustries.client.ArtronClientConfig;

/**
 * Owns the GPU mesh of every cached view and rebuilds it when the view changes: a new snapshot rebuilds every section,
 * a delta only the sections around the blocks it changed ({@link SnapshotSections#forEachAffected}). Rebuilds run on the
 * background executor from an immutable copy of the snapshot; at most {@code boti.maxRebuildsPerFrame} start per frame
 * and the finished sections are uploaded on the render thread. Meshes are never rebuilt per frame.
 */
public final class BotiMeshCache {
    private static final class Entry {
        @Nullable BotiMesh mesh;
        @Nullable CompletableFuture<BotiMeshBuilder.Result> pending;
        int pendingRevision = -1;
        int builtRevision = -1;
        /** The next rebuild must build every section (nothing built yet, a failed rebuild, or a settings change). */
        boolean needsFull = true;
    }

    /** A view's ground skirt (see BotiMeshBuilder#buildSkirt), rebuilt for a new snapshot or skirt box only. */
    private static final class SkirtEntry {
        @Nullable BotiMesh mesh;
        @Nullable CompletableFuture<BotiMeshBuilder.Result> pending;
        @Nullable PortalSnapshot builtFor;
        @Nullable SnapshotBox builtBox;
        @Nullable PortalSnapshot pendingFor;
        @Nullable SnapshotBox pendingBox;
    }

    /** Skirt sections are much larger than the snapshot's: they are only culled, never rebuilt one at a time. */
    static final int SKIRT_SECTION_SIZE = 64;

    private static final Map<BotiClientCache.View, Entry> ENTRIES = new IdentityHashMap<>();
    private static final Map<BotiClientCache.View, SkirtEntry> SKIRTS = new IdentityHashMap<>();
    private static int rebuildsThisFrame;
    private static double lastRebuildMs;
    private static int lastRebuildSections;

    private BotiMeshCache() {}

    /** Resets the per-frame rebuild budget. */
    static void beginFrame() {
        rebuildsThisFrame = 0;
    }

    /**
     * Returns the newest uploaded mesh for {@code view}, uploading a finished rebuild or starting a new one as needed. Must
     * be called on the render thread outside any render pass.
     */
    static @Nullable BotiMesh prepare(BotiClientCache.View view, Vector3f boxLocalCamera) {
        Entry entry = ENTRIES.computeIfAbsent(view, v -> new Entry());
        if (entry.pending != null && entry.pending.isDone()) {
            BotiMeshBuilder.Result result = entry.pending.getNow(null);
            entry.pending = null;
            if (result == null) {
                entry.needsFull = true;
            } else {
                try {
                    apply(entry, result, boxLocalCamera);
                    lastRebuildMs = result.nanos / 1.0E6;
                    lastRebuildSections = result.built.size();
                    String msg = "BOTI mesh {} rebuilt {} sections in {} ms";
                    if (ArtronClientConfig.DEBUG_LOGGING.getAsBoolean()) {
                        ArtronIndustries.LOGGER.info(msg, view.snapshot().key(), result.built.size(), String.format("%.2f", lastRebuildMs));
                    } else {
                        ArtronIndustries.LOGGER.debug(msg, view.snapshot().key(), result.built.size(), String.format("%.2f", lastRebuildMs));
                    }
                } finally {
                    result.close();
                }
            }
        }
        boolean stale = entry.needsFull || entry.builtRevision != view.revision();
        if (stale && entry.pending == null && rebuildsThisFrame < ArtronClientConfig.MAX_REBUILDS_PER_FRAME.getAsInt()) {
            schedule(entry, view);
        }
        return entry.mesh;
    }

    /**
     * Returns the newest uploaded ground skirt of {@code view} over {@code skirtBox}, uploading a finished build or starting a
     * new one when the view has a new snapshot or the skirt box changed. Render thread, outside any render pass.
     */
    static @Nullable BotiMesh prepareSkirt(BotiClientCache.View view, SnapshotBox skirtBox, Vector3f skirtLocalCamera) {
        SkirtEntry entry = SKIRTS.computeIfAbsent(view, v -> new SkirtEntry());
        if (entry.pending != null && entry.pending.isDone()) {
            BotiMeshBuilder.Result result = entry.pending.getNow(null);
            entry.pending = null;
            if (result != null) {
                try {
                    if (entry.mesh != null) {
                        entry.mesh.close();
                    }
                    entry.mesh = BotiMesh.upload(result, skirtLocalCamera);
                    entry.builtFor = entry.pendingFor;
                    entry.builtBox = entry.pendingBox;
                    ArtronIndustries.LOGGER.debug("BOTI skirt {} built in {} ms", view.snapshot().key(), String.format("%.2f", result.nanos / 1.0E6));
                } finally {
                    result.close();
                }
            }
        }
        PortalSnapshot snapshot = view.snapshot();
        boolean stale = entry.builtFor != snapshot || !skirtBox.equals(entry.builtBox);
        Minecraft mc = Minecraft.getInstance();
        if (stale && entry.pending == null && mc.level != null && rebuildsThisFrame < ArtronClientConfig.MAX_REBUILDS_PER_FRAME.getAsInt()) {
            rebuildsThisFrame++;
            entry.pendingFor = snapshot;
            entry.pendingBox = skirtBox;
            SkirtBlockGetter getter = new SkirtBlockGetter(new SnapshotBlockGetter(snapshot, SnapshotBlockGetter.biomeRegistry(mc.level)));
            boolean ao = mc.options.ambientOcclusion().get();
            boolean cutoutLeaves = mc.options.cutoutLeaves().get();
            var blockModels = mc.getModelManager().getBlockStateModelSet();
            var fluidModels = mc.getModelManager().getFluidStateModelSet();
            var blockColors = mc.getBlockColors();
            entry.pending = CompletableFuture.supplyAsync(() -> BotiMeshBuilder.buildSkirt(getter, skirtBox, SKIRT_SECTION_SIZE, ao, cutoutLeaves,
                            blockModels, fluidModels, blockColors), Util.backgroundExecutor())
                    .exceptionally(t -> {
                        ArtronIndustries.LOGGER.error("BOTI skirt build failed for {}", snapshot.key(), t);
                        return null;
                    });
        }
        // Until a new snapshot's skirt is ready, the old one is drawn; one built for another box (the render distance
        // changed) would not line up, so there is none until the new one is ready.
        return entry.mesh != null && skirtBox.equals(entry.builtBox) ? entry.mesh : null;
    }

    private static void apply(Entry entry, BotiMeshBuilder.Result result, Vector3f boxLocalCamera) {
        if (result.full) {
            if (entry.mesh != null) {
                entry.mesh.close();
            }
            entry.mesh = BotiMesh.upload(result, boxLocalCamera);
        } else if (entry.mesh != null && entry.mesh.grid().equals(result.sections)) {
            entry.mesh.patch(result, boxLocalCamera);
        } else {
            // Sections of a box the mesh does not have (a new snapshot replaced it while they were building).
            entry.needsFull = true;
            return;
        }
        entry.builtRevision = entry.pendingRevision;
    }

    private static void schedule(Entry entry, BotiClientCache.View view) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        PortalSnapshot snapshot = view.snapshot();
        IntSet dirtyBlocks = view.takeDirtyBlocks();
        IntSet sections = null;
        if (dirtyBlocks != null && !entry.needsFull && entry.mesh != null && entry.mesh.grid().equals(SnapshotSections.of(snapshot.box()))) {
            sections = affectedSections(snapshot.box(), dirtyBlocks);
            if (sections.isEmpty()) {
                entry.builtRevision = view.revision(); // only block entity data changed
                return;
            }
        }
        rebuildsThisFrame++;
        entry.needsFull = false;
        entry.pendingRevision = view.revision();
        SnapshotBlockGetter getter = new SnapshotBlockGetter(snapshot, SnapshotBlockGetter.biomeRegistry(mc.level));
        boolean ao = mc.options.ambientOcclusion().get();
        boolean cutoutLeaves = mc.options.cutoutLeaves().get();
        var blockModels = mc.getModelManager().getBlockStateModelSet();
        var fluidModels = mc.getModelManager().getFluidStateModelSet();
        var blockColors = mc.getBlockColors();
        IntSet toBuild = sections;
        entry.pending = CompletableFuture.supplyAsync(
                () -> BotiMeshBuilder.build(getter, toBuild, ao, cutoutLeaves, blockModels, fluidModels, blockColors), Util.backgroundExecutor())
                .exceptionally(t -> {
                    ArtronIndustries.LOGGER.error("BOTI mesh rebuild failed for {}", snapshot.key(), t);
                    return null;
                });
    }

    private static IntSet affectedSections(SnapshotBox box, IntSet dirtyBlocks) {
        SnapshotSections grid = SnapshotSections.of(box);
        IntSet sections = new IntOpenHashSet();
        for (IntIterator it = dirtyBlocks.iterator(); it.hasNext(); ) {
            int index = it.nextInt();
            grid.forEachAffected(box.localX(index), box.localY(index), box.localZ(index), sections::add);
        }
        return sections;
    }

    /** Frees the mesh of a removed view (render thread). */
    static void release(BotiClientCache.View view) {
        SkirtEntry skirt = SKIRTS.remove(view);
        if (skirt != null) {
            if (skirt.mesh != null) {
                skirt.mesh.close();
            }
            if (skirt.pending != null) {
                skirt.pending.thenAccept(r -> {
                    if (r != null) {
                        r.close();
                    }
                });
            }
        }
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

    /** Forces every mesh to rebuild in full (resource reload, video settings change). */
    public static void invalidateAll() {
        for (Entry entry : ENTRIES.values()) {
            entry.needsFull = true;
        }
        for (SkirtEntry skirt : SKIRTS.values()) {
            skirt.builtFor = null;
        }
    }

    public static double lastRebuildMs() {
        return lastRebuildMs;
    }

    /** Sections built by the last finished rebuild. */
    public static int lastRebuildSections() {
        return lastRebuildSections;
    }

    public static int meshCount() {
        return ENTRIES.size();
    }
}
