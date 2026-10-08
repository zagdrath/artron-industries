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

/** S2C: the player no longer watches this view; drop its snapshot and mesh. */
public record BotiClearPayload(PortalViewKey key) implements CustomPacketPayload {
    public static final Type<BotiClearPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_clear"));
    public static final StreamCodec<ByteBuf, BotiClearPayload> STREAM_CODEC = PortalViewKey.STREAM_CODEC.map(BotiClearPayload::new, BotiClearPayload::key);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
