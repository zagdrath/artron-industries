/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.Config;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.network.BotiArrivalPayload;
import net.zagdrath.artronindustries.network.BotiCrossingPayload;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronTickets;

/**
 * Server-side walk-through: an entity whose position goes from in front of an open doorway's plane to behind it, while
 * inside the {@link PortalShape} opening, is moved to the paired door with the {@link DoorPairTransform} applied to its
 * position, yaw and velocity (pitch is kept). Players approaching an open doorway get the destination pre-loaded.
 */
public final class DoorwayCrossing {
    /** Entities further than this from a doorway plane are not tracked. Covers sprinting and thrown items. */
    private static final double TRACK_RANGE = 3.0;
    /** Distance an arriving entity is placed in front of the destination plane, so it cannot immediately re-cross. */
    private static final double ARRIVAL_OFFSET = 0.05;

    /** Last tracked position per entity, with its dimension: a position from another level says nothing about crossing. */
    private static final Map<UUID, Tracked> LAST_POSITIONS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
    private static long ticks;

    private record Tracked(ResourceKey<Level> dimension, Vec3 position) {}

    private DoorwayCrossing() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> {
            LAST_POSITIONS.clear();
            COOLDOWN_UNTIL.clear();
        });
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        Set<UUID> seen = new HashSet<>();
        TardisInteriorManager manager = TardisInteriorManager.get(server);
        for (TardisRecord record : List.copyOf(manager.all())) {
            if (!record.doorOpen() || !record.hasExterior() || !record.interiorGenerated()) {
                continue;
            }
            for (PortalSide side : PortalSide.values()) {
                ServerLevel level = TardisInteriorManager.level(server, record, side);
                PortalDoorBlockEntity door = manager.loadedDoor(server, record, side);
                OpenSpan passable = door == null ? OpenSpan.NONE : door.getPassableSpan();
                if (level == null || passable.isEmpty()) {
                    continue;
                }
                BlockPos pos = record.doorPos(side);
                Direction facing = record.doorFacing(side);
                PortalShape shape = record.shape(side);
                for (Entity entity : level.getEntities((Entity) null, shape.bounds(pos, facing, TRACK_RANGE), e -> !e.isRemoved())) {
                    UUID id = entity.getUUID();
                    seen.add(id);
                    Vec3 now = entity.position();
                    Tracked last = LAST_POSITIONS.put(id, new Tracked(level.dimension(), now));
                    Vec3 before = last != null && last.dimension() == level.dimension() ? last.position() : null;
                    if (entity instanceof ServerPlayer player) {
                        prewarm(server, record, side, player, shape, pos, facing, before, now);
                    }
                    if (before == null || COOLDOWN_UNTIL.getOrDefault(id, Long.MIN_VALUE) > ticks) {
                        continue;
                    }
                    double d0 = shape.signedDistance(pos, facing, before);
                    double d1 = shape.signedDistance(pos, facing, now);
                    if (d0 <= 0.0 || d1 > 0.0) {
                        continue;
                    }
                    Vec3 crossing = before.lerp(now, d0 / (d0 - d1));
                    if (!passesThroughOpening(shape, passable, pos, facing, entity, crossing)) {
                        continue;
                    }
                    cross(server, record, side, entity, now);
                }
            }
        }
        LAST_POSITIONS.keySet().retainAll(seen);
        COOLDOWN_UNTIL.values().removeIf(until -> until <= ticks);
    }

    private static boolean passesThroughOpening(PortalShape shape, OpenSpan passable, BlockPos pos, Direction facing, Entity entity, Vec3 crossing) {
        double lateral = shape.lateral(pos, facing, crossing);
        double bottom = pos.getY() + shape.bottomOffset();
        return lateral >= shape.lateralAt(passable.from()) && lateral <= shape.lateralAt(passable.to())
                && crossing.y >= bottom - 0.5
                && crossing.y + Math.min(entity.getBbHeight(), shape.height()) <= bottom + shape.height() + 0.1;
    }

    private static void cross(MinecraftServer server, TardisRecord record, PortalSide from, Entity entity, Vec3 position) {
        boolean player = entity instanceof ServerPlayer;
        if (!player && !Config.TELEPORT_NON_PLAYERS.getAsBoolean()) {
            return;
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            return;
        }
        PortalSide to = from.opposite();
        ServerLevel destination = TardisInteriorManager.level(server, record, to);
        DoorPairTransform exteriorToInterior = record.transform();
        if (destination == null || exteriorToInterior == null || !entity.canTeleport(entity.level(), destination)) {
            return;
        }
        DoorPairTransform transform = from == PortalSide.EXTERIOR ? exteriorToInterior : exteriorToInterior.invert();
        Direction arrivalFacing = record.doorFacing(to);
        // The entity is just behind the near plane, which maps just in front of the far plane; nudge it clear.
        Vec3 target = transform.apply(position).add(Vec3.atLowerCornerOf(arrivalFacing.getUnitVec3i()).scale(ARRIVAL_OFFSET));
        target = intoOpening(target, record.shape(from), record.shape(to), record.doorPos(to), arrivalFacing, entity);
        PortalViewKey key = new PortalViewKey(record.uuid(), from);
        TeleportTransition transition;
        if (entity instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer, new BotiCrossingPayload(key, destination.dimension(), position, transform.quarterTurns()));
            // The client moves its own player and the server's copy lags it by a tick or two, so the turn and the velocity
            // are relative to what the client has: it keeps exactly its own look direction and speed, turned with the doorway.
            transition = new TeleportTransition(destination, target, Vec3.ZERO, transform.applyYaw(0.0F), 0.0F,
                    Relative.union(Relative.ROTATION, Relative.DELTA), TeleportTransition.DO_NOTHING);
        } else {
            Vec3 velocity = transform.applyVelocity(entity.getDeltaMovement());
            transition = new TeleportTransition(destination, target, velocity, transform.applyYaw(entity.getYRot()), entity.getXRot(),
                    TeleportTransition.DO_NOTHING);
        }
        Entity moved = entity.teleport(transition);
        if (moved == null) {
            return;
        }
        if (moved instanceof ServerPlayer serverPlayer) {
            // Send the nearest destination chunks now rather than next tick: the client holds the player still until its
            // own chunk is there, and with it in the same flush as the arrival packet it can release the player at once.
            serverPlayer.connection.chunkSender.sendNextChunks(serverPlayer);
            PacketDistributor.sendToPlayer(serverPlayer, new BotiArrivalPayload(key));
        } else {
            moved.setDeltaMovement(transition.deltaMovement());
        }
        moved.setYHeadRot(moved.getYRot());
        COOLDOWN_UNTIL.put(moved.getUUID(), ticks + Config.CROSSING_COOLDOWN.getAsInt());
        LAST_POSITIONS.put(moved.getUUID(), new Tracked(destination.dimension(), moved.position()));
        ArtronIndustries.LOGGER.debug("{} walked through TARDIS #{} {} -> {}", entity.getName().getString(), record.id(), from, to);
    }

    /**
     * Coming out of a wider or taller doorway than the one arrived at (a template interior's 4x4 door into the police
     * box's), {@code target} can be beside the far doorway: it is moved in front of the far opening, so the entity steps
     * out of the doors.
     */
    static Vec3 intoOpening(Vec3 target, PortalShape fromShape, PortalShape toShape, BlockPos toPos, Direction toFacing, Entity entity) {
        if (toShape.width() < fromShape.width()) {
            double lateral = toShape.lateral(toPos, toFacing, target);
            double limit = Math.max(0.0, toShape.width() / 2.0 - entity.getBbWidth() / 2.0);
            double clamped = Math.clamp(lateral, -limit, limit);
            Direction right = PortalShape.right(toFacing);
            target = target.add(right.getStepX() * (clamped - lateral), 0.0, right.getStepZ() * (clamped - lateral));
        }
        if (toShape.height() < fromShape.height()) {
            double bottom = toPos.getY() + toShape.bottomOffset();
            double top = Math.max(bottom, bottom + toShape.height() - entity.getBbHeight());
            target = new Vec3(target.x, Math.clamp(target.y, bottom, top), target.z);
        }
        return target;
    }

    /**
     * While a player is close to an open doorway and moving towards it, keep the destination chunks loaded so they are
     * ready (and sent at once) the moment the player arrives.
     */
    private static void prewarm(MinecraftServer server, TardisRecord record, PortalSide side, ServerPlayer player, PortalShape shape,
                                BlockPos pos, Direction facing, Vec3 before, Vec3 now) {
        if (before == null || ticks % 10 != 0) {
            return;
        }
        double d = shape.signedDistance(pos, facing, now);
        boolean approaching = shape.signedDistance(pos, facing, before) > d;
        if (d <= 0.0 || d > Config.PREWARM_DISTANCE.getAsDouble() || !approaching) {
            return;
        }
        PortalSide to = side.opposite();
        ServerLevel destination = TardisInteriorManager.level(server, record, to);
        if (destination != null) {
            destination.getChunkSource().addTicketWithRadius(ArtronTickets.PORTAL_PREWARM.get(), ChunkPos.containing(record.doorPos(to)), 3);
        }
    }
}
