/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import java.util.function.Supplier;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * What one end of a TARDIS doorway sounds like when its doors open and close. Each exterior and interior has its own;
 * a door plays its side's sound where it stands.
 */
public record DoorSounds(Supplier<SoundEvent> open, Supplier<SoundEvent> close) {
    /** For doorways with no sounds of their own. */
    public static final DoorSounds IRON_DOOR = new DoorSounds(() -> SoundEvents.IRON_DOOR_OPEN, () -> SoundEvents.IRON_DOOR_CLOSE);
}
