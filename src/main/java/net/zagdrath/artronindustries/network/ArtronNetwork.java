/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration. All BOTI payloads are server -> client; clients never send block data. Their handlers live in
 * client-only code ({@code ArtronClientNetwork}), registered through {@code RegisterClientPayloadHandlersEvent}, so the
 * dedicated server never loads client classes.
 */
public final class ArtronNetwork {
    public static final String VERSION = "4";

    private ArtronNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(BotiSnapshotPayload.TYPE, BotiSnapshotPayload.STREAM_CODEC);
        registrar.playToClient(BotiDeltaPayload.TYPE, BotiDeltaPayload.STREAM_CODEC);
        registrar.playToClient(BotiClearPayload.TYPE, BotiClearPayload.STREAM_CODEC);
        registrar.playToClient(BotiEntitiesPayload.TYPE, BotiEntitiesPayload.STREAM_CODEC);
        registrar.playToClient(BotiCrossingPayload.TYPE, BotiCrossingPayload.STREAM_CODEC);
        registrar.playToClient(BotiArrivalPayload.TYPE, BotiArrivalPayload.STREAM_CODEC);
        registrar.playToClient(InteriorPresencePayload.TYPE, InteriorPresencePayload.STREAM_CODEC);
    }
}
