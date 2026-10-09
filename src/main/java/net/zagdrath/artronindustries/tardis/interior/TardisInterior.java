/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.tardis.DoorSounds;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * One interior a TARDIS can be built with. It is generated around the TARDIS's cell origin the first time it is needed,
 * and puts its interior door at {@code origin + doorOffset}, facing {@code doorFacing}, with the opening {@code doorShape}.
 * Its {@code hum}, if it has one, loops on the client of every player inside it; its door plays {@code doorSounds}.
 */
public record TardisInterior(Identifier id, BlockPos doorOffset, Direction doorFacing, PortalShape doorShape, Generator generator,
                             @Nullable Supplier<SoundEvent> hum, DoorSounds doorSounds) {
    public TardisInterior(Identifier id, BlockPos doorOffset, Direction doorFacing, PortalShape doorShape, Generator generator) {
        this(id, doorOffset, doorFacing, doorShape, generator, null, DoorSounds.IRON_DOOR);
    }

    /** This interior with {@code hum} looping while a player is inside it. */
    public TardisInterior withHum(Supplier<SoundEvent> hum) {
        return new TardisInterior(this.id, this.doorOffset, this.doorFacing, this.doorShape, this.generator, hum, this.doorSounds);
    }

    /** This interior with its doors sounding like {@code sounds}, heard inside. */
    public TardisInterior withDoorSounds(DoorSounds sounds) {
        return new TardisInterior(this.id, this.doorOffset, this.doorFacing, this.doorShape, this.generator, this.hum, sounds);
    }

    @FunctionalInterface
    public interface Generator {
        /** Builds the interior around {@code record.interiorOrigin()}, including the interior door at {@code record.interiorDoorPos()}. */
        void generate(ServerLevel level, TardisRecord record);
    }

    public BlockPos doorPos(BlockPos origin) {
        return origin.offset(this.doorOffset);
    }

    public void generate(ServerLevel level, TardisRecord record) {
        this.generator.generate(level, record);
    }

    @Override
    public String toString() {
        return this.id.toString();
    }
}
