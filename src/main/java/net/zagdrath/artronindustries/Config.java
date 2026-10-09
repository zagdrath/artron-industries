/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common (both sides, server-authoritative) settings, stored in {@code config/artronindustries-common.toml}.
 * FML 12 no longer has a COMMON config type, so this is a LOCAL config registered under that file name.
 */
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // --- TARDIS interiors -------------------------------------------------------------------------------------------

    static {
        BUILDER.comment("TARDIS interior allocation").push("tardis");
    }

    /** Distance in blocks between neighbouring interior cells on X and Z. Changing it only affects new TARDISes. */
    public static final ModConfigSpec.IntValue CELL_SPACING = BUILDER
            .comment("Distance in blocks between neighbouring TARDIS interior cells on X and Z.",
                    "Changing this only affects newly created TARDISes.")
            .defineInRange("cellSpacing", 4096, 256, 1 << 20);

    /** Y level of the interior origin (floor of the starter room). */
    public static final ModConfigSpec.IntValue INTERIOR_Y = BUILDER
            .comment("Y level of the floor of each TARDIS interior.")
            .defineInRange("interiorY", 64, -48, 300);

    /** Whether cells of deleted TARDISes may be handed out again. Off by default so stale chunks never leak into a new TARDIS. */
    public static final ModConfigSpec.BooleanValue REUSE_DELETED_CELLS = BUILDER
            .comment("Reuse interior cells of deleted TARDISes. Off by default: old blocks would remain in the reused cell.")
            .define("reuseDeletedCells", false);

    static {
        BUILDER.pop();
    }

    // --- BOTI (server side) -----------------------------------------------------------------------------------------

    static {
        BUILDER.comment("Bigger-on-the-inside view streaming (server side)").push("boti");
    }

    /** Interior box seen through the exterior door: width (across the door), height and depth (behind the interior door). */
    public static final ModConfigSpec.IntValue INTERIOR_SNAPSHOT_WIDTH = BUILDER
            .comment("Width (across the doorway) of the interior region streamed to players looking in from outside.")
            .defineInRange("interiorSnapshotWidth", 48, 4, 128);
    public static final ModConfigSpec.IntValue INTERIOR_SNAPSHOT_HEIGHT = BUILDER
            .comment("Height of the interior region streamed to players looking in from outside.")
            .defineInRange("interiorSnapshotHeight", 24, 4, 128);
    public static final ModConfigSpec.IntValue INTERIOR_SNAPSHOT_DEPTH = BUILDER
            .comment("Depth (away from the interior door) of the interior region streamed to players looking in from outside.")
            .defineInRange("interiorSnapshotDepth", 48, 4, 128);

    /** Exterior box seen through the interior door. */
    public static final ModConfigSpec.IntValue EXTERIOR_SNAPSHOT_WIDTH = BUILDER
            .comment("Width (across the doorway) of the outside region streamed to players looking out from the interior.")
            .defineInRange("exteriorSnapshotWidth", 64, 4, 192);
    public static final ModConfigSpec.IntValue EXTERIOR_SNAPSHOT_HEIGHT = BUILDER
            .comment("Height of the outside region streamed to players looking out from the interior.")
            .defineInRange("exteriorSnapshotHeight", 32, 4, 128);
    public static final ModConfigSpec.IntValue EXTERIOR_SNAPSHOT_DEPTH = BUILDER
            .comment("Depth (away from the exterior door) of the outside region streamed to players looking out.")
            .defineInRange("exteriorSnapshotDepth", 64, 4, 192);

    /** Players within this distance of an open door's plane (and in front of it) receive its far-side view. */
    public static final ModConfigSpec.IntValue WATCH_RADIUS = BUILDER
            .comment("Players within this many blocks of an open door, and in front of it, are sent the view through it.")
            .defineInRange("watchRadius", 32, 4, 256);

    /** How often (ticks) watcher membership is re-evaluated. */
    public static final ModConfigSpec.IntValue WATCHER_SCAN_INTERVAL = BUILDER
            .comment("How often, in ticks, the server re-evaluates which players watch which doors.")
            .defineInRange("watcherScanInterval", 5, 1, 100);

    /** How often (ticks) block states of watched regions are diffed and sent as deltas. */
    public static final ModConfigSpec.IntValue BLOCK_DELTA_INTERVAL = BUILDER
            .comment("How often, in ticks, watched regions are checked for block changes. Changes are batched into one delta per check.")
            .defineInRange("blockDeltaInterval", 1, 1, 40);

    /** How often (ticks) light and block-entity data of watched regions are diffed. */
    public static final ModConfigSpec.IntValue LIGHT_DELTA_INTERVAL = BUILDER
            .comment("How often, in ticks, light levels and block entity data of watched regions are checked for changes.")
            .defineInRange("lightDeltaInterval", 10, 1, 200);

    /** How often (ticks) the snapshot header (time of day, weather, biome) is refreshed. */
    public static final ModConfigSpec.IntValue HEADER_REFRESH_INTERVAL = BUILDER
            .comment("How often, in ticks, time of day / weather / biome of the far side are re-sent.")
            .defineInRange("headerRefreshInterval", 20, 1, 1200);

    /** Snapshots larger than this are split into several payloads. Must stay below the 1 MiB custom payload limit. */
    public static final ModConfigSpec.IntValue MAX_PAYLOAD_BYTES = BUILDER
            .comment("Snapshot payloads larger than this many bytes are split into parts (vanilla limit is 1048576).")
            .defineInRange("maxPayloadBytes", 512 * 1024, 16 * 1024, 1000 * 1024);

    /** Exterior entities sent to players looking out of the interior. */
    public static final ModConfigSpec.IntValue ENTITY_UPDATE_INTERVAL = BUILDER
            .comment("How often, in ticks, outside entities are sent to players looking out of the interior.")
            .defineInRange("entityUpdateInterval", 2, 1, 40);
    public static final ModConfigSpec.IntValue MAX_ENTITIES = BUILDER
            .comment("Maximum number of outside entities sent per door per update.")
            .defineInRange("maxEntities", 32, 0, 256);

    static {
        BUILDER.pop();
    }

    // --- Walk-through -----------------------------------------------------------------------------------------------

    static {
        BUILDER.comment("Walking through open doors").push("teleport");
    }

    public static final ModConfigSpec.BooleanValue TELEPORT_NON_PLAYERS = BUILDER
            .comment("Let non-player entities (items, mobs, ...) pass through open doors.")
            .define("teleportNonPlayers", true);

    public static final ModConfigSpec.IntValue CROSSING_COOLDOWN = BUILDER
            .comment("Ticks after passing through a door during which an entity cannot pass through again.")
            .defineInRange("crossingCooldown", 10, 0, 200);

    public static final ModConfigSpec.DoubleValue PREWARM_DISTANCE = BUILDER
            .comment("Players closer than this to an open doorway and moving towards it get the destination chunks preloaded.")
            .defineInRange("prewarmDistance", 3.0, 0.0, 16.0);

    static {
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();
}
