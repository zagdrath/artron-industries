/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import java.util.Optional;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;

/**
 * Which TARDIS interior (a {@code TardisInteriors} id) the player is standing in, or empty when none. Sent when it changes
 * (see {@code InteriorPresence}).
 */
public record InteriorPresencePayload(Optional<Identifier> interior) implements CustomPacketPayload {
    public static final Type<InteriorPresencePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "interior_presence"));
    public static final StreamCodec<ByteBuf, InteriorPresencePayload> STREAM_CODEC = ByteBufCodecs.optional(Identifier.STREAM_CODEC)
            .map(InteriorPresencePayload::new, InteriorPresencePayload::interior);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
