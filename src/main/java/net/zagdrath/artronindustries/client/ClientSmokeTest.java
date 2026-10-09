/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.RoundelBlock;
import net.zagdrath.artronindustries.client.boti.BotiClientCache;
import net.zagdrath.artronindustries.client.boti.BotiMeshCache;
import net.zagdrath.artronindustries.client.boti.BotiRenderer;
import net.zagdrath.artronindustries.gametest.SmokeTest;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.TardisRecord;
import net.zagdrath.artronindustries.tardis.exterior.TardisExteriors;
import net.zagdrath.artronindustries.tardis.interior.TardisInteriors;

/**
 * Automated client walkthrough used during development ({@code ./gradlew runClient -Partronindustries.clientSmokeTest=true
 * --args="--quickPlaySingleplayer botitest"}). Sets up a TARDIS in front of the player on the integrated server, poses the
 * camera, saves screenshots to {@code run/client/screenshots/boti_*.png} and quits. Not active in normal play.
 */
public final class ClientSmokeTest {
    public static final String PROPERTY = "artronindustries.clientSmokeTest";

    private record Step(String name, Runnable action, @Nullable BooleanSupplier until, int delay) {}

    private final List<Step> steps = new ArrayList<>();
    private int index;
    private int wait = 40;
    /** Ticks the parlour's doors take to swing; shots part-way through wait half of it. */
    private static final int PARLOUR_SWING = TardisInteriors.VICTORIAN_PARLOUR.doorSounds().swingTicks();
    private int timeout = 600;
    private boolean started;
    private @Nullable TardisRecord record;
    /** A Victorian Parlour TARDIS, for its doorway. */
    private @Nullable TardisRecord parlour;
    /** A starter-room TARDIS with a Hell Bent door placed as its interior door. */
    private @Nullable TardisRecord hellBent;
    /** Ticks the Hell Bent door's leaves take to swing. */
    private static final int HELL_BENT_SWING = net.zagdrath.artronindustries.block.HellBentDoorBlock.SOUNDS.swingTicks();
    /** The outside corner of the roundel walls: wall A runs west from it facing south, wall B north from it facing east. */
    private BlockPos roundelCorner = BlockPos.ZERO;
    private BlockPos ground = BlockPos.ZERO;

    private ClientSmokeTest() {}

    public static void registerIfEnabled() {
        if (Boolean.getBoolean(PROPERTY)) {
            ClientSmokeTest test = new ClientSmokeTest();
            test.define();
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> test.tick());
        }
    }

    private void step(String name, Runnable action, @Nullable BooleanSupplier until, int delay) {
        this.steps.add(new Step(name, action, until, delay));
    }

    private static MinecraftServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    private static void onServer(java.util.function.Consumer<MinecraftServer> action) {
        MinecraftServer server = server();
        server.execute(() -> action.accept(server));
    }

    /** Teleports the player (server side) to {@code pos} looking at {@code target}. */
    private static void place(ServerLevel level, Vec3 pos, Vec3 target) {
        Vec3 d = target.subtract(pos.add(0.0, 1.62, 0.0));
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        ServerPlayer player = level.getServer().getPlayerList().getPlayers().getFirst();
        if (player.level() != level) {
            player.teleportTo(level, pos.x, pos.y, pos.z, java.util.Set.of(), yaw, pitch, false);
        } else {
            player.connection.teleport(pos.x, pos.y, pos.z, yaw, pitch);
        }
    }

    /** After walking straight through a doorway the player must face straight out of the far door, not turned. */
    private static void checkFacing(String what, Direction expected) {
        float yaw = Minecraft.getInstance().player.getYRot();
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - expected.toYRot()));
        if (off > 15.0F) {
            ArtronIndustries.LOGGER.error("CLIENT SMOKE FAILED: after {} the player faces {} degrees, expected {} ({})", what, yaw, expected.toYRot(), expected);
        } else {
            ArtronIndustries.LOGGER.info("CLIENT SMOKE {} facing ok ({} degrees off)", what, off);
        }
    }

    /**
     * Moves the player {@code distance} blocks the way it faces. Held movement keys only move it while the window has
     * focus, which a scripted run cannot count on.
     */
    private static void walk(double distance) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.gui.screen() == null) {
            mc.player.setPos(mc.player.position().add(Vec3.directionFromRotation(0.0F, mc.player.getYRot()).scale(distance)));
        }
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "boti_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                msg -> ArtronIndustries.LOGGER.info("CLIENT SMOKE screenshot {}: {}", name, msg.getString()));
    }

    private boolean viewReady(PortalSide nearSide) {
        return viewReady(this.record, nearSide);
    }

    private static boolean viewReady(@Nullable TardisRecord record, PortalSide nearSide) {
        if (record == null) {
            return false;
        }
        var view = BotiClientCache.get(new net.zagdrath.artronindustries.boti.PortalViewKey(record.uuid(), nearSide));
        return view != null && !view.isMeshDirty() && BotiMeshCache.meshCount() > 0 && BotiRenderer.lastDrawCount() > 0;
    }

    /** Eye position half a block in front of the exterior doorway, at a height inside the opening. */
    private Vec3 nearEye() {
        Vec3 anchor = this.record.exteriorShape().anchor(this.record.exteriorDoorPos(), this.record.exteriorFacing());
        return anchor.add(Vec3.atLowerCornerOf(this.record.exteriorFacing().getUnitVec3i()).scale(0.5)).add(0.0, 1.5, 0.0);
    }

    private Vec3 parlourDoor() {
        return this.parlour.shape(PortalSide.INTERIOR).center(this.parlour.interiorDoorPos(), this.parlour.interiorDoorFacing());
    }

    private Vec3 parlourFacing() {
        return Vec3.atLowerCornerOf(this.parlour.interiorDoorFacing().getUnitVec3i());
    }

    private Vec3 doorCenter(PortalSide side) {
        return this.record.shape(side).center(this.record.doorPos(side), this.record.doorFacing(side));
    }

    private Vec3 hellBentDoor() {
        return this.hellBent.shape(PortalSide.INTERIOR).center(this.hellBent.interiorDoorPos(), this.hellBent.interiorDoorFacing());
    }

    private Vec3 hellBentFacing() {
        return Vec3.atLowerCornerOf(this.hellBent.interiorDoorFacing().getUnitVec3i());
    }

    private Vec3 hellBentRight() {
        return Vec3.atLowerCornerOf(PortalShape.right(this.hellBent.interiorDoorFacing()).getUnitVec3i());
    }

    private static boolean renderedIn(boolean interior) {
        Minecraft mc = Minecraft.getInstance();
        return TardisInteriorManager.isInterior(mc.level) == interior && mc.levelRenderer.hasRenderedAllSections();
    }

    /** The outside corner's vertical edge, at eye height, and a point looking at it from the south-east. */
    private Vec3 outsideCornerEdge() {
        return Vec3.atLowerCornerOf(this.roundelCorner).add(1.0, 1.5, 1.0);
    }

    private Vec3 outsideCornerEye() {
        return this.outsideCornerEdge().add(2.0, -1.4, 2.0);
    }

    /** The inside corner's edge, where wall C's front meets wall A's, and a point looking into it. */
    private Vec3 insideCornerEdge() {
        return Vec3.atLowerCornerOf(this.roundelCorner).add(-5.0, 1.5, 1.0);
    }

    private Vec3 insideCornerEye() {
        return this.insideCornerEdge().add(2.2, -1.4, 2.2);
    }

    /** A roundel placed as a player would place it, its offset from where it is along the wall. */
    private static void roundel(ServerLevel level, BlockPos pos, Direction facing) {
        level.setBlockAndUpdate(pos, ArtronBlocks.ROUNDEL.get().defaultBlockState().setValue(RoundelBlock.FACING, facing)
                .setValue(RoundelBlock.OFFSET, RoundelBlock.offsetAt(pos, facing)));
    }

    /** Sneak-uses {@code pos} with an empty hand, as a player fixing a corner would. */
    private static void sneakUse(ServerLevel level, BlockPos pos, Direction face) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayers().getFirst();
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        player.setShiftKeyDown(true);
        level.getBlockState(pos).useWithoutItem(level, player, new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(pos), face, pos, false));
        player.setShiftKeyDown(false);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
    }

    /**
     * Whether the faces either side of the edge between {@code a}'s {@code aFace} and {@code b}'s {@code bFace} (two faces in
     * one plane, side by side) show different roundel textures, as the stagger needs.
     */
    private static boolean staggers(ServerLevel level, BlockPos a, Direction aFace, BlockPos b, Direction bFace) {
        return showsWhole(level.getBlockState(a), aFace) != showsWhole(level.getBlockState(b), bFace);
    }

    /** Whether {@code face} of a roundel block shows the whole roundel (rather than the shifted one). */
    private static boolean showsWhole(net.minecraft.world.level.block.state.BlockState state, Direction face) {
        boolean front = face == state.getValue(RoundelBlock.FACING);
        return front != state.getValue(RoundelBlock.OFFSET);
    }

    private void define() {
        this.step("setup", () -> onServer(server -> {
            ServerLevel level = server.overworld();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
            this.ground = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(0, 0, -4));
            BlockPos door = this.ground;
            // A lapis "box" around and behind the doorway: any leak of the view outside the doorway would show on it.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlockAndUpdate(door.offset(dx, dy, -1), Blocks.LAPIS_BLOCK.defaultBlockState());
                    // The whole column is left clear: the TARDIS takes three blocks.
                    boolean opening = dx == 0;
                    level.setBlockAndUpdate(door.offset(dx, dy, 0), opening ? Blocks.AIR.defaultBlockState() : Blocks.LAPIS_BLOCK.defaultBlockState());
                }
            }
            // The walkthrough is built around the starter room, made to exercise BOTI.
            this.record = ArtronBlocks.TARDIS.get().placeNewTardis(level, door, Direction.SOUTH, TardisExteriors.DEFAULT, TardisInteriors.STARTER);
            // In the last hotbar slot, so every screenshot also shows the TARDIS item model.
            server.getPlayerList().getPlayers().getFirst().getInventory().setItem(8, ArtronBlocks.TARDIS_ITEM.get().getDefaultInstance());
            TardisInteriorManager.get(server).setDoorOpen(server, this.record, true);
            place(level, Vec3.atBottomCenterOf(door).add(0.0, 0.0, 5.0), this.doorCenter(PortalSide.EXTERIOR));
        }), null, 20);
        this.step("front", () -> shot("front"), () -> this.viewReady(PortalSide.EXTERIOR), 40);
        this.step("f3", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.debugEntries.setStatus(net.zagdrath.artronindustries.client.boti.BotiDebugEntry.ID,
                    net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.IN_OVERLAY);
            mc.debugEntries.setOverlayVisible(true);
        }, null, 0);
        this.step("f3_shot", () -> {
            shot("f3");
            Minecraft.getInstance().debugEntries.setOverlayVisible(false);
        }, null, 10);
        this.step("angle_left", () -> onServer(server -> place(server.overworld(),
                Vec3.atBottomCenterOf(this.record.exteriorDoorPos()).add(-3.0, 0.0, 4.0), this.doorCenter(PortalSide.EXTERIOR))), null, 0);
        this.step("angle_left_shot", () -> shot("angle_left"), null, 20);
        this.step("close", () -> onServer(server -> place(server.overworld(),
                Vec3.atBottomCenterOf(this.record.exteriorDoorPos()).add(0.6, 0.0, 1.6), this.doorCenter(PortalSide.EXTERIOR).add(0.0, -0.3, 0.0))), null, 0);
        this.step("close_shot", () -> shot("close"), null, 20);
        this.step("grazing", () -> onServer(server -> place(server.overworld(),
                Vec3.atBottomCenterOf(this.record.exteriorDoorPos()).add(4.0, 0.0, 1.2), this.doorCenter(PortalSide.EXTERIOR))), null, 0);
        this.step("grazing_shot", () -> shot("grazing"), null, 20);
        // Block entities through the doorway: aim at the starter room's chest (4, 1, 4 from the interior origin).
        this.step("chest", () -> onServer(server -> {
            DoorPairTransform toExterior = this.record.transform().invert();
            Vec3 chest = toExterior.apply(Vec3.atCenterOf(this.record.interiorOrigin().offset(4, 1, 4)));
            Vec3 anchor = this.record.exteriorShape().anchor(this.record.exteriorDoorPos(), this.record.exteriorFacing());
            Vec3 viaDoor = anchor.add(0.0, 1.0, 0.0);
            Vec3 eye = viaDoor.add(viaDoor.subtract(chest).normalize().scale(2.5));
            place(server.overworld(), eye.subtract(0.0, 1.62, 0.0), chest);
        }), null, 0);
        this.step("chest_shot", () -> shot("chest"), null, 30);
        this.step("behind", () -> onServer(server -> place(server.overworld(),
                Vec3.atBottomCenterOf(this.record.exteriorDoorPos()).add(0.0, 0.0, -5.0), this.doorCenter(PortalSide.EXTERIOR))), null, 0);
        this.step("behind_shot", () -> shot("behind"), null, 20);
        // Same viewpoint twice: just in front of the exterior doorway (BOTI), and the corresponding point just behind the
        // interior doorway (the real interior). Inside the doorway the two images should match.
        this.step("near_boti", () -> onServer(server -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator @a");
            Vec3 eye = this.nearEye();
            place(server.overworld(), eye.subtract(0.0, 1.62, 0.0), eye.add(Vec3.atLowerCornerOf(this.record.exteriorFacing().getOpposite().getUnitVec3i())));
        }), null, 0);
        this.step("near_boti_shot", () -> shot("near_boti"), null, 40);
        this.step("near_reference", () -> onServer(server -> {
            DoorPairTransform t = this.record.transform();
            Vec3 eye = this.nearEye();
            Vec3 look = eye.add(Vec3.atLowerCornerOf(this.record.exteriorFacing().getOpposite().getUnitVec3i()));
            place(TardisInteriorManager.interiorLevel(server), t.apply(eye).subtract(0.0, 1.62, 0.0), t.apply(look));
        }), null, 0);
        this.step("near_reference_shot", () -> shot("near_reference"), () -> {
            Minecraft mc = Minecraft.getInstance();
            return TardisInteriorManager.isInterior(mc.level) && mc.levelRenderer.hasRenderedAllSections();
        }, 40);
        // Phase 5: from inside the TARDIS looking out, with mobs and a few landmarks outside.
        this.step("outside_setup", () -> onServer(server -> {
            ServerLevel level = server.overworld();
            var cmd = server.getCommands();
            var src = server.createCommandSourceStack();
            BlockPos d = this.record.exteriorDoorPos();
            cmd.performPrefixedCommand(src, "gamemode creative @a");
            cmd.performPrefixedCommand(src, String.format("summon cow %d %d %d", d.getX() + 1, d.getY(), d.getZ() + 6));
            cmd.performPrefixedCommand(src, String.format("summon sheep %d %d %d {Color:14}", d.getX() - 2, d.getY(), d.getZ() + 9));
            cmd.performPrefixedCommand(src, String.format("summon armor_stand %d %d %d", d.getX(), d.getY(), d.getZ() + 4));
            for (int i = 0; i < 6; i++) {
                level.setBlockAndUpdate(d.offset(-4, i, 14), Blocks.OAK_LOG.defaultBlockState());
                level.setBlockAndUpdate(d.offset(4, i, 18), Blocks.STONE_BRICKS.defaultBlockState());
            }
            level.setBlockAndUpdate(d.offset(2, 0, 3), Blocks.TORCH.defaultBlockState());
            Vec3 inDoor = this.doorCenter(PortalSide.INTERIOR);
            Vec3 facing = Vec3.atLowerCornerOf(this.record.interiorDoorFacing().getUnitVec3i());
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(facing.scale(3.0)).subtract(0.0, 1.0, 0.0), inDoor);
        }), null, 0);
        this.step("inside_out_shot", () -> shot("inside_out"), () -> this.viewReady(PortalSide.INTERIOR)
                && TardisInteriorManager.isInterior(Minecraft.getInstance().level) && Minecraft.getInstance().levelRenderer.hasRenderedAllSections(), 60);
        this.step("inside_out_close", () -> onServer(server -> {
            Vec3 inDoor = this.doorCenter(PortalSide.INTERIOR);
            Vec3 facing = Vec3.atLowerCornerOf(this.record.interiorDoorFacing().getUnitVec3i());
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(facing.scale(1.2)).subtract(0.0, 1.0, 0.0), inDoor.subtract(facing.scale(3.0)));
        }), null, 0);
        this.step("inside_out_close_shot", () -> {
            shot("inside_out_close");
            onServer(server -> server.overworld().getEntities((net.minecraft.world.entity.Entity) null,
                    new net.minecraft.world.phys.AABB(this.record.exteriorDoorPos()).inflate(20), e -> !(e instanceof ServerPlayer))
                    .forEach(e -> ArtronIndustries.LOGGER.info("CLIENT SMOKE outside entity {} at {}", e.getType(), e.position())));
        }, null, 40);
        this.step("entities_close", () -> onServer(server -> {
            Vec3 inDoor = this.doorCenter(PortalSide.INTERIOR);
            Vec3 facing = Vec3.atLowerCornerOf(this.record.interiorDoorFacing().getUnitVec3i());
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(facing.scale(0.6)).subtract(0.0, 1.5, 0.0),
                    inDoor.subtract(facing.scale(4.0)).subtract(0.0, 1.2, 0.0));
        }), null, 0);
        this.step("entities_close_shot", () -> shot("entities_close"), null, 100);
        this.step("dusk", () -> onServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 12800")), null, 0);
        this.step("inside_out_dusk_shot", () -> shot("inside_out_dusk"), null, 80);
        this.step("night", () -> onServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 18000")), null, 0);
        this.step("inside_out_night_shot", () -> shot("inside_out_night"), null, 80);
        this.step("noon_again", () -> onServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon")), null, 0);
        // Phase 6: walk (sprint) in through the exterior door, walk back out, throw an item through.
        this.step("walk_in_setup", () -> onServer(server -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "kill @e[type=!player]");
            Vec3 anchor = this.record.exteriorShape().anchor(this.record.exteriorDoorPos(), this.record.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.record.exteriorFacing().getUnitVec3i());
            place(server.overworld(), anchor.add(out.scale(4.0)), anchor.add(0.0, 1.62, 0.0).subtract(out.scale(2.0)));
        }), null, 0);
        this.step("walk_in_go", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(true);
            mc.options.keySprint.setDown(true);
        }, () -> !TardisInteriorManager.isInterior(Minecraft.getInstance().level) && Minecraft.getInstance().gui.screen() == null, 30);
        for (int i = 0; i < 16; i++) {
            String name = String.format("walk_in_%02d", i);
            this.step(name, () -> {
                // Screens release keys; a real player keeps holding them.
                Minecraft.getInstance().options.keyUp.setDown(true);
                walk(0.4);
                Minecraft.getInstance().options.keySprint.setDown(true);
                shot(name);
            }, null, 2);
        }
        this.step("walk_in_stop", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            ArtronIndustries.LOGGER.info("CLIENT SMOKE walked in: now in {} at {}", mc.level.dimension().identifier(), mc.player.position());
            checkFacing("walk in", this.record.interiorDoorFacing());
        }, null, 10);
        this.step("walk_out_setup", () -> onServer(server -> {
            Vec3 anchor = this.record.interiorShape().anchor(this.record.interiorDoorPos(), this.record.interiorDoorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.record.interiorDoorFacing().getUnitVec3i());
            place(TardisInteriorManager.interiorLevel(server), anchor.add(out.scale(3.0)), anchor.add(0.0, 1.62, 0.0).subtract(out.scale(2.0)));
        }), () -> TardisInteriorManager.isInterior(Minecraft.getInstance().level), 20);
        this.step("walk_out_go", () -> Minecraft.getInstance().options.keyUp.setDown(true), null, 30);
        for (int i = 0; i < 16; i++) {
            String name = String.format("walk_out_%02d", i);
            this.step(name, () -> {
                Minecraft.getInstance().options.keyUp.setDown(true);
                walk(0.3);
                shot(name);
            }, null, 2);
        }
        this.step("walk_out_stop", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(false);
            ArtronIndustries.LOGGER.info("CLIENT SMOKE walked out: now in {} at {}", mc.level.dimension().identifier(), mc.player.position());
            checkFacing("walk out", this.record.exteriorFacing());
        }, null, 10);
        this.step("throw_item", () -> onServer(server -> {
            // Out of the way, so the (creative) player does not pick the item up.
            place(server.overworld(), Vec3.atBottomCenterOf(this.record.exteriorDoorPos()).add(6.0, 0.0, 6.0),
                    this.doorCenter(PortalSide.EXTERIOR));
            Vec3 anchor = this.record.exteriorShape().anchor(this.record.exteriorDoorPos(), this.record.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.record.exteriorFacing().getUnitVec3i());
            Vec3 from = anchor.add(out.scale(2.0)).add(0.0, 1.0, 0.0);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), String.format(java.util.Locale.ROOT,
                    "summon item %.2f %.2f %.2f {Item:{id:\"minecraft:diamond\",count:1},Motion:[%.2f,0.15,%.2f]}",
                    from.x, from.y, from.z, -out.x * 0.45, -out.z * 0.45));
        }), null, 20);
        this.step("item_check", () -> onServer(server -> {
            var interior = TardisInteriorManager.interiorLevel(server);
            var items = interior.getEntities((net.minecraft.world.entity.Entity) null,
                    new net.minecraft.world.phys.AABB(this.record.interiorDoorPos()).inflate(8), e -> e instanceof net.minecraft.world.entity.item.ItemEntity);
            var outside = server.overworld().getEntities((net.minecraft.world.entity.Entity) null,
                    new net.minecraft.world.phys.AABB(this.record.exteriorDoorPos()).inflate(8), e -> e instanceof net.minecraft.world.entity.item.ItemEntity);
            ArtronIndustries.LOGGER.info("CLIENT SMOKE item through door: {} inside, {} outside", items.size(), outside.size());
        }), null, 60);
        this.step("debug_floating", () -> {
            onServer(server -> place(server.overworld(), Vec3.atBottomCenterOf(this.ground).add(0.0, 2.0, 30.0),
                    Vec3.atBottomCenterOf(this.ground).add(0.0, 4.0, 10.0)));
            BotiRenderer.setDebugFloatingPos(this.ground.offset(-12, -2, 6));
        }, null, 0);
        this.step("debug_floating_shot", () -> shot("debug_floating"), () -> !TardisInteriorManager.isInterior(Minecraft.getInstance().level)
                && Minecraft.getInstance().levelRenderer.hasRenderedAllSections(), 60);
        // Phase 8: the Victorian Parlour's doorway, built from the template's own blocks, opening leaf by leaf.
        this.step("parlour_setup", () -> onServer(server -> {
            ServerLevel level = server.overworld();
            BlockPos door = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, this.ground.offset(16, 0, 0));
            for (int dy = 0; dy < 3; dy++) {
                level.setBlockAndUpdate(door.above(dy), Blocks.AIR.defaultBlockState());
            }
            this.parlour = ArtronBlocks.TARDIS.get().placeNewTardis(level, door, Direction.SOUTH, TardisExteriors.DEFAULT, TardisInteriors.VICTORIAN_PARLOUR);
            Vec3 inDoor = this.parlourDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.parlourFacing().scale(7.0)).subtract(0.0, 1.0, 0.0), inDoor);
        }), null, 0);
        this.step("parlour_shut_shot", () -> shot("parlour_shut"), () -> TardisInteriorManager.isInterior(Minecraft.getInstance().level)
                && Minecraft.getInstance().levelRenderer.hasRenderedAllSections(), 60);
        this.step("parlour_right", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.parlour, DoorState.RIGHT_OPEN)), null, 0);
        this.step("parlour_opening_shot", () -> shot("parlour_opening"), null, PARLOUR_SWING / 2);
        this.step("parlour_right_shot", () -> shot("parlour_right_open"), () -> viewReady(this.parlour, PortalSide.INTERIOR), PARLOUR_SWING / 2 + 5);
        this.step("parlour_both", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.parlour, DoorState.BOTH_OPEN)), null, 0);
        this.step("parlour_open_shot", () -> shot("parlour_open"), () -> viewReady(this.parlour, PortalSide.INTERIOR), PARLOUR_SWING + 5);
        // Looking up and out through the open doors: the outside's clouds by day, its moon and stars by night.
        this.step("parlour_sky", () -> onServer(server -> {
            Vec3 inDoor = this.parlourDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.parlourFacing().scale(2.5)).subtract(0.0, 2.0, 0.0),
                    inDoor.subtract(this.parlourFacing().scale(6.0)).add(0.0, 7.0, 0.0));
        }), null, 0);
        this.step("parlour_sky_shot", () -> shot("parlour_sky"), null, 40);
        this.step("parlour_midnight", () -> onServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 18000")), null, 0);
        this.step("parlour_night_shot", () -> shot("parlour_night_sky"), null, 60);
        this.step("parlour_dusk", () -> onServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 12700")), null, 0);
        this.step("parlour_dusk_shot", () -> shot("parlour_dusk_sky"), null, 60);
        this.step("parlour_noon", () -> onServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000")), null, 0);
        this.step("parlour_angle", () -> onServer(server -> {
            Vec3 inDoor = this.parlourDoor();
            Vec3 right = Vec3.atLowerCornerOf(net.zagdrath.artronindustries.portal.PortalShape.right(this.parlour.interiorDoorFacing()).getUnitVec3i());
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.parlourFacing().scale(4.0)).add(right.scale(4.0)).subtract(0.0, 1.0, 0.0), inDoor);
        }), null, 0);
        this.step("parlour_angle_shot", () -> shot("parlour_angle"), null, 30);
        this.step("parlour_room", () -> onServer(server -> {
            Vec3 inDoor = this.parlourDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.parlourFacing().scale(1.5)).subtract(0.0, 1.0, 0.0),
                    inDoor.add(this.parlourFacing().scale(20.0)));
        }), null, 0);
        this.step("parlour_room_shot", () -> shot("parlour_room"), null, 30);
        this.step("parlour_outside", () -> onServer(server -> {
            Vec3 exDoor = this.parlour.exteriorShape().center(this.parlour.exteriorDoorPos(), this.parlour.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.parlour.exteriorFacing().getUnitVec3i());
            place(server.overworld(), exDoor.add(out.scale(3.0)).subtract(0.0, 1.3, 0.0), exDoor);
        }), null, 0);
        this.step("parlour_outside_shot", () -> shot("parlour_outside_in"), () -> viewReady(this.parlour, PortalSide.EXTERIOR)
                && !TardisInteriorManager.isInterior(Minecraft.getInstance().level), 60);
        // From outside, part-way through opening and closing: the parlour's own doors must never fill the doorway.
        this.step("parlour_shut_again", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.parlour, DoorState.CLOSED)), null, 0);
        this.step("parlour_reopen", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.parlour, DoorState.BOTH_OPEN)), null, PARLOUR_SWING + 5);
        this.step("parlour_outside_opening_shot", () -> shot("parlour_outside_opening"), null, PARLOUR_SWING / 2);
        this.step("parlour_reclose", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.parlour, DoorState.CLOSED)), null, PARLOUR_SWING + 5);
        this.step("parlour_outside_closing_shot", () -> shot("parlour_outside_closing"), null, PARLOUR_SWING / 2);
        // The police box's lit windows at night, doors shut.
        this.step("exterior_night", () -> onServer(server -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 18000");
            TardisInteriorManager.get(server).setDoorState(server, this.parlour, DoorState.CLOSED);
            Vec3 exDoor = this.parlour.exteriorShape().center(this.parlour.exteriorDoorPos(), this.parlour.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.parlour.exteriorFacing().getUnitVec3i());
            Vec3 side = Vec3.atLowerCornerOf(net.zagdrath.artronindustries.portal.PortalShape.right(this.parlour.exteriorFacing()).getUnitVec3i());
            place(server.overworld(), exDoor.add(out.scale(4.0)).add(side.scale(2.5)).subtract(0.0, 1.3, 0.0), exDoor.add(0.0, 0.3, 0.0));
        }), null, 0);
        this.step("exterior_night_shot", () -> shot("exterior_night"), () -> !TardisInteriorManager.isInterior(Minecraft.getInstance().level)
                && Minecraft.getInstance().levelRenderer.hasRenderedAllSections(), 60);
        this.step("exterior_day", () -> onServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000")), null, 0);
        this.step("exterior_day_shot", () -> shot("exterior_day"), null, 40);
        // Walking backwards out of the parlour, looking into the room, then at the police box: no black or grey frames, and
        // the box is there as soon as the overworld is.
        this.step("parlour_back_setup", () -> onServer(server -> {
            TardisInteriorManager.get(server).setDoorState(server, this.parlour, DoorState.BOTH_OPEN);
            Vec3 inDoor = this.parlourDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.parlourFacing().scale(1.4)).subtract(0.0, 2.0, 0.0),
                    inDoor.add(this.parlourFacing().scale(20.0)));
        }), null, 0);
        this.step("parlour_back_ready", () -> {}, () -> TardisInteriorManager.isInterior(Minecraft.getInstance().level)
                && Minecraft.getInstance().levelRenderer.hasRenderedAllSections(), PARLOUR_SWING + 5);
        for (int i = 0; i < 24; i++) {
            String name = String.format("parlour_back_out_%02d", i);
            this.step(name, () -> {
                walk(-0.22);
                shot(name);
            }, null, 1);
        }
        this.step("parlour_back_check", () -> checkFacing("backwards walk out", this.parlour.exteriorFacing().getOpposite()), null, 0);
        // Phase 9: roundel walls. Wall A runs west from an outside corner, facing south; wall B runs north from it, facing
        // east; wall C stands at A's west end, facing east, making an inside corner. Each block is offset as placed.
        this.step("roundel_setup", () -> onServer(server -> {
            ServerLevel level = server.overworld();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode creative @a");
            BlockPos base = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, this.ground.offset(-20, 0, 12));
            this.roundelCorner = base;
            for (int dx = -10; dx <= 6; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.SMOOTH_STONE.defaultBlockState());
                    for (int dy = 0; dy <= 5; dy++) {
                        level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            for (int dy = 0; dy < 3; dy++) {
                for (int k = 0; k <= 5; k++) {
                    roundel(level, base.offset(-k, dy, 0), Direction.SOUTH);
                }
                for (int k = 1; k <= 4; k++) {
                    roundel(level, base.offset(0, dy, -k), Direction.EAST);
                }
                for (int k = 1; k <= 3; k++) {
                    roundel(level, base.offset(-6, dy, k), Direction.EAST);
                }
            }
            place(level, Vec3.atBottomCenterOf(base).add(4.5, 0.0, 5.5), Vec3.atCenterOf(base.above()).add(-1.5, 0.0, 0.0));
            server.getPlayerList().getPlayers().getFirst().getInventory().setItem(6, ArtronBlocks.ROUNDEL_ITEM.get().getDefaultInstance());
            server.getPlayerList().getPlayers().getFirst().getInventory().setItem(7, ArtronBlocks.HELL_BENT_DOOR_ITEM.get().getDefaultInstance());
        }), null, 0);
        this.step("roundel_shot", () -> {
            shot("roundel_walls_as_placed");
            onServer(server -> {
                ServerLevel level = server.overworld();
                BlockPos c = this.roundelCorner;
                boolean outside = staggers(level, c, Direction.EAST, c.north(), Direction.EAST);
                boolean inside = staggers(level, c.west(5), Direction.SOUTH, c.offset(-6, 0, 1), Direction.EAST);
                boolean straight = staggers(level, c, Direction.SOUTH, c.west(), Direction.SOUTH) && staggers(level, c.north(), Direction.EAST, c.north(2), Direction.EAST);
                ArtronIndustries.LOGGER.info("CLIENT SMOKE roundels as placed: straight walls stagger {}, outside corner {}, inside corner {}", straight, outside, inside);
                if (!straight) {
                    ArtronIndustries.LOGGER.error("CLIENT SMOKE FAILED: a straight roundel wall does not stagger");
                }
                place(level, this.outsideCornerEye(), this.outsideCornerEdge());
            });
        }, () -> renderedIn(false), 20);
        this.step("roundel_outside_before_shot", () -> {
            shot("roundel_outside_corner_as_placed");
            onServer(server -> place(server.overworld(), this.insideCornerEye(), this.insideCornerEdge()));
        }, null, 20);
        this.step("roundel_inside_before_shot", () -> shot("roundel_inside_corner_as_placed"), null, 20);
        // Corners the parity rule gets wrong are fixed by sneak-using every block of the wall that meets wall A.
        this.step("roundel_toggle", () -> onServer(server -> {
            ServerLevel level = server.overworld();
            BlockPos c = this.roundelCorner;
            boolean outside = staggers(level, c, Direction.EAST, c.north(), Direction.EAST);
            boolean inside = staggers(level, c.west(5), Direction.SOUTH, c.offset(-6, 0, 1), Direction.EAST);
            for (int dy = 0; dy < 3; dy++) {
                for (int k = 1; !outside && k <= 4; k++) {
                    sneakUse(level, c.offset(0, dy, -k), Direction.EAST);
                }
                for (int k = 1; !inside && k <= 3; k++) {
                    sneakUse(level, c.offset(-6, dy, k), Direction.EAST);
                }
            }
            boolean fixed = staggers(level, c, Direction.EAST, c.north(), Direction.EAST)
                    && staggers(level, c.west(5), Direction.SOUTH, c.offset(-6, 0, 1), Direction.EAST);
            if (fixed) {
                ArtronIndustries.LOGGER.info("CLIENT SMOKE roundel corners line up (toggled outside: {}, inside: {})", !outside, !inside);
            } else {
                ArtronIndustries.LOGGER.error("CLIENT SMOKE FAILED: sneak-using did not line the roundel corners up");
            }
        }), null, 0);
        this.step("roundel_inside_after_shot", () -> {
            shot("roundel_inside_corner");
            onServer(server -> place(server.overworld(), this.outsideCornerEye(), this.outsideCornerEdge()));
        }, null, 20);
        this.step("roundel_outside_after_shot", () -> {
            shot("roundel_outside_corner");
            onServer(server -> place(server.overworld(), Vec3.atBottomCenterOf(this.roundelCorner).add(4.5, 0.0, 5.5),
                    Vec3.atCenterOf(this.roundelCorner.above()).add(-1.5, 0.0, 0.0)));
        }, null, 20);
        this.step("roundel_walls_shot", () -> shot("roundel_walls"), null, 20);
        // Phase 10: the Hell Bent door, placed by hand as a starter room's interior door (west wall, facing east).
        this.step("hellbent_setup", () -> onServer(server -> {
            ServerLevel level = server.overworld();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
            BlockPos door = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, this.ground.offset(32, 0, 0));
            for (int dy = 0; dy < 3; dy++) {
                level.setBlockAndUpdate(door.above(dy), Blocks.AIR.defaultBlockState());
            }
            this.hellBent = ArtronBlocks.TARDIS.get().placeNewTardis(level, door, Direction.SOUTH, TardisExteriors.DEFAULT, TardisInteriors.STARTER);
            TardisInteriorManager.get(server).ensureInterior(server, this.hellBent);
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            BlockPos master = SmokeTest.hellBentDoorPos(this.hellBent);
            if (!SmokeTest.placeHellBentDoor(interior, master, Direction.EAST)) {
                throw new IllegalStateException("could not place the Hell Bent door");
            }
            // Set in a roundel wall, staggered round it, so the shut door can be compared with the blocks beside it. Seen
            // from the room (looking west) the door's columns show whole roundels, so the columns either side are offset.
            for (int dy = 0; dy < 4; dy++) {
                for (int dz : new int[]{-3, -2, 1, 2}) {
                    BlockPos pos = master.offset(0, dy, dz);
                    interior.setBlockAndUpdate(pos, ArtronBlocks.ROUNDEL.get().defaultBlockState().setValue(RoundelBlock.FACING, Direction.EAST)
                            .setValue(RoundelBlock.OFFSET, dz == -2 || dz == 1));
                }
            }
            for (int dz = -1; dz <= 0; dz++) {
                interior.setBlockAndUpdate(master.offset(0, 3, dz), ArtronBlocks.ROUNDEL.get().defaultBlockState().setValue(RoundelBlock.FACING, Direction.EAST));
            }
        }), null, 0);
        // Only once the new room's light has been worked out: chunks sent before that show it unlit.
        this.step("hellbent_enter", () -> onServer(server -> {
            Vec3 inDoor = this.hellBentDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.hellBentFacing().scale(4.5)).subtract(0.0, 1.5, 0.0), inDoor);
        }), null, 60);
        this.step("hellbent_shut_shot", () -> shot("hellbent_shut"), () -> renderedIn(true), 40);
        this.step("hellbent_right", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.hellBent, DoorState.RIGHT_OPEN)), null, 0);
        this.step("hellbent_opening_shot", () -> shot("hellbent_opening"), null, HELL_BENT_SWING / 2);
        this.step("hellbent_one_open_shot", () -> shot("hellbent_one_open"), () -> viewReady(this.hellBent, PortalSide.INTERIOR), HELL_BENT_SWING / 2 + 5);
        this.step("hellbent_both", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.hellBent, DoorState.BOTH_OPEN)), null, 0);
        this.step("hellbent_both_opening_shot", () -> shot("hellbent_both_opening"), null, HELL_BENT_SWING / 3);
        this.step("hellbent_open_shot", () -> shot("hellbent_open"), () -> viewReady(this.hellBent, PortalSide.INTERIOR), HELL_BENT_SWING + 5);
        for (int side = -1; side <= 1; side += 2) {
            int s = side;
            String name = side < 0 ? "left" : "right";
            this.step("hellbent_angle_" + name, () -> onServer(server -> {
                Vec3 inDoor = this.hellBentDoor();
                place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.hellBentFacing().scale(3.0)).add(this.hellBentRight().scale(3.0 * s))
                        .subtract(0.0, 1.5, 0.0), inDoor);
            }), null, 0);
            this.step("hellbent_angle_" + name + "_shot", () -> shot("hellbent_angle_" + name), null, 20);
        }
        // Close by, along the wall: the leaves' 4 px edges, the frame's scallops and the leaf swung out beside them.
        this.step("hellbent_edge", () -> onServer(server -> {
            Vec3 inDoor = this.hellBentDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.hellBentFacing().scale(1.6)).add(this.hellBentRight().scale(2.2))
                    .subtract(0.0, 1.9, 0.0), inDoor.subtract(this.hellBentRight().scale(0.9)).add(0.0, 0.3, 0.0));
        }), null, 0);
        this.step("hellbent_edge_shot", () -> shot("hellbent_edge"), null, 20);
        this.step("hellbent_edge_close", () -> onServer(server -> TardisInteriorManager.get(server).setDoorState(server, this.hellBent, DoorState.CLOSED)), null, 0);
        this.step("hellbent_edge_closing_shot", () -> shot("hellbent_edge_closing"), null, HELL_BENT_SWING / 2);
        this.step("hellbent_edge_shut_shot", () -> shot("hellbent_edge_shut"), null, HELL_BENT_SWING / 2 + 5);
        this.step("hellbent_angle_reopen", () -> onServer(server -> {
            TardisInteriorManager.get(server).setDoorState(server, this.hellBent, DoorState.BOTH_OPEN);
            Vec3 inDoor = this.hellBentDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.hellBentFacing().scale(3.0)).add(this.hellBentRight().scale(-2.5))
                    .subtract(0.0, 1.5, 0.0), inDoor);
        }), null, 0);
        this.step("hellbent_angle_opening_shot", () -> shot("hellbent_angle_opening"), null, HELL_BENT_SWING * 2 / 3);
        // Looking in from outside, through the police box: the door, open, at the far side.
        this.step("hellbent_outside", () -> onServer(server -> {
            Vec3 exDoor = this.hellBent.exteriorShape().center(this.hellBent.exteriorDoorPos(), this.hellBent.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.hellBent.exteriorFacing().getUnitVec3i());
            place(server.overworld(), exDoor.add(out.scale(2.5)).subtract(0.0, 1.3, 0.0), exDoor);
        }), null, HELL_BENT_SWING);
        this.step("hellbent_outside_shot", () -> shot("hellbent_outside_in"), () -> viewReady(this.hellBent, PortalSide.EXTERIOR) && renderedIn(false), 40);
        this.step("hellbent_outside_angle", () -> onServer(server -> {
            Vec3 exDoor = this.hellBent.exteriorShape().center(this.hellBent.exteriorDoorPos(), this.hellBent.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.hellBent.exteriorFacing().getUnitVec3i());
            Vec3 side = Vec3.atLowerCornerOf(PortalShape.right(this.hellBent.exteriorFacing()).getUnitVec3i());
            place(server.overworld(), exDoor.add(out.scale(1.5)).add(side.scale(-1.2)).subtract(0.0, 1.3, 0.0), exDoor.subtract(out.scale(4.0)));
        }), null, 0);
        this.step("hellbent_outside_angle_shot", () -> shot("hellbent_outside_angle"), null, 30);
        // Walk out through the Hell Bent door, then back in through the police box.
        this.step("hellbent_walk_out_setup", () -> onServer(server -> {
            Vec3 anchor = this.hellBent.interiorShape().anchor(this.hellBent.interiorDoorPos(), this.hellBent.interiorDoorFacing());
            place(TardisInteriorManager.interiorLevel(server), anchor.add(this.hellBentFacing().scale(3.0)),
                    anchor.add(0.0, 1.62, 0.0).subtract(this.hellBentFacing().scale(2.0)));
        }), null, 0);
        this.step("hellbent_walk_out_go", () -> Minecraft.getInstance().options.keyUp.setDown(true), () -> renderedIn(true), 20);
        for (int i = 0; i < 16; i++) {
            String name = String.format("hellbent_walk_out_%02d", i);
            this.step(name, () -> {
                Minecraft.getInstance().options.keyUp.setDown(true);
                walk(0.3);
                if (name.endsWith("08") || name.endsWith("10") || name.endsWith("12")) {
                    shot(name);
                }
            }, null, 2);
        }
        this.step("hellbent_walk_out_stop", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(false);
            if (TardisInteriorManager.isInterior(mc.level)) {
                ArtronIndustries.LOGGER.error("CLIENT SMOKE FAILED: did not walk out through the Hell Bent door ({})", mc.player.position());
            } else {
                ArtronIndustries.LOGGER.info("CLIENT SMOKE walked out through the Hell Bent door: now at {}", mc.player.position());
            }
            checkFacing("Hell Bent walk out", this.hellBent.exteriorFacing());
        }, null, 10);
        this.step("hellbent_walk_in_setup", () -> onServer(server -> {
            Vec3 anchor = this.hellBent.exteriorShape().anchor(this.hellBent.exteriorDoorPos(), this.hellBent.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.hellBent.exteriorFacing().getUnitVec3i());
            place(server.overworld(), anchor.add(out.scale(3.0)), anchor.add(0.0, 1.62, 0.0).subtract(out.scale(2.0)));
        }), null, 10);
        this.step("hellbent_walk_in_go", () -> Minecraft.getInstance().options.keyUp.setDown(true), () -> renderedIn(false), 20);
        for (int i = 0; i < 16; i++) {
            String name = String.format("hellbent_walk_in_%02d", i);
            this.step(name, () -> {
                Minecraft.getInstance().options.keyUp.setDown(true);
                walk(0.3);
                if (name.endsWith("10") || name.endsWith("12")) {
                    shot(name);
                }
            }, null, 2);
        }
        this.step("hellbent_walk_in_stop", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(false);
            if (!TardisInteriorManager.isInterior(mc.level)) {
                ArtronIndustries.LOGGER.error("CLIENT SMOKE FAILED: did not walk back in through the Hell Bent door ({})", mc.player.position());
            } else {
                ArtronIndustries.LOGGER.info("CLIENT SMOKE walked back in through the Hell Bent door: now at {}", mc.player.position());
            }
            checkFacing("Hell Bent walk in", this.hellBent.interiorDoorFacing());
        }, null, 10);
        this.step("hellbent_close", () -> onServer(server -> {
            TardisInteriorManager.get(server).setDoorState(server, this.hellBent, DoorState.CLOSED);
            Vec3 inDoor = this.hellBentDoor();
            place(TardisInteriorManager.interiorLevel(server), inDoor.add(this.hellBentFacing().scale(4.5)).subtract(0.0, 1.5, 0.0), inDoor);
        }), null, 0);
        this.step("hellbent_closing_shot", () -> shot("hellbent_closing"), null, HELL_BENT_SWING / 2);
        this.step("hellbent_closed_shot", () -> {
            shot("hellbent_closed");
            onServer(server -> {
                ServerLevel interior = TardisInteriorManager.interiorLevel(server);
                BlockPos master = this.hellBent.interiorDoorPos();
                boolean collides = !interior.getBlockState(master).getCollisionShape(interior, master).isEmpty();
                ArtronIndustries.LOGGER.info("CLIENT SMOKE Hell Bent door shut again, collides: {}", collides);
            });
        }, null, HELL_BENT_SWING / 2 + 5);
        this.step("quit", () -> {
            BotiRenderer.setDebugFloatingPos(null);
            ArtronIndustries.LOGGER.info("CLIENT SMOKE done: {}", String.format("rebuild %.2f ms, draw %.3f ms (%d doorways)",
                    BotiMeshCache.lastRebuildMs(), BotiRenderer.lastDrawMs(), BotiRenderer.lastDrawCount()));
            Minecraft.getInstance().stop();
        }, null, 20);
    }

    private int menuTicks;
    private boolean backupSkipped;

    private void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
            // The test world was generated by a dedicated server, which makes vanilla ask for a backup first.
            if (!this.backupSkipped && mc.gui.screen() instanceof net.minecraft.client.gui.screens.BackupConfirmScreen screen) {
                for (var child : screen.children()) {
                    if (child instanceof net.minecraft.client.gui.components.Button button
                            && button.getMessage().equals(net.minecraft.network.chat.Component.translatable("selectWorld.backupJoinSkipButton"))) {
                        this.backupSkipped = true;
                        button.onPress(new net.minecraft.client.input.KeyEvent(257, 0, 0));
                        return;
                    }
                }
            }
            if (++this.menuTicks % 100 == 0) {
                ArtronIndustries.LOGGER.info("CLIENT SMOKE waiting for the world, screen = {}", mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName());
            }
            return;
        }
        if (this.index >= this.steps.size()) {
            return;
        }
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.ChatScreen) {
            // The dev window takes focus when it starts; typing elsewhere can open chat in it, which stops movement.
            mc.gui.setScreen(null);
        }
        if (mc.player.isDeadOrDying()) {
            // A run that stopped part-way can leave the player falling out of the bottom of the interior dimension.
            mc.player.respawn();
            this.wait = 40;
            return;
        }
        if (!this.started) {
            this.started = true;
            mc.options.pauseOnLostFocus = false;
            ArtronIndustries.LOGGER.info("CLIENT SMOKE starting");
            // Start from the overworld, whatever dimension (or void) the last run left the player in.
            onServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                BlockPos spawn = server.overworld().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.ZERO);
                player.teleportTo(server.overworld(), spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, java.util.Set.of(), 0.0F, 0.0F, false);
                player.setHealth(player.getMaxHealth());
            });
            this.wait = 40;
            return;
        }
        if (this.wait > 0) {
            this.wait--;
            return;
        }
        Step step = this.steps.get(this.index);
        if (step.until() != null && !step.until().getAsBoolean()) {
            if (--this.timeout <= 0) {
                ArtronIndustries.LOGGER.error("CLIENT SMOKE timed out before step {} (screen {})", step.name(), mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getName());
                this.index = this.steps.size();
                mc.stop();
            }
            return;
        }
        this.timeout = 600;
        ArtronIndustries.LOGGER.info("CLIENT SMOKE step {} (player in {} at {})", step.name(), mc.level.dimension().identifier(),
                mc.player.position());
        try {
            step.action().run();
        } catch (RuntimeException e) {
            ArtronIndustries.LOGGER.error("CLIENT SMOKE failed at step {}", step.name(), e);
            this.index = this.steps.size();
            mc.stop();
            return;
        }
        this.index++;
        this.wait = this.index < this.steps.size() ? this.steps.get(this.index).delay() : 0;
    }
}
