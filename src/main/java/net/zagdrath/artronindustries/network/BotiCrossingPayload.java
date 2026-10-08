/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalViewKey;

/**
 * S2C, sent immediately before the server moves the player through a doorway: the coming dimension change is a walk
 * through {@code key}'s doorway, so the client skips the loading screen and covers the gap with the cached view.
 * {@code departure} is the server's position of the player when it crossed and {@code quarterTurns} the doorway pair's
 * rotation, so the client can carry over the movement it made since (see {@link BotiArrivalPayload}).
 */
public record BotiCrossingPayload(PortalViewKey key, ResourceKey<Level> destination, Vec3 departure, int quarterTurns) implements CustomPacketPayload {
    public static final Type<BotiCrossingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_crossing"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotiCrossingPayload> STREAM_CODEC = StreamCodec.composite(
            PortalViewKey.STREAM_CODEC, BotiCrossingPayload::key,
            ResourceKey.streamCodec(Registries.DIMENSION), BotiCrossingPayload::destination,
            Vec3.STREAM_CODEC, BotiCrossingPayload::departure,
            ByteBufCodecs.VAR_INT, BotiCrossingPayload::quarterTurns,
            BotiCrossingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
