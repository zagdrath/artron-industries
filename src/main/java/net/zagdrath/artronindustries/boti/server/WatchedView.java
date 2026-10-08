/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti.server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.zagdrath.artronindustries.boti.PortalEnvironment;
import net.zagdrath.artronindustries.boti.PortalGeometry;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.boti.SnapshotBox;

/** Server-side state of one watched view: the last state sent to its watchers, who they are, and the chunks held loaded. */
final class WatchedView {
    final PortalViewKey key;
    final ResourceKey<Level> farLevel;
    final PortalGeometry geometry;
    final SnapshotBox box;
    final Set<UUID> watchers = new HashSet<>();
    /** Box indices hinted dirty by block events since the last delta. */
    final IntOpenHashSet dirty = new IntOpenHashSet();
    final List<ChunkPos> tickets = new ArrayList<>();
    /** Entity ids whose synched data the watchers already have. */
    final IntOpenHashSet entitiesWithData = new IntOpenHashSet();
    int lastEntityCount;

    /** False until the far chunks have finished loading (asynchronously, via the ticket) and the first capture ran. */
    boolean ready;
    /** Debug/test views stay alive without watchers until explicitly dropped. */
    boolean pinned;
    int sequence;
    int[] states;
    byte[] light;
    int[] columnBiomes;
    Int2ObjectOpenHashMap<PortalSnapshot.BlockEntityData> blockEntities;
    PortalEnvironment environment;
    /** Encoded current state, rebuilt lazily after changes, shared by every newly subscribing watcher. */
    byte @Nullable [] encoded;

    WatchedView(PortalViewKey key, ResourceKey<Level> farLevel, PortalGeometry geometry, SnapshotBox box) {
        this.key = key;
        this.farLevel = farLevel;
        this.geometry = geometry;
        this.box = box;
    }

    void load(int sequence, SnapshotCapture capture, PortalEnvironment environment) {
        this.sequence = sequence;
        this.states = capture.states;
        this.light = capture.light;
        this.columnBiomes = capture.columnBiomes;
        this.blockEntities = capture.blockEntities;
        this.environment = environment;
        this.encoded = null;
        this.ready = true;
    }

    byte[] encoded() {
        if (this.encoded == null) {
            this.encoded = new PortalSnapshot(this.key, this.sequence, this.geometry, this.box, this.states, this.light,
                    this.columnBiomes, this.blockEntities, this.environment).toBytes();
        }
        return this.encoded;
    }
}
