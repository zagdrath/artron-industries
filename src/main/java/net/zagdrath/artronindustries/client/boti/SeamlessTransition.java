/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionTransitionScreenEvent;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.network.BotiArrivalPayload;
import net.zagdrath.artronindustries.network.BotiCrossingPayload;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.tardis.ArtronDimensions;

/**
 * Makes walking through a TARDIS doorway look continuous across the dimension change:
 * <ul>
 *     <li>the server announces the crossing ({@link BotiCrossingPayload}) right before it moves the player;</li>
 *     <li>for that one transition the loading screen draws nothing, so the world stays visible instead of the "loading
 *     terrain" panorama. It is opened as soon as the crossing is announced: the respawn then only updates it, instead of
 *     opening one and forcing a frame before the new player is the camera, which would be black;</li>
 *     <li>until the destination's chunks are compiled, the cached view of the far side (which is exactly what the player
 *     was looking at) is drawn in place of the not-yet-loaded terrain ("arrival cover", see {@link BotiRenderer});</li>
 *     <li>the server teleports with yaw, pitch and velocity relative to the client's own, then sends
 *     {@link BotiArrivalPayload}: the new player is moved on by however far the old one had walked past the server's
 *     position, and gets the old one's previous-tick position, rotation, view bobbing and first-person hands, so the
 *     camera neither slows, snaps back nor skips a tick of interpolation. The server sends the nearest chunks ahead of
 *     that packet, so the player is normally released (and the screen closed) right then.</li>
 * </ul>
 * Other dimension changes into or out of TARDIS interiors (commands, death) keep the normal loading screen.
 */
public final class SeamlessTransition {
    private static final long EXPECT_TIMEOUT_MS = 3000L;
    /** How long the screen opened for an announced crossing waits for the respawn before giving up. */
    private static final long RESPAWN_TIMEOUT_MS = 1000L;
    /** Upper bound for the arrival cover, in client ticks, in case chunks never arrive. */
    private static final int MAX_COVER_TICKS = 100;
    /** Extra ticks the cover stays after the player's own section is ready, while neighbouring sections finish. */
    private static final int COVER_LINGER_TICKS = 6;
    /** The client is never more than a few ticks ahead of the server; a larger lead is not carried over. */
    private static final double MAX_LEAD = 4.0;

    private static @Nullable PortalViewKey expectedKey;
    private static @Nullable ResourceKey<Level> expectedDestination;
    private static long expectedUntil;
    private static long respawnBy;

    private static @Nullable PortalViewKey arrivalKey;
    private static @Nullable ResourceKey<Level> arrivalDestination;
    private static int arrivalTicks;
    private static int lingerTicks;

    private static @Nullable BotiCrossingPayload crossing;
    private static @Nullable Departure departure;

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
        respawnBy = Util.getMillis() + RESPAWN_TIMEOUT_MS;
        crossing = payload;
        departure = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() == null) {
            mc.gui.setScreen(new Invisible(new LevelLoadTracker(), LevelLoadingScreen.Reason.OTHER));
        }
    }

    /** The dimension change replaces the player: remember the old one before the position packet moves the new one. */
    static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        BotiCrossingPayload c = crossing;
        if (c != null && Util.getMillis() <= expectedUntil && event.getNewPlayer().level().dimension() == c.destination()) {
            departure = Departure.of(event.getOldPlayer());
            event.getNewPlayer().firstPersonHandsAndItems = event.getOldPlayer().firstPersonHandsAndItems;
            startCover(c.key(), c.destination());
        }
    }

    static void onArrival(BotiArrivalPayload payload) {
        carryOver(payload);
        releaseIfReady(Minecraft.getInstance());
    }

    private static void carryOver(BotiArrivalPayload payload) {
        BotiCrossingPayload c = crossing;
        Departure d = departure;
        crossing = null;
        departure = null;
        LocalPlayer player = Minecraft.getInstance().player;
        if (c == null || d == null || player == null || !payload.key().equals(c.key()) || player.level().dimension() != c.destination()) {
            return;
        }
        Vec3 lead = d.position().subtract(c.departure());
        if (lead.lengthSqr() > MAX_LEAD * MAX_LEAD) {
            return;
        }
        // The server placed the player where its lagging copy crossed. The doorway pair is a rigid transform, so the
        // client's lead over that copy, and its previous-tick position, map across with the rotation alone.
        DoorPairTransform rotation = DoorPairTransform.rotation(c.quarterTurns());
        float turn = rotation.applyYaw(0.0F);
        Vec3 placed = player.position();
        player.setPos(placed.add(rotation.applyVelocity(lead)));
        player.setOldPosAndRot(placed.add(rotation.applyVelocity(d.oldPosition().subtract(c.departure()))), d.yRotO() + turn, d.xRotO());
        player.yBob = d.yBob() + turn;
        player.yBobO = d.yBobO() + turn;
        player.xBob = d.xBob();
        player.xBobO = d.xBobO();
        ClientAvatarState avatar = player.avatarState();
        avatar.walkDist = d.walkDist();
        avatar.walkDistO = d.walkDistO();
        avatar.bob = d.bob();
        avatar.bobO = d.bobO();
    }

    private static LevelLoadingScreen screen(LevelLoadTracker tracker, LevelLoadingScreen.Reason reason) {
        if (expectedKey == null || Util.getMillis() > expectedUntil) {
            return new LevelLoadingScreen(tracker, reason);
        }
        startCover(expectedKey, expectedDestination);
        return new Invisible(tracker, reason);
    }

    private static void startCover(PortalViewKey key, @Nullable ResourceKey<Level> destination) {
        arrivalKey = key;
        arrivalDestination = destination;
        arrivalTicks = 0;
        lingerTicks = 0;
        expectedKey = null;
    }

    /**
     * Vanilla keeps the player frozen until its section has been compiled for rendering. The arrival cover already shows
     * the destination, so the player only needs its chunk (for collision): release it as soon as that is here.
     */
    private static void releaseIfReady(Minecraft mc) {
        if (mc.gui.screen() instanceof Invisible && arrivalKey != null && mc.player != null && mc.level != null && mc.getConnection() != null
                && mc.level.dimension() == arrivalDestination && mc.level.hasChunk(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4)) {
            mc.getConnection().notifyPlayerLoaded();
            mc.gui.setScreen(null);
        }
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
        Minecraft mc = Minecraft.getInstance();
        if (arrivalKey == null) {
            // Announced, but the respawn never came (the server refused the teleport): give the player its controls back.
            if (mc.gui.screen() instanceof Invisible && expectedKey != null && Util.getMillis() > respawnBy) {
                expectedKey = null;
                crossing = null;
                mc.gui.setScreen(null);
            }
            return;
        }
        releaseIfReady(mc);
        boolean loaded = mc.getConnection() != null && mc.getConnection().hasClientLoaded();
        if (loaded) {
            lingerTicks++;
        }
        if (++arrivalTicks > MAX_COVER_TICKS || lingerTicks > COVER_LINGER_TICKS) {
            arrivalKey = null;
            arrivalDestination = null;
        }
    }

    /** What the old player had that a dimension change resets on the new one. */
    private record Departure(Vec3 position, Vec3 oldPosition, float yRotO, float xRotO, float yBob, float xBob, float yBobO, float xBobO,
                             float walkDist, float walkDistO, float bob, float bobO) {
        static Departure of(LocalPlayer p) {
            ClientAvatarState a = p.avatarState();
            return new Departure(p.position(), p.oldPosition(), p.yRotO, p.xRotO, p.yBob, p.xBob, p.yBobO, p.xBobO,
                    a.walkDist, a.walkDistO, a.bob, a.bobO);
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
