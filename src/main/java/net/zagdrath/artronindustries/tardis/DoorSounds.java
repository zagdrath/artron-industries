/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import java.util.function.Supplier;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * What one end of a TARDIS doorway sounds like when its doors open and close, and how long its leaves take to swing,
 * which is how long those sounds last. Each exterior and interior has its own; a door plays its side's sound where it
 * stands and swings in step with it.
 *
 * @param swingTicks ticks a leaf takes to swing fully open or shut
 */
public record DoorSounds(Supplier<SoundEvent> open, Supplier<SoundEvent> close, int swingTicks) {
    /** How long doors with no sounds of their own take to swing. */
    public static final int DEFAULT_SWING_TICKS = 10;
    /** For doorways with no sounds of their own. */
    public static final DoorSounds IRON_DOOR = new DoorSounds(() -> SoundEvents.IRON_DOOR_OPEN, () -> SoundEvents.IRON_DOOR_CLOSE,
            DEFAULT_SWING_TICKS);
}
