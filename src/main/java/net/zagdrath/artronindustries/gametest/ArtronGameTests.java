/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.gametest;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * Server-side GameTests. Instances live in {@code data/artronindustries/test_instance}; run them with
 * {@code ./gradlew runGameTestServer} or {@code /test runall artronindustries}.
 */
public final class ArtronGameTests {
    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, ArtronIndustries.MODID);

    static {
        TEST_FUNCTIONS.register("tardis_lifecycle", () -> ArtronGameTests::tardisLifecycle);
    }

    private ArtronGameTests() {}

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            throw helper.assertionException("test.error.artronindustries", message);
        }
    }

    /** Placing an exterior allocates a cell and links the door; the transform glues the anchors; deleting unlinks the door. */
    private static void tardisLifecycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        TardisInteriorManager manager = TardisInteriorManager.get(server);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());

        TardisRecord record = ArtronBlocks.TARDIS.get().placeNewTardis(level, pos, Direction.EAST);
        check(helper, record != null, "door could not be placed");
        check(helper, manager.get(record.uuid()) == record, "record not registered");
        check(helper, !record.interiorGenerated(), "interior generated eagerly");
        check(helper, level.getBlockEntity(pos) instanceof PortalDoorBlockEntity d && record.uuid().equals(d.getTardisId()), "exterior not linked");

        // The GameTest server never creates datapack dimensions, so the door must refuse to open here. Interior
        // generation and door sync are covered by the dedicated-server smoke test (see SmokeTest).
        manager.setDoorOpen(server, record, true);
        check(helper, TardisInteriorManager.interiorLevel(server) != null || !record.doorOpen(), "door opened without an interior");
        PortalDoorBlockEntity exDoor = manager.loadedDoor(server, record, PortalSide.EXTERIOR);
        check(helper, exDoor != null, "exterior door not found through the manager");

        DoorPairTransform t = record.transform();
        Vec3 exAnchor = record.exteriorShape().anchor(pos, Direction.EAST);
        Vec3 inAnchor = record.interiorShape().anchor(record.interiorDoorPos(), record.interiorDoorFacing());
        check(helper, t != null && t.apply(exAnchor).distanceTo(inAnchor) < 1.0E-6, "transform does not glue the anchors");

        manager.delete(server, record);
        check(helper, manager.get(record.uuid()) == null, "record survived deletion");
        check(helper, exDoor.getTardisId() == null, "door still linked after deletion");
        helper.succeed();
    }
}
