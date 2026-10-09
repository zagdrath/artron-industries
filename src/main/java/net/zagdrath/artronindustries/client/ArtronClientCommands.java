/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.zagdrath.artronindustries.client.boti.BotiClientCache;
import net.zagdrath.artronindustries.client.boti.BotiMeshCache;
import net.zagdrath.artronindustries.client.boti.BotiRenderer;

/** Client-side debug commands: {@code /artronclient boti debug here|off} and {@code /artronclient boti stats}. */
public final class ArtronClientCommands {
    private ArtronClientCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("artronclient").then(Commands.literal("boti")
                .then(Commands.literal("debug")
                        .then(Commands.literal("here").executes(ctx -> {
                            // Floats the first cached view (unmasked) a few blocks in front of the player.
                            var player = Minecraft.getInstance().player;
                            BlockPos pos = player.blockPosition().relative(player.getDirection(), 3);
                            BotiRenderer.setDebugFloatingPos(pos);
                            ctx.getSource().sendSuccess(() -> Component.literal("BOTI debug view at " + pos.toShortString()), false);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            BotiRenderer.setDebugFloatingPos(null);
                            ctx.getSource().sendSuccess(() -> Component.literal("BOTI debug view off"), false);
                            return 1;
                        })))
                .then(Commands.literal("stats").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                            "BOTI: %d cached views, %d meshes, last rebuild %.2f ms (%d sections), last frame %d doorways (%d sections) in %.3f ms%s",
                            BotiClientCache.entries().size(), BotiMeshCache.meshCount(), BotiMeshCache.lastRebuildMs(),
                            BotiMeshCache.lastRebuildSections(), BotiRenderer.lastDrawCount(), BotiRenderer.lastSectionCount(),
                            BotiRenderer.lastDrawMs(), BotiRenderer.usesFallback() ? " (fallback)" : "")), false);
                    return 1;
                }))));
    }
}
