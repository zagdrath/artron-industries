/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.zagdrath.artronindustries.ArtronIndustries;

/** Keys for the datapack-defined interior dimension ({@code data/artronindustries/dimension/tardis_interiors.json}). */
public final class ArtronDimensions {
    public static final Identifier TARDIS_INTERIORS_ID = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "tardis_interiors");
    public static final ResourceKey<Level> TARDIS_INTERIORS = ResourceKey.create(Registries.DIMENSION, TARDIS_INTERIORS_ID);
    public static final ResourceKey<DimensionType> TARDIS_INTERIORS_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, TARDIS_INTERIORS_ID);

    private ArtronDimensions() {}
}
