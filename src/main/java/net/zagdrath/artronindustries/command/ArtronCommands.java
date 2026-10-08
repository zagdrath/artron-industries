/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/** {@code /artron tardis ...} test and admin commands. */
public final class ArtronCommands {
    private static final DynamicCommandExceptionType UNKNOWN_TARDIS = new DynamicCommandExceptionType(
            id -> Component.translatable("commands.artronindustries.tardis.unknown", id));
    private static final SimpleCommandExceptionType NO_SPACE = new SimpleCommandExceptionType(
            Component.translatable("commands.artronindustries.tardis.no_space"));
    private static final SimpleCommandExceptionType NO_EXTERIOR = new SimpleCommandExceptionType(
            Component.translatable("commands.artronindustries.tardis.no_exterior"));
    private static final SimpleCommandExceptionType NO_INTERIOR = new SimpleCommandExceptionType(
            Component.translatable("commands.artronindustries.tardis.no_interior"));
    private static final SimpleCommandExceptionType NOT_IN_TARDIS = new SimpleCommandExceptionType(
            Component.translatable("commands.artronindustries.tardis.not_inside"));

    private static final SuggestionProvider<CommandSourceStack> TARDIS_IDS = (ctx, builder) -> SharedSuggestionProvider.suggest(
            TardisInteriorManager.get(ctx.getSource().getServer()).all().stream().map(r -> Integer.toString(r.id())), builder);

    private ArtronCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /artron and /artronindustries are the same command tree.
        for (String root : new String[]{"artron", ArtronIndustries.MODID}) {
            dispatcher.register(build(root));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String root) {
        return Commands.literal(root)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("tardis")
                        .then(Commands.literal("create").executes(ctx -> create(ctx.getSource())))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("info")
                                .executes(ctx -> info(ctx.getSource(), current(ctx.getSource())))
                                .then(withId(ctx -> info(ctx.getSource(), tardis(ctx)))))
                        .then(Commands.literal("enter").then(withId(ctx -> enter(ctx.getSource(), tardis(ctx)))))
                        .then(Commands.literal("exit")
                                .executes(ctx -> exit(ctx.getSource(), current(ctx.getSource())))
                                .then(withId(ctx -> exit(ctx.getSource(), tardis(ctx)))))
                        .then(Commands.literal("door").then(Commands.argument("id", IntegerArgumentType.integer(1)).suggests(TARDIS_IDS)
                                .then(Commands.literal("open").executes(ctx -> door(ctx.getSource(), tardis(ctx), true)))
                                .then(Commands.literal("close").executes(ctx -> door(ctx.getSource(), tardis(ctx), false)))))
                        .then(Commands.literal("delete").then(withId(ctx -> delete(ctx.getSource(), tardis(ctx))))));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> withId(com.mojang.brigadier.Command<CommandSourceStack> command) {
        return Commands.argument("id", IntegerArgumentType.integer(1)).suggests(TARDIS_IDS).executes(command);
    }

    private static TardisRecord tardis(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        TardisRecord record = TardisInteriorManager.get(ctx.getSource().getServer()).byId(id);
        if (record == null) {
            throw UNKNOWN_TARDIS.create(id);
        }
        return record;
    }

    /** The TARDIS whose interior the source is standing in. */
    private static TardisRecord current(CommandSourceStack source) throws CommandSyntaxException {
        TardisRecord record = TardisInteriorManager.isInterior(source.getLevel())
                ? TardisInteriorManager.get(source.getServer()).byInteriorPos(BlockPos.containing(source.getPosition()))
                : null;
        if (record == null) {
            throw NOT_IN_TARDIS.create();
        }
        return record;
    }

    /** Places a test exterior door two blocks in front of the player, facing them, which allocates a new TARDIS. */
    private static int create(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Direction look = player.getDirection();
        BlockPos pos = player.blockPosition().relative(look, 2);
        TardisRecord record = ArtronBlocks.TEST_EXTERIOR_DOOR.get().placeNewTardis(source.getLevel(), pos, look.getOpposite());
        if (record == null) {
            throw NO_SPACE.create();
        }
        source.sendSuccess(() -> Component.translatable("commands.artronindustries.tardis.created", record.id(), pos.toShortString()), true);
        return record.id();
    }

    private static int list(CommandSourceStack source) {
        var all = TardisInteriorManager.get(source.getServer()).all();
        source.sendSuccess(() -> Component.translatable("commands.artronindustries.tardis.list", all.size()), false);
        for (TardisRecord r : all) {
            source.sendSuccess(() -> Component.literal(" #" + r.id() + "  cell " + r.cellIndex() + "  "
                    + (r.hasExterior() ? r.exteriorLevel().identifier() + " " + r.exteriorDoorPos().toShortString() : "no exterior")
                    + (r.doorOpen() ? "  [open]" : "")), false);
        }
        return all.size();
    }

    private static int info(CommandSourceStack source, TardisRecord r) {
        source.sendSuccess(() -> Component.literal("TARDIS #" + r.id() + " (" + r.uuid() + ")"), false);
        source.sendSuccess(() -> Component.literal(" cell " + r.cellIndex() + ", origin " + r.interiorOrigin().toShortString()
                + (r.interiorGenerated() ? "" : " (not generated yet)")), false);
        source.sendSuccess(() -> Component.literal(" interior door " + r.interiorDoorPos().toShortString() + " facing " + r.interiorDoorFacing()), false);
        source.sendSuccess(() -> Component.literal(r.hasExterior()
                ? " exterior " + r.exteriorLevel().identifier() + " " + r.exteriorDoorPos().toShortString() + " facing " + r.exteriorFacing()
                : " no exterior"), false);
        source.sendSuccess(() -> Component.literal(" door " + (r.doorOpen() ? "open" : "closed") + ", " + r.transform()), false);
        return r.id();
    }

    private static int enter(CommandSourceStack source, TardisRecord record) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        MinecraftServer server = source.getServer();
        TardisInteriorManager manager = TardisInteriorManager.get(server);
        if (!manager.ensureInterior(server, record)) {
            throw NO_INTERIOR.create();
        }
        teleportInFrontOf(player, TardisInteriorManager.interiorLevel(server), record, PortalSide.INTERIOR);
        return record.id();
    }

    private static int exit(CommandSourceStack source, TardisRecord record) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = TardisInteriorManager.level(source.getServer(), record, PortalSide.EXTERIOR);
        if (level == null || !record.hasExterior()) {
            throw NO_EXTERIOR.create();
        }
        teleportInFrontOf(player, level, record, PortalSide.EXTERIOR);
        return record.id();
    }

    /** Puts the player one block in front of the door on {@code side}, looking away from it. */
    private static void teleportInFrontOf(ServerPlayer player, ServerLevel level, TardisRecord record, PortalSide side) {
        Direction facing = record.doorFacing(side);
        Vec3 anchor = record.shape(side).anchor(record.doorPos(side), facing);
        Vec3 pos = anchor.add(facing.getStepX(), 0.0, facing.getStepZ());
        player.teleport(new TeleportTransition(level, pos, Vec3.ZERO, facing.toYRot(), 0.0F, TeleportTransition.DO_NOTHING));
    }

    private static int door(CommandSourceStack source, TardisRecord record, boolean open) throws CommandSyntaxException {
        if (open && !record.hasExterior()) {
            throw NO_EXTERIOR.create();
        }
        TardisInteriorManager.get(source.getServer()).setDoorOpen(source.getServer(), record, open);
        source.sendSuccess(() -> Component.translatable(open ? "commands.artronindustries.tardis.opened" : "commands.artronindustries.tardis.closed", record.id()), true);
        return record.id();
    }

    private static int delete(CommandSourceStack source, TardisRecord record) {
        TardisInteriorManager.get(source.getServer()).delete(source.getServer(), record);
        source.sendSuccess(() -> Component.translatable("commands.artronindustries.tardis.deleted", record.id()), true);
        return record.id();
    }
}
