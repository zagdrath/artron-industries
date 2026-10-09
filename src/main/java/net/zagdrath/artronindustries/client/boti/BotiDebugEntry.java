/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.debug.DebugScreenProfile;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.zagdrath.artronindustries.ArtronIndustries;

/** F3 line: cached views and their snapshot size, last mesh rebuild time and last frame's BOTI draw time. */
public final class BotiDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti");

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(RegisterDebugEntriesEvent.class, e -> {
            e.register(ID, new BotiDebugEntry());
            e.includeInProfile(ID, DebugScreenProfile.DEFAULT, DebugScreenEntryStatus.IN_OVERLAY);
            e.includeInProfile(ID, DebugScreenProfile.PERFORMANCE, DebugScreenEntryStatus.IN_OVERLAY);
        });
    }

    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
        int views = 0;
        long bytes = 0;
        for (var entry : BotiClientCache.entries()) {
            views++;
            bytes += entry.getValue().bytes();
        }
        displayer.addLine(String.format("BOTI: %d views (%.1f KB), rebuild %.2f ms / %d sections, draw %.3f ms / %d doorways, %d sections%s",
                views, bytes / 1024.0, BotiMeshCache.lastRebuildMs(), BotiMeshCache.lastRebuildSections(), BotiRenderer.lastDrawMs(),
                BotiRenderer.lastDrawCount(), BotiRenderer.lastSectionCount(), BotiRenderer.usesFallback() ? " (fallback)" : ""));
    }
}
