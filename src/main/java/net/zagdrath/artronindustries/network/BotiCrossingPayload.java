/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalViewKey;

/**
 * S2C, sent immediately before the server moves the player through a doorway: the coming dimension change is a walk
 * through {@code key}'s doorway, so the client skips the loading screen and covers the gap with the cached view.
 */
public record BotiCrossingPayload(PortalViewKey key, ResourceKey<Level> destination) implements CustomPacketPayload {
    public static final Type<BotiCrossingPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_crossing"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotiCrossingPayload> STREAM_CODEC = StreamCodec.composite(
            PortalViewKey.STREAM_CODEC, BotiCrossingPayload::key,
            ResourceKey.streamCodec(Registries.DIMENSION), BotiCrossingPayload::destination,
            BotiCrossingPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
