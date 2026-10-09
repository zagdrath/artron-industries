/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.exterior;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;

/** The exteriors a TARDIS can have, by id. Saved ids that are no longer known load as {@link #DEFAULT}. */
public final class TardisExteriors {
    private static final Map<Identifier, TardisExterior> BY_ID = new LinkedHashMap<>();

    public static final TardisExterior HUDOLIN = register(new HudolinExterior(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "hudolin")));
    /** What a new TARDIS gets when nothing else is asked for. */
    public static final TardisExterior DEFAULT = HUDOLIN;

    public static final Codec<TardisExterior> CODEC = Identifier.CODEC.xmap(TardisExteriors::getOrDefault, TardisExterior::id);

    private TardisExteriors() {}

    private static <T extends TardisExterior> T register(T exterior) {
        if (BY_ID.putIfAbsent(exterior.id(), exterior) != null) {
            throw new IllegalStateException("Duplicate TARDIS exterior " + exterior.id());
        }
        return exterior;
    }

    public static @Nullable TardisExterior get(Identifier id) {
        return BY_ID.get(id);
    }

    public static TardisExterior getOrDefault(Identifier id) {
        return BY_ID.getOrDefault(id, DEFAULT);
    }

    public static Collection<TardisExterior> all() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }
}
