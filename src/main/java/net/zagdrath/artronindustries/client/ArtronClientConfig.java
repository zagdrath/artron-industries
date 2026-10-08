/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only settings, stored in {@code config/artronindustries-client.toml}. */
public final class ArtronClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public enum RenderMode {
        /** Full view through the doorway, masked with the main render target's stencil buffer. */
        STENCIL,
        /** No view: open doorways show the dark fallback shimmer. */
        DISABLED
    }

    static {
        BUILDER.comment("Bigger-on-the-inside rendering").push("boti");
    }

    public static final ModConfigSpec.EnumValue<RenderMode> RENDER_MODE = BUILDER
            .comment("How open TARDIS doorways are drawn.",
                    "STENCIL: the real view through the door (needs a stencil buffer on the main render target; enabled automatically).",
                    "DISABLED: a dark animated shimmer instead of the view.",
                    "Takes effect immediately; the stencil attachment is always requested at startup.")
            .defineEnum("renderMode", RenderMode.STENCIL);

    public static final ModConfigSpec.BooleanValue RENDER_ENTITIES = BUILDER
            .comment("Show mobs and other entities through doorways (looking out of a TARDIS).")
            .define("renderEntities", true);

    public static final ModConfigSpec.IntValue MAX_ENTITIES = BUILDER
            .comment("Maximum number of entities drawn through one doorway.")
            .defineInRange("maxEntities", 32, 0, 256);

    public static final ModConfigSpec.BooleanValue RENDER_BLOCK_ENTITIES = BUILDER
            .comment("Draw block entities (chests, signs, ...) through the nearest open doorway.")
            .define("renderBlockEntities", true);

    public static final ModConfigSpec.BooleanValue FORCE_WITH_SHADERS = BUILDER
            .comment("Render the real view even when a shader pack mod (Iris/Oculus) is installed. Off by default because shader",
                    "packs replace the pipelines BOTI relies on; the fallback shimmer is used instead.")
            .define("forceWithShaders", false);

    public static final ModConfigSpec.IntValue MAX_REBUILDS_PER_FRAME = BUILDER
            .comment("Maximum number of doorway mesh rebuilds started per frame. Rebuilds run off-thread and only happen when the view changes.")
            .defineInRange("maxRebuildsPerFrame", 1, 1, 16);

    public static final ModConfigSpec.IntValue RENDER_DISTANCE = BUILDER
            .comment("Doorways further away than this many blocks are not drawn (their view is not visible anyway).")
            .defineInRange("renderDistance", 64, 8, 512);

    public static final ModConfigSpec.IntValue INTERIOR_BACKDROP_COLOR = BUILDER
            .comment("RGB colour shown behind the interior geometry when looking into a TARDIS (where no blocks are).")
            .defineInRange("interiorBackdropColor", 0x05060a, 0x000000, 0xffffff);

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Log mesh rebuild and draw timings at INFO level (they are always logged at DEBUG).")
            .define("debugTimings", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ArtronClientConfig() {}
}
