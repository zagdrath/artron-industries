/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.network.InteriorPresencePayload;
import net.zagdrath.artronindustries.tardis.interior.TardisInterior;
import net.zagdrath.artronindustries.tardis.interior.TardisInteriors;

/**
 * Loops the hum of the TARDIS interior the player is in ({@link InteriorPresencePayload}), fading it in on entering and
 * out on leaving. The hum is not positional (it fills the room) and plays on the ambient sound channel. Each hum file is a
 * whole number of cycles of the recording with a crossfaded seam, loaded into memory (not streamed) so OpenAL repeats it
 * with no gap.
 */
public final class InteriorHum {
    /** Ticks for a fade in or out. */
    private static final int FADE_TICKS = 30;

    private static Optional<Identifier> interior = Optional.empty();
    private static @Nullable Loop playing;
    private static int ticks;

    private InteriorHum() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RegisterClientPayloadHandlersEvent.class,
                e -> e.register(InteriorPresencePayload.TYPE, (payload, context) -> interior = payload.interior()));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> tick());
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            interior = Optional.empty();
            if (playing != null) {
                Minecraft.getInstance().getSoundManager().stop(playing);
                playing = null;
            }
        });
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        SoundEvent wanted = mc.player == null || ArtronClientConfig.HUM_VOLUME.get() <= 0.0
                ? null
                : interior.map(TardisInteriors::get).map(TardisInterior::hum).map(hum -> hum.get()).orElse(null);
        if (playing != null && playing.event != wanted) {
            // Left the interior (or moved to one with another hum): this loop fades out on its own and stops itself.
            ArtronIndustries.LOGGER.debug("Interior hum {} fading out", playing.event.location());
            playing.fadingOut = true;
            playing = null;
        }
        if (wanted == null) {
            return;
        }
        if (playing == null) {
            ArtronIndustries.LOGGER.debug("Interior hum {} starting", wanted.location());
            playing = new Loop(wanted, 0.0F);
            mc.getSoundManager().play(playing);
        } else if (++ticks % 20 == 0 && !mc.getSoundManager().isActive(playing)) {
            // The sound engine dropped it (sound device change, /stopsound, resource reload): start it again.
            playing = new Loop(wanted, playing.level);
            mc.getSoundManager().play(playing);
        }
    }

    /** One looping hum, its volume eased between 0 and the configured volume. */
    private static final class Loop extends AbstractTickableSoundInstance {
        final SoundEvent event;
        /** Fade level 0..1, multiplied by the configured hum volume. */
        float level;
        boolean fadingOut;

        Loop(SoundEvent event, float level) {
            super(event, SoundSource.AMBIENT, RandomSource.create());
            this.event = event;
            this.level = level;
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.0F;
        }

        @Override
        public void tick() {
            this.level = Math.clamp(this.level + (this.fadingOut ? -1.0F : 1.0F) / FADE_TICKS, 0.0F, 1.0F);
            float smooth = this.level * this.level * (3.0F - 2.0F * this.level);
            this.volume = Math.max(1.0E-4F, smooth * ArtronClientConfig.HUM_VOLUME.get().floatValue());
            if (this.fadingOut && this.level <= 0.0F) {
                this.stop();
            }
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
