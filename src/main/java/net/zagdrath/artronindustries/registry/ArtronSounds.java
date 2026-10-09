/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.registry;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.artronindustries.ArtronIndustries;

/** Sound events. The sounds themselves are defined in {@code assets/artronindustries/sounds.json}. */
public final class ArtronSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, ArtronIndustries.MODID);

    /** The Victorian Parlour's interior hum: a seamless loop, played while a player is inside that interior. */
    public static final Supplier<SoundEvent> VICTORIAN_PARLOUR_HUM = register("interior_hum.victorian_parlour");

    private ArtronSounds() {}

    private static Supplier<SoundEvent> register(String name) {
        Identifier id = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, name);
        return SOUND_EVENTS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
