/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import org.jspecify.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Far-side atmosphere sampled by the server at the far doorway, used for the backdrop, sky and lighting of the snapshot.
 * Refreshed every {@code boti.headerRefreshInterval} ticks.
 *
 * @param skyColor RGB sky colour (already includes time of day and weather)
 * @param fogColor RGB fog colour
 * @param fogStart environmental fog start distance in blocks
 * @param fogEnd   environmental fog end distance in blocks
 * @param rain     rain level 0..1
 * @param thunder  thunder level 0..1
 * @param dayTime  far-side default clock ticks
 * @param hasSky   whether the far side has a sky (false for TARDIS interiors)
 * @param biomeId  network id of the biome at the far doorway
 * @param light    how the far side's lightmap turns light levels into colour there
 * @param sky      what is drawn in that sky, or {@code null} for no sky (or no overworld-like skybox)
 */
public record PortalEnvironment(int skyColor, int fogColor, float fogStart, float fogEnd, float rain, float thunder, long dayTime, boolean hasSky, int biomeId,
                                Light light, @Nullable Sky sky) {
    public static final PortalEnvironment DARK = new PortalEnvironment(0x050508, 0x050508, 0.0F, 64.0F, 0.0F, 0.0F, 0L, false, -1, Light.DEFAULT, null);

    /**
     * The far side's lightmap inputs, as its own environment attributes give them at the doorway (the viewer's own
     * settings and effects, such as gamma and night vision, are added on the client).
     *
     * @param skyFactor    sky light strength 0..1 (time of day, weather)
     * @param skyColor     RGB colour of sky light
     * @param ambientColor RGB colour of unlit areas
     * @param blockTint    RGB tint of dim block light
     */
    public record Light(float skyFactor, int skyColor, int ambientColor, int blockTint) {
        /** Vanilla's defaults for these attributes. */
        public static final Light DEFAULT = new Light(1.0F, 0xFFFFFF, 0x000000, 0xFFD88C);

        static final StreamCodec<ByteBuf, Light> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Light decode(ByteBuf buf) {
                return new Light(buf.readFloat(), buf.readUnsignedMedium(), buf.readUnsignedMedium(), buf.readUnsignedMedium());
            }

            @Override
            public void encode(ByteBuf buf, Light light) {
                buf.writeFloat(light.skyFactor);
                buf.writeMedium(light.skyColor);
                buf.writeMedium(light.ambientColor);
                buf.writeMedium(light.blockTint);
            }
        };
    }

    /**
     * The sky's moving parts, as the far side's own environment attributes give them at the doorway.
     *
     * @param sunAngle       degrees
     * @param moonAngle      degrees
     * @param starAngle      degrees
     * @param starBrightness 0..1
     * @param moonPhase      {@code MoonPhase} index
     * @param sunriseColor   ARGB, alpha = strength of the sunrise/sunset glow
     * @param cloudColor     ARGB, alpha 0 = no clouds
     * @param cloudHeight    world Y of the bottom of the clouds
     * @param gameTime       far-side game time, which drives the clouds' drift
     */
    public record Sky(float sunAngle, float moonAngle, float starAngle, float starBrightness, int moonPhase, int sunriseColor, int cloudColor,
                      float cloudHeight, long gameTime) {
        static final StreamCodec<ByteBuf, Sky> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Sky decode(ByteBuf buf) {
                return new Sky(buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), ByteBufCodecs.VAR_INT.decode(buf), buf.readInt(),
                        buf.readInt(), buf.readFloat(), ByteBufCodecs.VAR_LONG.decode(buf));
            }

            @Override
            public void encode(ByteBuf buf, Sky sky) {
                buf.writeFloat(sky.sunAngle);
                buf.writeFloat(sky.moonAngle);
                buf.writeFloat(sky.starAngle);
                buf.writeFloat(sky.starBrightness);
                ByteBufCodecs.VAR_INT.encode(buf, sky.moonPhase);
                buf.writeInt(sky.sunriseColor);
                buf.writeInt(sky.cloudColor);
                buf.writeFloat(sky.cloudHeight);
                ByteBufCodecs.VAR_LONG.encode(buf, sky.gameTime);
            }
        };
    }

    public static final StreamCodec<ByteBuf, PortalEnvironment> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PortalEnvironment decode(ByteBuf buf) {
            return new PortalEnvironment(buf.readInt(), buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    ByteBufCodecs.VAR_LONG.decode(buf), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf), Light.STREAM_CODEC.decode(buf),
                    buf.readBoolean() ? Sky.STREAM_CODEC.decode(buf) : null);
        }

        @Override
        public void encode(ByteBuf buf, PortalEnvironment env) {
            buf.writeInt(env.skyColor);
            buf.writeInt(env.fogColor);
            buf.writeFloat(env.fogStart);
            buf.writeFloat(env.fogEnd);
            buf.writeFloat(env.rain);
            buf.writeFloat(env.thunder);
            ByteBufCodecs.VAR_LONG.encode(buf, env.dayTime);
            buf.writeBoolean(env.hasSky);
            ByteBufCodecs.VAR_INT.encode(buf, env.biomeId);
            Light.STREAM_CODEC.encode(buf, env.light);
            buf.writeBoolean(env.sky != null);
            if (env.sky != null) {
                Sky.STREAM_CODEC.encode(buf, env.sky);
            }
        }
    };
}
