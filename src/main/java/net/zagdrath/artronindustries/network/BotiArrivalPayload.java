/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalViewKey;

/**
 * S2C, sent right after the teleport announced by {@link BotiCrossingPayload}: the respawn and position packets have been
 * applied, so the client can now move its new player to where its old one had walked to.
 */
public record BotiArrivalPayload(PortalViewKey key) implements CustomPacketPayload {
    public static final Type<BotiArrivalPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_arrival"));
    public static final StreamCodec<ByteBuf, BotiArrivalPayload> STREAM_CODEC = PortalViewKey.STREAM_CODEC.map(BotiArrivalPayload::new, BotiArrivalPayload::key);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
