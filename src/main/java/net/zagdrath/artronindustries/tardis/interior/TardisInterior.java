/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * One interior a TARDIS can be built with. It is generated around the TARDIS's cell origin the first time it is needed,
 * and puts its interior door (lower half) at {@code origin + doorOffset}, facing {@code doorFacing}.
 */
public record TardisInterior(Identifier id, BlockPos doorOffset, Direction doorFacing, Generator generator) {
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
