/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.network.BotiClearPayload;
import net.zagdrath.artronindustries.network.BotiDeltaPayload;
import net.zagdrath.artronindustries.network.BotiSnapshotPayload;

/**
 * Client-side store of the snapshots this player currently watches, keyed by view. Reassembles split snapshots, applies
 * deltas in place and marks views dirty for the mesh builder. Accessed on the client main thread only.
 */
public final class BotiClientCache {
    /** Ticks a cleared view stays around so a closing door can still show it while its animation finishes. */
    private static final int CLEAR_GRACE_TICKS = 20;

    /**
     * One cached view. {@code dirtyBlocks} accumulates changed indices until the mesh builder takes them;
     * {@code allDirty} means the whole snapshot was replaced.
     */
    public static final class View {
        private PortalSnapshot snapshot;
        private boolean meshDirty = true;
        private boolean allDirty = true;
        private boolean blockEntitiesDirty = true;
        private final IntOpenHashSet dirtyBlocks = new IntOpenHashSet();
        private int clearCountdown = -1;
        private int revision;
        private int bytes;

        View(PortalSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        public PortalSnapshot snapshot() {
            return this.snapshot;
        }

        /** Encoded size of the last full snapshot received for this view. */
        public int bytes() {
            return this.bytes;
        }

        /** Incremented on every change; lets renderers detect a replaced or modified snapshot. */
        public int revision() {
            return this.revision;
        }

        public boolean isMeshDirty() {
            return this.meshDirty;
        }

        /**
         * Takes what changed since the last call: the box-local indices of changed blocks, or null when the whole view
         * has to be rebuilt (a new snapshot).
         */
        public @Nullable IntSet takeDirtyBlocks() {
            IntSet dirty = this.allDirty ? null : new IntOpenHashSet(this.dirtyBlocks);
            this.meshDirty = false;
            this.allDirty = false;
            this.dirtyBlocks.clear();
            return dirty;
        }

        /** Returns and resets the block-entity dirty flag. */
        public boolean consumeBlockEntitiesDirty() {
            boolean d = this.blockEntitiesDirty;
            this.blockEntitiesDirty = false;
            return d;
        }

        public boolean isClearing() {
            return this.clearCountdown >= 0;
        }

        private void touch() {
            this.meshDirty = true;
            this.revision++;
        }
    }

    private record PartKey(PortalViewKey key, int sequence) {}

    private static final class Assembly {
        final byte[][] parts;
        int received;

        Assembly(int count) {
            this.parts = new byte[count][];
        }
    }

    private static final Map<PortalViewKey, View> VIEWS = new HashMap<>();
    private static final Map<PartKey, Assembly> ASSEMBLIES = new HashMap<>();

    private BotiClientCache() {}

    public static @Nullable View get(PortalViewKey key) {
        return VIEWS.get(key);
    }

    public static Collection<Map.Entry<PortalViewKey, View>> entries() {
        return VIEWS.entrySet();
    }

    static void onSnapshotPart(BotiSnapshotPayload payload) {
        if (payload.parts() < 1 || payload.parts() > BotiSnapshotPayload.MAX_PARTS || payload.part() < 0 || payload.part() >= payload.parts()) {
            ArtronIndustries.LOGGER.warn("Ignoring malformed BOTI snapshot part {}/{} for {}", payload.part(), payload.parts(), payload.key());
            return;
        }
        // Older, unfinished sequences for the same view are superseded.
        ASSEMBLIES.keySet().removeIf(k -> k.key.equals(payload.key()) && k.sequence != payload.sequence());
        Assembly assembly = ASSEMBLIES.computeIfAbsent(new PartKey(payload.key(), payload.sequence()), k -> new Assembly(payload.parts()));
        if (assembly.parts.length != payload.parts() || assembly.parts[payload.part()] != null) {
            return;
        }
        assembly.parts[payload.part()] = payload.data();
        if (++assembly.received < assembly.parts.length) {
            return;
        }
        ASSEMBLIES.remove(new PartKey(payload.key(), payload.sequence()));
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : assembly.parts) {
            joined.writeBytes(part);
        }
        long start = System.nanoTime();
        PortalSnapshot snapshot;
        try {
            snapshot = PortalSnapshot.fromBytes(joined.toByteArray());
        } catch (RuntimeException e) {
            ArtronIndustries.LOGGER.error("Failed to decode BOTI snapshot for {}", payload.key(), e);
            return;
        }
        if (!snapshot.key().equals(payload.key()) || snapshot.sequence() != payload.sequence()) {
            ArtronIndustries.LOGGER.warn("BOTI snapshot header does not match its payload, dropping");
            return;
        }
        View view = VIEWS.get(snapshot.key());
        if (view == null) {
            view = new View(snapshot);
            VIEWS.put(snapshot.key(), view);
        } else {
            BotiBlockEntities.release(view);
            view.snapshot = snapshot;
            view.clearCountdown = -1;
            view.blockEntitiesDirty = true;
            view.allDirty = true;
            view.dirtyBlocks.clear();
            view.touch();
        }
        view.bytes = joined.size();
        ArtronIndustries.LOGGER.debug("BOTI received snapshot {} #{}: {} bytes, decoded in {} ms", snapshot.key(), snapshot.sequence(),
                joined.size(), String.format("%.2f", (System.nanoTime() - start) / 1.0E6));
    }

    static void onDelta(BotiDeltaPayload delta) {
        View view = VIEWS.get(delta.key());
        if (view == null || view.snapshot.sequence() != delta.sequence()) {
            return;
        }
        PortalSnapshot s = view.snapshot;
        int volume = s.box().volume();
        int[] indices = delta.indices();
        if (indices.length != delta.states().length || indices.length != delta.light().length) {
            return;
        }
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index < 0 || index >= volume) {
                continue;
            }
            s.states()[index] = delta.states()[i];
            s.light()[index] = delta.light()[i];
            view.dirtyBlocks.add(index);
        }
        for (BotiDeltaPayload.BlockEntityChange change : delta.blockEntities()) {
            if (change.index() < 0 || change.index() >= volume) {
                continue;
            }
            if (change.data().isPresent()) {
                s.blockEntities().put(change.index(), change.data().get());
            } else {
                s.blockEntities().remove(change.index());
            }
            view.blockEntitiesDirty = true;
        }
        delta.environment().ifPresent(s::setEnvironment);
        if (indices.length > 0) {
            view.touch();
        } else if (!delta.blockEntities().isEmpty()) {
            view.revision++;
        }
    }

    static void onClear(BotiClearPayload payload) {
        View view = VIEWS.get(payload.key());
        if (view != null && view.clearCountdown < 0) {
            view.clearCountdown = CLEAR_GRACE_TICKS;
        }
        ASSEMBLIES.keySet().removeIf(k -> k.key.equals(payload.key()));
    }

    /** Called every client tick: expires cleared views. */
    static void tick() {
        for (Iterator<Map.Entry<PortalViewKey, View>> it = VIEWS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<PortalViewKey, View> entry = it.next();
            View view = entry.getValue();
            if (view.clearCountdown > 0) {
                view.clearCountdown--;
            } else if (view.clearCountdown == 0 && !SeamlessTransition.holds(entry.getKey())) {
                it.remove();
                BotiMeshCache.release(view);
                BotiLightmaps.release(view);
                BotiBlockEntities.release(view);
                BotiEntities.release(entry.getKey());
            }
        }
    }

    /** Drops everything (disconnect). */
    static void clearAll() {
        VIEWS.values().forEach(BotiMeshCache::release);
        VIEWS.values().forEach(BotiLightmaps::release);
        VIEWS.values().forEach(BotiBlockEntities::release);
        BotiEntities.clearAll();
        VIEWS.clear();
        ASSEMBLIES.clear();
    }
}
