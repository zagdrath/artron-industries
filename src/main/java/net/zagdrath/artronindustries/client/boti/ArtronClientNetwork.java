/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.zagdrath.artronindustries.network.BotiClearPayload;
import net.zagdrath.artronindustries.network.BotiCrossingPayload;
import net.zagdrath.artronindustries.network.BotiDeltaPayload;
import net.zagdrath.artronindustries.network.BotiEntitiesPayload;
import net.zagdrath.artronindustries.network.BotiSnapshotPayload;

/** Client-only payload handlers. All run on the client main thread. */
public final class ArtronClientNetwork {
    private ArtronClientNetwork() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RegisterClientPayloadHandlersEvent.class, event -> {
            event.register(BotiSnapshotPayload.TYPE, (payload, context) -> BotiClientCache.onSnapshotPart(payload));
            event.register(BotiDeltaPayload.TYPE, (payload, context) -> BotiClientCache.onDelta(payload));
            event.register(BotiClearPayload.TYPE, (payload, context) -> BotiClientCache.onClear(payload));
            event.register(BotiEntitiesPayload.TYPE, (payload, context) -> BotiEntities.onPayload(payload));
            event.register(BotiCrossingPayload.TYPE, (payload, context) -> SeamlessTransition.onCrossing(payload));
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            BotiClientCache.tick();
            BotiEntities.tick();
            SeamlessTransition.tick();
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> BotiClientCache.clearAll());
    }
}
