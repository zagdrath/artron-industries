/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionTransitionScreenEvent;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.boti.SnapshotBox;
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
 *     <li>the cached view of the far side (which is exactly what the player was looking at) is drawn where it really is
 *     ("arrival cover", see {@link BotiRenderer}), behind any real terrain, so it only fills what has not been drawn
 *     yet. It stays until the level renderer reports every section it covers, and those round the camera, compiled
 *     (empty and off-screen sections need not be, nor sections it skipped as occluded once it has gone idle), lingers
 *     {@link #COVER_LINGER_TICKS} more ticks, then dithers away over {@link #FADE_TICKS}; {@link #MAX_COVER_TICKS} caps
 *     it. That readiness, not the player's release, ends it: renderers that compile sections in the background
 *     (Sodium) finish long after the player's own chunk is here;</li>
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
    /** Upper bound for the arrival cover, in client ticks, in case terrain never becomes ready. */
    private static final int MAX_COVER_TICKS = 160;
    /** Extra ticks the cover stays once terrain is ready, so the last compiled sections reach the screen. */
    private static final int COVER_LINGER_TICKS = 2;
    /** How long the cover fades out, in ticks (drawn per frame with the partial tick: about 6 frames at 60 fps). */
    private static final int FADE_TICKS = 2;
    /**
     * How long the level renderer must have had nothing left to compile before a section it never compiled counts as
     * ready anyway: one it skipped because it is occluded (vanilla only compiles what its occlusion graph reaches).
     */
    private static final int SETTLED_TICKS = 3;
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
    private static int settledTicks;
    /** Ticks into the fade-out, or -1 while the cover is fully drawn. */
    private static int fadeTicks = -1;
    /** The last frame's view frustum, recorded while the cover is up: sections outside it need not be ready. */
    private static @Nullable Frustum coverFrustum;

    private static @Nullable BotiCrossingPayload crossing;
    private static @Nullable Departure departure;
    /**
     * The doorway's turn, already given to the new player when it was placed at the arrival point. The server's teleport
     * turns it again (its rotation is relative), so this much is taken back off once that has arrived.
     */
    private static float preTurn;

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
            placeAtArrival(c, event.getOldPlayer(), event.getNewPlayer());
            primeEnvironment(event.getNewPlayer().level(), event.getNewPlayer().getEyePosition());
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
        float turned = preTurn;
        preTurn = 0.0F;
        if (player != null && turned != 0.0F) {
            player.setYRot(player.getYRot() - turned);
            player.setYHeadRot(player.getYHeadRot() - turned);
            player.yRotO -= turned;
        }
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

    /**
     * The new player starts wherever the respawn put it until the server's position packet arrives, which can be a frame
     * or a tick later: put it where the old one will come out of the doorway straight away, so the first frame in the new
     * level is already from the right place. The position packet then only corrects it.
     */
    private static void placeAtArrival(BotiCrossingPayload c, LocalPlayer oldPlayer, LocalPlayer newPlayer) {
        BotiClientCache.View view = BotiClientCache.get(c.key());
        if (view == null) {
            return;
        }
        DoorPairTransform transform = view.snapshot().geometry().nearToFar();
        Vec3 pos = transform.apply(oldPlayer.position());
        float yaw = transform.applyYaw(oldPlayer.getYRot());
        preTurn = yaw - oldPlayer.getYRot();
        newPlayer.snapTo(pos.x, pos.y, pos.z, yaw, oldPlayer.getXRot());
        newPlayer.setOldPosAndRot(pos, yaw, oldPlayer.getXRot());
    }

    /**
     * A dimension change clears the camera's environment probe, and until it is next ticked (and while the new level has
     * no chunks round the player) the sky and fog come out at their defaults: black, then grey, for several frames.
     * Seeded with the destination at the arrival point, they are right from the first frame.
     */
    private static void primeEnvironment(Level level, Vec3 eye) {
        Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe().tick(level, eye);
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
        settledTicks = 0;
        fadeTicks = -1;
        coverFrustum = null;
        expectedKey = null;
    }

    private static void endCover() {
        arrivalKey = null;
        arrivalDestination = null;
        fadeTicks = -1;
        coverFrustum = null;
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

    /** The arrival cover's opacity this frame: 1 until it starts fading out, then down to 0 over {@link #FADE_TICKS}. */
    static float arrivalAlpha(float partialTick) {
        return fadeTicks < 0 ? 1.0F : Mth.clamp(1.0F - (fadeTicks + partialTick) / FADE_TICKS, 0.0F, 1.0F);
    }

    /** Records this frame's view frustum while the cover is up (see {@link #terrainReady}). */
    static void coverFrustum(Frustum frustum) {
        coverFrustum = frustum;
    }

    /** Whether the cached view {@code key} must be kept even though the server cleared it (until the fade has finished). */
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
        if (mc.level != null && mc.level.dimension() == arrivalDestination) {
            // Keep the environment seeded at the camera while the cover is up (vanilla's own tick may not run yet).
            primeEnvironment(mc.level, mc.gameRenderer.mainCamera().position());
        }
        arrivalTicks++;
        if (fadeTicks >= 0) {
            if (++fadeTicks >= FADE_TICKS) {
                endCover();
            }
            return;
        }
        lingerTicks = terrainReady(mc) ? lingerTicks + 1 : 0;
        boolean timedOut = arrivalTicks > MAX_COVER_TICKS;
        if (timedOut || lingerTicks > COVER_LINGER_TICKS) {
            ArtronIndustries.LOGGER.debug("BOTI arrival cover {} fading out after {} ticks ({})", arrivalKey, arrivalTicks,
                    timedOut ? "timed out" : "terrain ready");
            fadeTicks = 0;
        }
    }

    /**
     * Whether the real terrain is drawn wherever the cover is: every section the cover's snapshot overlaps, and the
     * camera's own section with its neighbours round and above and below it. Goes through the level renderer's own
     * per-section query, which renderers that replace terrain rendering (Sodium) override with their real state.
     */
    private static boolean terrainReady(Minecraft mc) {
        ClientLevel level = mc.level;
        Frustum frustum = coverFrustum;
        PortalViewKey key = arrivalKey;
        if (level == null || frustum == null || key == null || level.dimension() != arrivalDestination) {
            settledTicks = 0;
            return false;
        }
        LongSet sections = coverSections(level, mc.gameRenderer.mainCamera().blockPosition(), BotiClientCache.get(key));
        LongIterator it = sections.iterator();
        while (it.hasNext()) {
            long section = it.next();
            if (level.getChunkSource().getChunkNow(SectionPos.x(section), SectionPos.z(section)) == null) {
                // The renderer is idle before the chunks are even here: only count idleness once they all are.
                settledTicks = 0;
                return false;
            }
        }
        settledTicks = mc.levelRenderer.hasRenderedAllSections() ? settledTicks + 1 : 0;
        it = sections.iterator();
        while (it.hasNext()) {
            if (!sectionReady(mc, level, frustum, it.next())) {
                return false;
            }
        }
        return true;
    }

    /** The sections {@link #terrainReady} checks, within the level's height. */
    private static LongSet coverSections(ClientLevel level, BlockPos camera, BotiClientCache.@Nullable View view) {
        LongSet sections = new LongOpenHashSet();
        int cx = SectionPos.blockToSectionCoord(camera.getX());
        int cy = SectionPos.blockToSectionCoord(camera.getY());
        int cz = SectionPos.blockToSectionCoord(camera.getZ());
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) {
                sections.add(SectionPos.asLong(x, cy, z));
            }
        }
        sections.add(SectionPos.asLong(cx, cy - 1, cz));
        sections.add(SectionPos.asLong(cx, cy + 1, cz));
        if (view != null) {
            SnapshotBox box = view.snapshot().box();
            for (int x = SectionPos.blockToSectionCoord(box.origin().getX()); x <= SectionPos.blockToSectionCoord(box.maxX()); x++) {
                for (int y = SectionPos.blockToSectionCoord(box.origin().getY()); y <= SectionPos.blockToSectionCoord(box.maxY()); y++) {
                    for (int z = SectionPos.blockToSectionCoord(box.origin().getZ()); z <= SectionPos.blockToSectionCoord(box.maxZ()); z++) {
                        sections.add(SectionPos.asLong(x, y, z));
                    }
                }
            }
        }
        sections.removeIf((long s) -> SectionPos.y(s) < level.getMinSectionY() || SectionPos.y(s) > level.getMaxSectionY());
        return sections;
    }

    /**
     * Whether one section (its chunk loaded) needs no more cover. Air-only sections, and sections outside the view, have
     * nothing to show. An uncompiled section counts once the renderer has been idle a while: vanilla never compiles
     * sections its occlusion graph does not reach, so waiting for those would hold the cover to its cap.
     */
    private static boolean sectionReady(Minecraft mc, ClientLevel level, Frustum frustum, long section) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(section), SectionPos.z(section));
        if (chunk == null) {
            return false;
        }
        if (chunk.getSection(level.getSectionIndexFromSectionY(SectionPos.y(section))).hasOnlyAir()) {
            return true;
        }
        BlockPos origin = SectionPos.of(section).origin();
        if (!frustum.isVisible(new AABB(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16))) {
            return true;
        }
        // No fade duration: compiled (and uploaded) is enough; chunk fade-in only tints distant sections, it leaves no holes.
        return mc.levelRenderer.isSectionCompiledAndVisible(origin, 0L) || settledTicks >= SETTLED_TICKS;
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
