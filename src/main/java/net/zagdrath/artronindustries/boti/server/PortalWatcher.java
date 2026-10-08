/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti.server;

import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.zagdrath.artronindustries.network.BotiEntitiesPayload;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.Config;
import net.zagdrath.artronindustries.boti.PortalEnvironment;
import net.zagdrath.artronindustries.boti.PortalGeometry;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.boti.SnapshotBox;
import net.zagdrath.artronindustries.network.BotiClearPayload;
import net.zagdrath.artronindustries.network.BotiDeltaPayload;
import net.zagdrath.artronindustries.network.BotiSnapshotPayload;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronTickets;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * Decides who sees through which open door and streams the far side to them: a full snapshot on subscribe, then one
 * batched delta per check interval, and a clear on unsubscribe. All state is server-authoritative; clients never send
 * block data.
 * <p>
 * Change detection: block events ({@link BlockEvent.NeighborNotifyEvent}) hint individual positions, which are diffed
 * every {@code boti.blockDeltaInterval} ticks; a full sweep every {@code boti.lightDeltaInterval} ticks also catches
 * light changes, block entity data and changes made without neighbour updates.
 */
public final class PortalWatcher {
    /** Extra blocks of range before an existing watcher is dropped, so standing at the edge does not flap. */
    private static final double HYSTERESIS = 4.0;

    private static final Map<PortalViewKey, WatchedView> VIEWS = new HashMap<>();
    private static int nextSequence = 1;
    private static long ticks;

    private PortalWatcher() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(BlockEvent.NeighborNotifyEvent.class, PortalWatcher::onBlockChanged);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> forget(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                unsubscribeAll(player);
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> {
            new ArrayList<>(VIEWS.keySet()).forEach(key -> drop(e.getServer(), key));
            ticks = 0;
        });
        TardisInteriorManager.addListener(PortalWatcher::onTardisChanged);
    }

    // --- Subscriptions ------------------------------------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % Config.WATCHER_SCAN_INTERVAL.getAsInt() == 0) {
            scan(server);
        }
        boolean blocks = ticks % Config.BLOCK_DELTA_INTERVAL.getAsInt() == 0;
        boolean sweep = ticks % Config.LIGHT_DELTA_INTERVAL.getAsInt() == 0;
        boolean header = ticks % Config.HEADER_REFRESH_INTERVAL.getAsInt() == 0;
        if (blocks || sweep || header) {
            for (WatchedView view : List.copyOf(VIEWS.values())) {
                update(server, view, sweep, header);
            }
        }
        if (ticks % Config.ENTITY_UPDATE_INTERVAL.getAsInt() == 0) {
            boolean refreshData = ticks % 20 == 0;
            for (WatchedView view : VIEWS.values()) {
                if (view.ready && !view.watchers.isEmpty()) {
                    sendEntities(server, view, refreshData);
                }
            }
        }
    }

    private static void scan(MinecraftServer server) {
        TardisInteriorManager manager = TardisInteriorManager.get(server);
        Map<PortalViewKey, List<ServerPlayer>> desired = new HashMap<>();
        Map<PortalViewKey, TardisRecord> records = new HashMap<>();
        double radius = Config.WATCH_RADIUS.getAsInt();
        for (TardisRecord record : manager.all()) {
            // Shut doors are watched too: the view is then already on the client when they open, instead of the doorway
            // showing only the backdrop until the first snapshot arrives.
            if (!record.hasExterior() || !record.interiorGenerated()) {
                continue;
            }
            for (PortalSide side : PortalSide.values()) {
                ServerLevel near = TardisInteriorManager.level(server, record, side);
                if (near == null || TardisInteriorManager.level(server, record, side.opposite()) == null) {
                    continue;
                }
                PortalViewKey key = new PortalViewKey(record.uuid(), side);
                WatchedView existing = VIEWS.get(key);
                BlockPos pos = record.doorPos(side);
                Direction facing = record.doorFacing(side);
                PortalShape shape = record.shape(side);
                Vec3 center = shape.center(pos, facing);
                for (ServerPlayer player : near.players()) {
                    boolean watching = existing != null && existing.watchers.contains(player.getUUID());
                    double r = watching ? radius + HYSTERESIS : radius;
                    Vec3 eye = player.getEyePosition();
                    if (eye.distanceToSqr(center) <= r * r && shape.signedDistance(pos, facing, eye) >= (watching ? -2.0 : -0.5)) {
                        desired.computeIfAbsent(key, k -> new ArrayList<>()).add(player);
                        records.put(key, record);
                    }
                }
            }
        }

        for (Iterator<Map.Entry<PortalViewKey, WatchedView>> it = VIEWS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<PortalViewKey, WatchedView> entry = it.next();
            WatchedView view = entry.getValue();
            List<ServerPlayer> want = desired.getOrDefault(entry.getKey(), List.of());
            for (Iterator<UUID> w = view.watchers.iterator(); w.hasNext(); ) {
                UUID id = w.next();
                if (want.stream().noneMatch(p -> p.getUUID().equals(id))) {
                    w.remove();
                    sendClear(server, id, view.key);
                }
            }
            if (want.isEmpty() && !view.pinned) {
                releaseTickets(server, view);
                it.remove();
            }
        }

        for (Map.Entry<PortalViewKey, List<ServerPlayer>> entry : desired.entrySet()) {
            for (ServerPlayer player : entry.getValue()) {
                subscribe(server, records.get(entry.getKey()), entry.getKey().nearSide(), player);
            }
        }
    }

    /** Subscribes {@code player} to the view through the door on {@code nearSide}, sending the full snapshot if new. */
    public static void subscribe(MinecraftServer server, TardisRecord record, PortalSide nearSide, ServerPlayer player) {
        PortalViewKey key = new PortalViewKey(record.uuid(), nearSide);
        WatchedView view = VIEWS.get(key);
        if (view == null) {
            view = create(server, record, nearSide);
            if (view == null) {
                return;
            }
            VIEWS.put(key, view);
        }
        if (view.watchers.add(player.getUUID()) && view.ready) {
            sendSnapshot(view, player);
            view.entitiesWithData.clear(); // the new watcher needs full entity data
        }
    }

    private static void unsubscribeAll(ServerPlayer player) {
        for (WatchedView view : VIEWS.values()) {
            if (view.watchers.remove(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, new BotiClearPayload(view.key));
            }
        }
    }

    private static void forget(UUID player) {
        VIEWS.values().forEach(v -> v.watchers.remove(player));
    }

    private static void onTardisChanged(MinecraftServer server, TardisRecord record, boolean deleted) {
        for (PortalSide side : PortalSide.values()) {
            PortalViewKey key = new PortalViewKey(record.uuid(), side);
            WatchedView view = VIEWS.get(key);
            if (view != null && (deleted || !record.hasExterior() || !view.geometry.equals(geometry(record, side)))) {
                drop(server, key);
            }
        }
    }

    /** Removes a view, clearing it on every watcher and releasing its chunks. */
    private static void drop(MinecraftServer server, PortalViewKey key) {
        WatchedView view = VIEWS.remove(key);
        if (view != null) {
            view.watchers.forEach(id -> sendClear(server, id, key));
            releaseTickets(server, view);
        }
    }

    // --- Snapshot creation and streaming ------------------------------------------------------------------------------

    static PortalGeometry geometry(TardisRecord record, PortalSide nearSide) {
        PortalSide far = nearSide.opposite();
        return new PortalGeometry(record.doorPos(nearSide), record.doorFacing(nearSide), record.shape(nearSide),
                record.doorPos(far), record.doorFacing(far), record.shape(far));
    }

    static SnapshotBox box(PortalGeometry geometry, PortalSide farSide) {
        boolean interior = farSide == PortalSide.INTERIOR;
        return SnapshotBox.inFrontOf(geometry.farPos(), geometry.farFacing(), geometry.farShape(),
                (interior ? Config.INTERIOR_SNAPSHOT_WIDTH : Config.EXTERIOR_SNAPSHOT_WIDTH).getAsInt(),
                (interior ? Config.INTERIOR_SNAPSHOT_HEIGHT : Config.EXTERIOR_SNAPSHOT_HEIGHT).getAsInt(),
                (interior ? Config.INTERIOR_SNAPSHOT_DEPTH : Config.EXTERIOR_SNAPSHOT_DEPTH).getAsInt());
    }

    private static @Nullable WatchedView create(MinecraftServer server, TardisRecord record, PortalSide nearSide) {
        PortalSide farSide = nearSide.opposite();
        ServerLevel far = TardisInteriorManager.level(server, record, farSide);
        if (far == null || record.doorPos(farSide) == null) {
            return null;
        }
        PortalGeometry geometry = geometry(record, nearSide);
        SnapshotBox box = box(geometry, farSide);
        WatchedView view = new WatchedView(new PortalViewKey(record.uuid(), nearSide), far.dimension(), geometry, box);
        for (int cx = box.origin().getX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.origin().getZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                far.getChunkSource().addTicketWithRadius(ArtronTickets.PORTAL_VIEW.get(), chunk, 2);
                view.tickets.add(chunk);
            }
        }
        tryCapture(far, view);
        return view;
    }

    /**
     * Takes the first capture once every chunk under the box is loaded, so creating a view never generates or loads
     * chunks synchronously on the server thread. Returns whether the view is ready.
     */
    private static boolean tryCapture(ServerLevel far, WatchedView view) {
        if (view.ready) {
            return true;
        }
        for (ChunkPos chunk : view.tickets) {
            if (far.getChunkSource().getChunkNow(chunk.x(), chunk.z()) == null) {
                return false;
            }
        }
        long start = System.nanoTime();
        SnapshotCapture capture = SnapshotCapture.capture(far, view.box);
        view.load(nextSequence++, capture, environment(far, view.geometry));
        if (ArtronIndustries.LOGGER.isDebugEnabled()) {
            long captured = System.nanoTime();
            int bytes = view.encoded().length;
            ArtronIndustries.LOGGER.debug("BOTI snapshot {} #{}: {} blocks, {} block entities, {} bytes, capture {} ms, encode {} ms",
                    view.key, view.sequence, view.box.volume(), capture.blockEntities.size(), bytes,
                    String.format("%.2f", (captured - start) / 1.0E6), String.format("%.2f", (System.nanoTime() - captured) / 1.0E6));
        }
        return true;
    }

    private static PortalEnvironment environment(ServerLevel far, PortalGeometry geometry) {
        return SnapshotCapture.environment(far, geometry.farShape().center(geometry.farPos(), geometry.farFacing()));
    }

    private static void releaseTickets(MinecraftServer server, WatchedView view) {
        ServerLevel far = server.getLevel(view.farLevel);
        if (far != null) {
            for (ChunkPos chunk : view.tickets) {
                far.getChunkSource().removeTicketWithRadius(ArtronTickets.PORTAL_VIEW.get(), chunk, 2);
            }
        }
        view.tickets.clear();
    }

    private static void sendSnapshot(WatchedView view, ServerPlayer player) {
        byte[] bytes = view.encoded();
        int partSize = Math.min(Config.MAX_PAYLOAD_BYTES.getAsInt(), BotiSnapshotPayload.MAX_PART_BYTES);
        int parts = Math.max(1, (bytes.length + partSize - 1) / partSize);
        if (parts > BotiSnapshotPayload.MAX_PARTS) {
            ArtronIndustries.LOGGER.error("BOTI snapshot {} is {} bytes, too large to send; reduce the snapshot size config", view.key, bytes.length);
            return;
        }
        for (int i = 0; i < parts; i++) {
            int from = i * partSize;
            byte[] part = Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + partSize));
            PacketDistributor.sendToPlayer(player, new BotiSnapshotPayload(view.key, view.sequence, i, parts, part));
        }
        ArtronIndustries.LOGGER.debug("BOTI sent snapshot {} #{} to {} ({} bytes in {} parts)", view.key, view.sequence,
                player.getName().getString(), bytes.length, parts);
    }

    private static void sendClear(MinecraftServer server, UUID playerId, PortalViewKey key) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            PacketDistributor.sendToPlayer(player, new BotiClearPayload(key));
        }
    }

    // --- Entities -----------------------------------------------------------------------------------------------------

    private static void sendEntities(MinecraftServer server, WatchedView view, boolean refreshData) {
        ServerLevel far = server.getLevel(view.farLevel);
        int max = Math.min(Config.MAX_ENTITIES.getAsInt(), BotiEntitiesPayload.MAX_ENTITIES);
        if (far == null || max == 0) {
            return;
        }
        SnapshotBox box = view.box;
        AABB bounds = new AABB(box.origin().getX(), box.origin().getY(), box.origin().getZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
        Vec3 door = view.geometry.farShape().center(view.geometry.farPos(), view.geometry.farFacing());
        List<Entity> found = far.getEntities((Entity) null, bounds, e -> e.isAlive() && !e.isSpectator() && !e.isInvisible());
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(door)));
        if (found.isEmpty() && view.lastEntityCount == 0) {
            return;
        }
        List<BotiEntitiesPayload.Entry> entries = new ArrayList<>(Math.min(found.size(), max));
        IntOpenHashSet present = new IntOpenHashSet();
        for (Entity e : found) {
            if (entries.size() >= max) {
                break;
            }
            present.add(e.getId());
            boolean withData = refreshData || !view.entitiesWithData.contains(e.getId());
            Optional<List<SynchedEntityData.DataValue<?>>> data = Optional.empty();
            if (withData) {
                List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
                data = Optional.of(values == null ? List.of() : values);
            }
            Optional<BotiEntitiesPayload.PlayerProfile> profile = e instanceof Player p
                    ? Optional.of(new BotiEntitiesPayload.PlayerProfile(p.getUUID(), p.getGameProfile().name()))
                    : Optional.empty();
            float body = e instanceof LivingEntity living ? living.yBodyRot : e.getYRot();
            entries.add(new BotiEntitiesPayload.Entry(e.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()), profile,
                    e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot(), e.getYHeadRot(), body, data));
        }
        view.entitiesWithData.retainAll(present);
        view.entitiesWithData.addAll(present);
        view.lastEntityCount = entries.size();
        BotiEntitiesPayload payload = new BotiEntitiesPayload(view.key, entries);
        for (UUID id : view.watchers) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    // --- Change detection ---------------------------------------------------------------------------------------------

    private static void onBlockChanged(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || VIEWS.isEmpty()) {
            return;
        }
        BlockPos pos = event.getPos();
        for (WatchedView view : VIEWS.values()) {
            if (view.farLevel == level.dimension() && view.box.containsWorld(pos.getX(), pos.getY(), pos.getZ())) {
                view.dirty.add(view.box.indexOfWorld(pos.getX(), pos.getY(), pos.getZ()));
            }
        }
    }

    private static void update(MinecraftServer server, WatchedView view, boolean sweep, boolean header) {
        ServerLevel far = server.getLevel(view.farLevel);
        if (far == null) {
            drop(server, view.key);
            return;
        }
        if (!view.ready) {
            if (tryCapture(far, view)) {
                for (UUID id : view.watchers) {
                    ServerPlayer player = server.getPlayerList().getPlayer(id);
                    if (player != null) {
                        sendSnapshot(view, player);
                    }
                }
            }
            return;
        }
        long start = System.nanoTime();
        IntArrayList changed = new IntArrayList();
        List<BotiDeltaPayload.BlockEntityChange> beChanges = new ArrayList<>();
        if (sweep) {
            SnapshotCapture now = SnapshotCapture.capture(far, view.box);
            for (int i = 0; i < now.states.length; i++) {
                if (now.states[i] != view.states[i] || now.light[i] != view.light[i]) {
                    changed.add(i);
                    view.states[i] = now.states[i];
                    view.light[i] = now.light[i];
                }
            }
            IntOpenHashSet indices = new IntOpenHashSet(view.blockEntities.keySet());
            indices.addAll(now.blockEntities.keySet());
            for (IntIterator it = indices.iterator(); it.hasNext(); ) {
                int index = it.nextInt();
                PortalSnapshot.BlockEntityData before = view.blockEntities.get(index);
                PortalSnapshot.BlockEntityData after = now.blockEntities.get(index);
                if (after == null) {
                    view.blockEntities.remove(index);
                    beChanges.add(new BotiDeltaPayload.BlockEntityChange(index, Optional.empty()));
                } else if (!after.equals(before)) {
                    view.blockEntities.put(index, after);
                    beChanges.add(new BotiDeltaPayload.BlockEntityChange(index, Optional.of(after)));
                }
            }
        } else if (!view.dirty.isEmpty()) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (IntIterator it = view.dirty.iterator(); it.hasNext(); ) {
                int index = it.nextInt();
                BlockPos world = view.box.worldPos(index);
                pos.set(world);
                BlockState state = far.getBlockState(pos);
                int id = Block.getId(state);
                byte light = PortalSnapshot.packLight(far.getBrightness(LightLayer.BLOCK, pos), far.getBrightness(LightLayer.SKY, pos));
                if (id != view.states[index] || light != view.light[index]) {
                    changed.add(index);
                    view.states[index] = id;
                    view.light[index] = light;
                }
                BlockEntity be = state.hasBlockEntity() ? far.getBlockEntity(pos) : null;
                boolean render = be != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.wrapAsHolder(be.getType())
                        .is(SnapshotCapture.BOTI_RENDER_BLOCK_ENTITIES);
                PortalSnapshot.BlockEntityData before = view.blockEntities.get(index);
                PortalSnapshot.BlockEntityData after = render ? SnapshotCapture.blockEntityData(far, be) : null;
                if (after == null && before != null) {
                    view.blockEntities.remove(index);
                    beChanges.add(new BotiDeltaPayload.BlockEntityChange(index, Optional.empty()));
                } else if (after != null && !after.equals(before)) {
                    view.blockEntities.put(index, after);
                    beChanges.add(new BotiDeltaPayload.BlockEntityChange(index, Optional.of(after)));
                }
            }
        }
        view.dirty.clear();
        if (sweep && ArtronIndustries.LOGGER.isDebugEnabled()) {
            double ms = (System.nanoTime() - start) / 1.0E6;
            if (ms > 2.0) {
                ArtronIndustries.LOGGER.debug("BOTI slow sweep {}: {} ms", view.key, String.format("%.2f", ms));
            }
        }

        Optional<PortalEnvironment> environment = Optional.empty();
        if (header) {
            view.environment = environment(far, view.geometry);
            environment = Optional.of(view.environment);
        }
        if (changed.isEmpty() && beChanges.isEmpty() && environment.isEmpty()) {
            return;
        }
        view.encoded = null;

        Set<UUID> watchers = view.watchers;
        if (changed.size() > view.box.volume() / 4) {
            // Cheaper to resend everything than to describe most of the box as changes.
            view.sequence = nextSequence++;
            for (UUID id : watchers) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) {
                    sendSnapshot(view, player);
                }
            }
            return;
        }
        int[] indices = changed.toIntArray();
        int[] states = new int[indices.length];
        byte[] light = new byte[indices.length];
        for (int i = 0; i < indices.length; i++) {
            states[i] = view.states[indices[i]];
            light[i] = view.light[indices[i]];
        }
        BotiDeltaPayload delta = new BotiDeltaPayload(view.key, view.sequence, indices, states, light, beChanges, environment);
        for (UUID id : watchers) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, delta);
            }
        }
        if (!changed.isEmpty() || !beChanges.isEmpty()) {
            ArtronIndustries.LOGGER.debug("BOTI delta {} #{}: {} blocks, {} block entities{} in {} ms", view.key, view.sequence,
                    indices.length, beChanges.size(), sweep ? " (sweep)" : "", String.format("%.2f", (System.nanoTime() - start) / 1.0E6));
        }
    }

    /** Creates (or returns) a view that stays alive without watchers until the door closes. Tests and debugging only. */
    public static boolean pinView(MinecraftServer server, TardisRecord record, PortalSide nearSide) {
        PortalViewKey key = new PortalViewKey(record.uuid(), nearSide);
        WatchedView view = VIEWS.get(key);
        if (view == null) {
            view = create(server, record, nearSide);
            if (view == null) {
                return false;
            }
            VIEWS.put(key, view);
        }
        view.pinned = true;
        return true;
    }

    /** Number of views currently streamed, for debugging. */
    public static int viewCount() {
        return VIEWS.size();
    }

    /** Players currently watching the view, for debugging and tests. */
    public static Set<UUID> watchers(PortalViewKey key) {
        WatchedView view = VIEWS.get(key);
        return view == null ? Set.of() : Set.copyOf(view.watchers);
    }

    /** Encodes the current server-side state of a view as clients would receive it (tests and debugging). */
    public static byte @Nullable [] encodedView(PortalViewKey key) {
        WatchedView view = VIEWS.get(key);
        return view == null || !view.ready ? null : view.encoded();
    }
}
