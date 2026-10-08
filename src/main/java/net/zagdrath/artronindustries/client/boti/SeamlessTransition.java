/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterDimensionTransitionScreenEvent;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.network.BotiCrossingPayload;
import net.zagdrath.artronindustries.tardis.ArtronDimensions;

/**
 * Makes walking through a TARDIS doorway look continuous across the dimension change:
 * <ul>
 *     <li>the server announces the crossing ({@link BotiCrossingPayload}) right before it moves the player;</li>
 *     <li>for that one transition the loading screen draws nothing (NeoForge's dimension transition screen hook), so the
 *     world stays visible instead of the "loading terrain" panorama;</li>
 *     <li>until the destination's chunks are compiled, the cached view of the far side (which is exactly what the player
 *     was looking at) is drawn in place of the not-yet-loaded terrain ("arrival cover", see {@link BotiRenderer}).</li>
 * </ul>
 * Other dimension changes into or out of TARDIS interiors (commands, death) keep the normal loading screen.
 */
public final class SeamlessTransition {
    private static final long EXPECT_TIMEOUT_MS = 3000L;
    /** Upper bound for the arrival cover, in client ticks, in case chunks never arrive. */
    private static final int MAX_COVER_TICKS = 100;
    /** Extra ticks the cover stays after the player's own section is ready, while neighbouring sections finish. */
    private static final int COVER_LINGER_TICKS = 6;

    private static @Nullable PortalViewKey expectedKey;
    private static @Nullable ResourceKey<Level> expectedDestination;
    private static long expectedUntil;

    private static @Nullable PortalViewKey arrivalKey;
    private static @Nullable ResourceKey<Level> arrivalDestination;
    private static int arrivalTicks;
    private static int lingerTicks;

    private SeamlessTransition() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RegisterDimensionTransitionScreenEvent.class, e -> {
            e.registerIncomingEffect(ArtronDimensions.TARDIS_INTERIORS, SeamlessTransition::screen);
            e.registerOutgoingEffect(ArtronDimensions.TARDIS_INTERIORS, SeamlessTransition::screen);
        });
    }

    static void onCrossing(BotiCrossingPayload payload) {
        expectedKey = payload.key();
        expectedDestination = payload.destination();
        expectedUntil = Util.getMillis() + EXPECT_TIMEOUT_MS;
    }

    private static LevelLoadingScreen screen(LevelLoadTracker tracker, LevelLoadingScreen.Reason reason) {
        if (expectedKey == null || Util.getMillis() > expectedUntil) {
            return new LevelLoadingScreen(tracker, reason);
        }
        arrivalKey = expectedKey;
        arrivalDestination = expectedDestination;
        arrivalTicks = 0;
        lingerTicks = 0;
        expectedKey = null;
        return new Invisible(tracker, reason);
    }

    /** The view to draw as arrival cover in {@code level}, or null when no cover is needed. */
    static @Nullable PortalViewKey arrivalCover(Level level) {
        return arrivalKey != null && level.dimension() == arrivalDestination ? arrivalKey : null;
    }

    /** Whether the cached view {@code key} must be kept even though the server cleared it. */
    static boolean holds(PortalViewKey key) {
        return key.equals(arrivalKey);
    }

    static void tick() {
        if (arrivalKey == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // Vanilla keeps the player frozen until its section has been compiled for rendering. The arrival cover already
        // shows the destination, so the player only needs its chunk (for collision): release it as soon as that is here.
        if (mc.gui.screen() instanceof Invisible && mc.player != null && mc.level != null && mc.getConnection() != null
                && mc.level.hasChunk(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4)) {
            mc.getConnection().notifyPlayerLoaded();
            mc.gui.screen().onClose();
        }
        boolean loaded = mc.getConnection() != null && mc.getConnection().hasClientLoaded();
        if (loaded) {
            lingerTicks++;
        }
        if (++arrivalTicks > MAX_COVER_TICKS || lingerTicks > COVER_LINGER_TICKS) {
            arrivalKey = null;
            arrivalDestination = null;
        }
    }

    /** A loading screen that draws nothing, so the level (and the arrival cover) stay visible. */
    private static final class Invisible extends LevelLoadingScreen {
        Invisible(LevelLoadTracker tracker, Reason reason) {
            super(tracker, reason);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        }
    }
}
