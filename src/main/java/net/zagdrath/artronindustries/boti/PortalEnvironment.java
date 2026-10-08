/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Far-side atmosphere sampled by the server at the far doorway, used for the backdrop behind the snapshot. Refreshed
 * every {@code boti.headerRefreshInterval} ticks.
 *
 * @param skyColor RGB sky colour (already includes time of day and weather)
 * @param fogColor RGB fog colour
 * @param fogEnd   environmental fog end distance in blocks
 * @param rain     rain level 0..1
 * @param thunder  thunder level 0..1
 * @param dayTime  far-side default clock ticks
 * @param hasSky   whether the far side has a sky (false for TARDIS interiors)
 * @param biomeId  network id of the biome at the far doorway
 */
public record PortalEnvironment(int skyColor, int fogColor, float fogEnd, float rain, float thunder, long dayTime, boolean hasSky, int biomeId) {
    public static final PortalEnvironment DARK = new PortalEnvironment(0x050508, 0x050508, 64.0F, 0.0F, 0.0F, 0L, false, -1);

    public static final StreamCodec<ByteBuf, PortalEnvironment> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PortalEnvironment decode(ByteBuf buf) {
            return new PortalEnvironment(buf.readInt(), buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    ByteBufCodecs.VAR_LONG.decode(buf), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf));
        }

        @Override
        public void encode(ByteBuf buf, PortalEnvironment env) {
            buf.writeInt(env.skyColor);
            buf.writeInt(env.fogColor);
            buf.writeFloat(env.fogEnd);
            buf.writeFloat(env.rain);
            buf.writeFloat(env.thunder);
            ByteBufCodecs.VAR_LONG.encode(buf, env.dayTime);
            buf.writeBoolean(env.hasSky);
            ByteBufCodecs.VAR_INT.encode(buf, env.biomeId);
        }
    };
}
