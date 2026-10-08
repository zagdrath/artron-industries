/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalViewKey;

/**
 * S2C: one part of an encoded {@link net.zagdrath.artronindustries.boti.PortalSnapshot}. Snapshots larger than
 * {@code boti.maxPayloadBytes} are split; the client reassembles parts with the same key and sequence.
 */
public record BotiSnapshotPayload(PortalViewKey key, int sequence, int part, int parts, byte[] data) implements CustomPacketPayload {
    /** Hard cap on a single part, safely below the 1 MiB custom payload limit. */
    public static final int MAX_PART_BYTES = 1000 * 1024;
    public static final int MAX_PARTS = 64;

    public static final Type<BotiSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_snapshot"));
    public static final StreamCodec<ByteBuf, BotiSnapshotPayload> STREAM_CODEC = StreamCodec.composite(
            PortalViewKey.STREAM_CODEC, BotiSnapshotPayload::key,
            ByteBufCodecs.VAR_INT, BotiSnapshotPayload::sequence,
            ByteBufCodecs.VAR_INT, BotiSnapshotPayload::part,
            ByteBufCodecs.VAR_INT, BotiSnapshotPayload::parts,
            ByteBufCodecs.byteArray(MAX_PART_BYTES), BotiSnapshotPayload::data,
            BotiSnapshotPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
