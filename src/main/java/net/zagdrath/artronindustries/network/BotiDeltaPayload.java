/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalEnvironment;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.PortalViewKey;

/**
 * S2C: all changes to one view's snapshot since the last delta, batched per tick. {@code sequence} names the full
 * snapshot the delta applies to; clients drop deltas for any other sequence.
 *
 * @param indices       box indices of changed blocks
 * @param states        new block state ids, parallel to {@code indices}
 * @param light         new packed light, parallel to {@code indices}
 * @param blockEntities block entity data changes (empty = removed)
 * @param environment   refreshed far-side atmosphere, if it is time for one
 */
public record BotiDeltaPayload(PortalViewKey key, int sequence, int[] indices, int[] states, byte[] light,
                               List<BlockEntityChange> blockEntities, Optional<PortalEnvironment> environment) implements CustomPacketPayload {
    public record BlockEntityChange(int index, Optional<PortalSnapshot.BlockEntityData> data) {
        static final StreamCodec<ByteBuf, BlockEntityChange> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, BlockEntityChange::index,
                ByteBufCodecs.optional(PortalSnapshot.BlockEntityData.STREAM_CODEC), BlockEntityChange::data,
                BlockEntityChange::new);
    }

    public static final int MAX_CHANGES = PortalSnapshot.MAX_VOLUME;

    public static final Type<BotiDeltaPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_delta"));
    public static final StreamCodec<ByteBuf, BotiDeltaPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BotiDeltaPayload decode(ByteBuf buf) {
            PortalViewKey key = PortalViewKey.STREAM_CODEC.decode(buf);
            int sequence = ByteBufCodecs.VAR_INT.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            if (count < 0 || count > MAX_CHANGES) {
                throw new IllegalArgumentException("Bad delta size " + count);
            }
            int[] indices = new int[count];
            int[] states = new int[count];
            byte[] light = new byte[count];
            for (int i = 0; i < count; i++) {
                indices[i] = ByteBufCodecs.VAR_INT.decode(buf);
                states[i] = ByteBufCodecs.VAR_INT.decode(buf);
                light[i] = buf.readByte();
            }
            List<BlockEntityChange> blockEntities = ByteBufCodecs.<ByteBuf, BlockEntityChange>list(4096).apply(BlockEntityChange.STREAM_CODEC).decode(buf);
            Optional<PortalEnvironment> environment = ByteBufCodecs.optional(PortalEnvironment.STREAM_CODEC).decode(buf);
            return new BotiDeltaPayload(key, sequence, indices, states, light, new ArrayList<>(blockEntities), environment);
        }

        @Override
        public void encode(ByteBuf buf, BotiDeltaPayload payload) {
            PortalViewKey.STREAM_CODEC.encode(buf, payload.key);
            ByteBufCodecs.VAR_INT.encode(buf, payload.sequence);
            ByteBufCodecs.VAR_INT.encode(buf, payload.indices.length);
            for (int i = 0; i < payload.indices.length; i++) {
                ByteBufCodecs.VAR_INT.encode(buf, payload.indices[i]);
                ByteBufCodecs.VAR_INT.encode(buf, payload.states[i]);
                buf.writeByte(payload.light[i]);
            }
            ByteBufCodecs.<ByteBuf, BlockEntityChange>list(4096).apply(BlockEntityChange.STREAM_CODEC).encode(buf, payload.blockEntities);
            ByteBufCodecs.optional(PortalEnvironment.STREAM_CODEC).encode(buf, payload.environment);
        }
    };

    public boolean isEmpty() {
        return this.indices.length == 0 && this.blockEntities.isEmpty() && this.environment.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
