/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.registry.ArtronSounds;
import net.zagdrath.artronindustries.tardis.DoorSounds;
import net.zagdrath.artronindustries.tardis.InteriorGenerator;

/** The interiors a TARDIS can be built with, by id. Saved ids that are no longer known load as {@link #DEFAULT}. */
public final class TardisInteriors {
    private static final Map<Identifier, TardisInterior> BY_ID = new LinkedHashMap<>();

    /**
     * The starter room, built in code to exercise BOTI (tinted, translucent and cutout blocks, block entities): the
     * interior door in the middle of the south wall, facing into the room.
     */
    public static final TardisInterior STARTER = register(new TardisInterior(id("starter"),
            new BlockPos(0, 1, InteriorGenerator.HALF + 1), Direction.NORTH, PortalShape.DEFAULT_DOOR, InteriorGenerator::generate));
    /**
     * The Victorian Parlour. Its door is the 4x4 pair of gray leaves (with their button panelling in front) in the south
     * wall, facing north into the room, drawn as half-block slabs. It hums like the 1996 TARDIS.
     */
    public static final TardisInterior VICTORIAN_PARLOUR = register(TemplateInterior.create(id("victorian_parlour"), id("victorian_parlour"),
            new BlockPos(12, 2, 38), new BlockPos(15, 5, 39), Direction.NORTH, 0.5F)
            .withHum(ArtronSounds.VICTORIAN_PARLOUR_HUM)
            .withDoorSounds(new DoorSounds(ArtronSounds.VICTORIAN_PARLOUR_DOOR_OPEN, ArtronSounds.VICTORIAN_PARLOUR_DOOR_CLOSE)));
    /** What a new TARDIS gets when nothing else is asked for. */
    public static final TardisInterior DEFAULT = VICTORIAN_PARLOUR;

    public static final Codec<TardisInterior> CODEC = Identifier.CODEC.xmap(TardisInteriors::getOrDefault, TardisInterior::id);

    private TardisInteriors() {}

    private static TardisInterior register(TardisInterior interior) {
        if (BY_ID.putIfAbsent(interior.id(), interior) != null) {
            throw new IllegalStateException("Duplicate TARDIS interior " + interior.id());
        }
        return interior;
    }

    public static @Nullable TardisInterior get(Identifier id) {
        return BY_ID.get(id);
    }

    public static TardisInterior getOrDefault(Identifier id) {
        return BY_ID.getOrDefault(id, DEFAULT);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, path);
    }

    public static Collection<TardisInterior> all() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }
}
