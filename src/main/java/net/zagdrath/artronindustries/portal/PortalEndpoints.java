/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.portal;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;
import net.minecraft.world.level.Level;

/**
 * Tracks every loaded {@link PortalEndpoint}, per level, on both logical sides. Endpoints add themselves when loaded and
 * remove themselves when removed or unloaded. The client renderer and the server crossing detector iterate these
 * instead of being tied to any particular block.
 */
public final class PortalEndpoints {
    private static final Map<Level, Set<PortalEndpoint>> BY_LEVEL = Collections.synchronizedMap(new WeakHashMap<>());

    private PortalEndpoints() {}

    public static void add(Level level, PortalEndpoint endpoint) {
        BY_LEVEL.computeIfAbsent(level, l -> new ReferenceLinkedOpenHashSet<>()).add(endpoint);
    }

    public static void remove(Level level, PortalEndpoint endpoint) {
        Set<PortalEndpoint> set = BY_LEVEL.get(level);
        if (set != null) {
            set.remove(endpoint);
        }
    }

    /** Live view of the endpoints in {@code level}. Only iterate on that level's thread. */
    public static Collection<PortalEndpoint> in(Level level) {
        Set<PortalEndpoint> set = BY_LEVEL.get(level);
        if (set == null) {
            return Collections.emptySet();
        }
        set.removeIf(PortalEndpoint::isPortalRemoved);
        return set;
    }
}
