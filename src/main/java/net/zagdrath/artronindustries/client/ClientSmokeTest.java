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
import net.zagdrath.artronindustries.client.boti.BotiClientCache;
import net.zagdrath.artronindustries.client.boti.BotiMeshCache;
import net.zagdrath.artronindustries.client.boti.BotiRenderer;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

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
    private int timeout = 600;
    private boolean started;
    private @Nullable TardisRecord record;
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

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "boti_" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1,
                msg -> ArtronIndustries.LOGGER.info("CLIENT SMOKE screenshot {}: {}", name, msg.getString()));
    }

    private boolean viewReady(PortalSide nearSide) {
        if (this.record == null) {
            return false;
        }
        var view = BotiClientCache.get(new net.zagdrath.artronindustries.boti.PortalViewKey(this.record.uuid(), nearSide));
        return view != null && !view.isMeshDirty() && BotiMeshCache.meshCount() > 0 && BotiRenderer.lastDrawCount() > 0;
    }

    /** Eye position half a block in front of the exterior doorway, at a height inside the opening. */
    private Vec3 nearEye() {
        Vec3 anchor = this.record.exteriorShape().anchor(this.record.exteriorDoorPos(), this.record.exteriorFacing());
        return anchor.add(Vec3.atLowerCornerOf(this.record.exteriorFacing().getUnitVec3i()).scale(0.5)).add(0.0, 1.5, 0.0);
    }

    private Vec3 doorCenter(PortalSide side) {
        return this.record.shape(side).center(this.record.doorPos(side), this.record.doorFacing(side));
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
                    boolean opening = dx == 0 && dy < 2;
                    level.setBlockAndUpdate(door.offset(dx, dy, 0), opening ? Blocks.AIR.defaultBlockState() : Blocks.LAPIS_BLOCK.defaultBlockState());
                }
            }
            this.record = ArtronBlocks.TEST_EXTERIOR_DOOR.get().placeNewTardis(level, door, Direction.SOUTH);
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
        }, () -> !TardisInteriorManager.isInterior(Minecraft.getInstance().level), 30);
        for (int i = 0; i < 16; i++) {
            String name = String.format("walk_in_%02d", i);
            this.step(name, () -> {
                // Screens release keys; a real player keeps holding them.
                Minecraft.getInstance().options.keyUp.setDown(true);
                Minecraft.getInstance().options.keySprint.setDown(true);
                shot(name);
            }, null, 2);
        }
        this.step("walk_in_stop", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(false);
            mc.options.keySprint.setDown(false);
            ArtronIndustries.LOGGER.info("CLIENT SMOKE walked in: now in {} at {}", mc.level.dimension().identifier(), mc.player.position());
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
                shot(name);
            }, null, 2);
        }
        this.step("walk_out_stop", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(false);
            ArtronIndustries.LOGGER.info("CLIENT SMOKE walked out: now in {} at {}", mc.level.dimension().identifier(), mc.player.position());
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
        if (!this.started) {
            this.started = true;
            mc.options.pauseOnLostFocus = false;
            ArtronIndustries.LOGGER.info("CLIENT SMOKE starting");
        }
        if (this.wait > 0) {
            this.wait--;
            return;
        }
        Step step = this.steps.get(this.index);
        if (step.until() != null && !step.until().getAsBoolean()) {
            if (--this.timeout <= 0) {
                ArtronIndustries.LOGGER.error("CLIENT SMOKE timed out before step {}", step.name());
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
