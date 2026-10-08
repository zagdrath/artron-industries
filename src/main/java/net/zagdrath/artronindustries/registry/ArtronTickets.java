/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.registry;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.TicketType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.artronindustries.ArtronIndustries;

public final class ArtronTickets {
    public static final DeferredRegister<TicketType> TICKET_TYPES = DeferredRegister.create(Registries.TICKET_TYPE, ArtronIndustries.MODID);

    /**
     * Keeps the far side of a watched doorway loaded and simulated (so its mobs move) while anyone looks through it.
     * Never expires and is not persisted; it is removed explicitly when the last watcher leaves.
     */
    public static final Supplier<TicketType> PORTAL_VIEW = TICKET_TYPES.register("portal_view",
            () -> new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    /** Short-lived ticket that pre-loads a destination doorway before an entity walks through it. */
    public static final Supplier<TicketType> PORTAL_PREWARM = TICKET_TYPES.register("portal_prewarm",
            () -> new TicketType(60L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    private ArtronTickets() {}
}
