/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.zagdrath.artronindustries.network.InteriorPresencePayload;

/**
 * Tells each player which TARDIS interior they are standing in (the interior of the TARDIS whose cell holds them), so the
 * client can play that interior's hum. Checked every {@link #INTERVAL} ticks and sent only when it changes; a player who
 * logs in again is told afresh.
 */
public final class InteriorPresence {
    private static final int INTERVAL = 5;
    private static final Map<UUID, Optional<Identifier>> SENT = new HashMap<>();
    private static int ticks;

    private InteriorPresence() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            if (++ticks % INTERVAL == 0) {
                tick(e.getServer());
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> SENT.remove(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, e -> SENT.clear());
    }

    private static void tick(MinecraftServer server) {
        TardisInteriorManager manager = TardisInteriorManager.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Optional<Identifier> interior = Optional.empty();
            if (TardisInteriorManager.isInterior(player.level())) {
                TardisRecord record = manager.byInteriorPos(player.blockPosition());
                if (record != null) {
                    interior = Optional.of(record.interior().id());
                }
            }
            if (!interior.equals(SENT.get(player.getUUID()))) {
                SENT.put(player.getUUID(), interior);
                PacketDistributor.sendToPlayer(player, new InteriorPresencePayload(interior));
            }
        }
    }
}
