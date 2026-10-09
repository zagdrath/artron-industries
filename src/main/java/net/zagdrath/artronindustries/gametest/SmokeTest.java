/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.Config;
import net.zagdrath.artronindustries.block.PortalDoorBlock;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.boti.server.PortalWatcher;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * Dedicated-server smoke test, enabled with {@code -Dartronindustries.smokeTest=true}
 * ({@code ./gradlew runServer -Partronindustries.smokeTest=true}). Runs the server-side TARDIS pipeline end to end on a
 * real server (including the datapack interior dimension, which the GameTest server never creates), logs
 * {@code ARTRON SMOKE TEST PASSED/FAILED} and stops the server.
 */
public final class SmokeTest {
    public static final String PROPERTY = "artronindustries.smokeTest";

    private final List<Consumer<MinecraftServer>> steps = new ArrayList<>();
    private int step;
    private int wait;
    private TardisRecord record;
    private BlockPos probe;
    private @Nullable BooleanSupplier until;
    private int untilTimeout;

    /** Leave the last TARDIS open (and the server stopped with it open) so the next run can check a restart. */
    private static boolean keepOpen;
    private @Nullable TardisRecord leftover;

    private SmokeTest() {}

    public static void registerIfEnabled() {
        String mode = System.getProperty(PROPERTY, "false");
        if (mode.equals("true") || mode.equals("keepopen")) {
            keepOpen = mode.equals("keepopen");
            SmokeTest test = new SmokeTest();
            test.define();
            NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> ArtronIndustries.LOGGER.info("ARTRON SMOKE TEST starting"));
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> test.tick(e.getServer()));
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** Steps run one per tick (or after {@link #wait} ticks), so block entity ticking and syncing happen in between. */
    private void define() {
        this.steps.add(server -> {
            TardisInteriorManager manager = TardisInteriorManager.get(server);
            this.leftover = manager.all().stream().filter(TardisRecord::doorOpen).findFirst().orElse(null);
            if (this.leftover != null) {
                ArtronIndustries.LOGGER.info("ARTRON SMOKE TEST restart check on TARDIS #{}", this.leftover.id());
                server.getLevel(this.leftover.exteriorLevel()).getChunkSource()
                        .addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(this.leftover.exteriorDoorPos()), 1);
                check(PortalWatcher.pinView(server, this.leftover, PortalSide.EXTERIOR), "no view for the TARDIS left open before the restart");
                this.waitFor(() -> PortalWatcher.encodedView(new PortalViewKey(this.leftover.uuid(), PortalSide.EXTERIOR)) != null, 600);
            }
        });
        this.steps.add(server -> {
            if (this.leftover != null) {
                TardisInteriorManager manager = TardisInteriorManager.get(server);
                PortalDoorBlockEntity door = manager.loadedDoor(server, this.leftover, PortalSide.EXTERIOR);
                check(door != null && door.isOpen() && this.leftover.uuid().equals(door.getTardisId()), "door not open/linked after the restart");
                manager.delete(server, this.leftover);
                check(PortalWatcher.viewCount() == 0, "deleting the restarted TARDIS did not drop its views");
            }
        });
        this.steps.add(server -> {
            ServerLevel overworld = server.overworld();
            BlockPos ground = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(8, 0, 8));
            overworld.setBlockAndUpdate(ground, Blocks.AIR.defaultBlockState());
            overworld.setBlockAndUpdate(ground.above(), Blocks.AIR.defaultBlockState());
            this.record = ArtronBlocks.TARDIS.get().placeNewTardis(overworld, ground, Direction.SOUTH);
            check(this.record != null, "could not place the exterior door at " + ground);
            overworld.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(ground), 1);
        });
        this.steps.add(server -> {
            TardisInteriorManager manager = TardisInteriorManager.get(server);
            check(TardisInteriorManager.interiorLevel(server) != null, "interior dimension not loaded");
            manager.setDoorOpen(server, this.record, true);
            check(this.record.doorOpen() && this.record.interiorGenerated(), "opening did not generate the interior");
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            check(interior.getBlockState(this.record.interiorDoorPos()).is(ArtronBlocks.INTERIOR_DOOR.get()), "no interior door block");
            check(interior.getBlockState(this.record.interiorDoorPos().above()).getValue(PortalDoorBlock.HALF).name().equals("UPPER"), "no upper half");
            for (PortalSide side : PortalSide.values()) {
                PortalDoorBlockEntity door = manager.loadedDoor(server, this.record, side);
                check(door != null && this.record.uuid().equals(door.getTardisId()) && door.isOpen(), side + " door not linked/open");
            }
            // Nothing holds the interior loaded on an empty server; keep the door chunk alive while the animation runs.
            interior.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(this.record.interiorDoorPos()), 1);
            this.wait = PortalDoorBlockEntity.OPEN_TICKS + 2;
        });
        // BOTI streaming: capture the interior as seen from outside, change a block, expect a delta.
        this.steps.add(server -> {
            check(PortalWatcher.pinView(server, this.record, PortalSide.EXTERIOR), "could not create the exterior view");
            check(PortalWatcher.pinView(server, this.record, PortalSide.INTERIOR), "could not create the interior view");
            // First captures happen once the far chunks have loaded asynchronously.
            this.waitFor(() -> PortalWatcher.encodedView(this.key(PortalSide.EXTERIOR)) != null
                    && PortalWatcher.encodedView(this.key(PortalSide.INTERIOR)) != null, 600);
        });
        this.steps.add(server -> {
            PortalSnapshot snapshot = PortalSnapshot.fromBytes(PortalWatcher.encodedView(this.key(PortalSide.EXTERIOR)));
            BlockPos console = this.record.interiorOrigin().above();
            check(snapshot.box().containsWorld(console.getX(), console.getY(), console.getZ()), "interior box misses the console " + snapshot.box());
            check(snapshot.states()[snapshot.box().indexOfWorld(console.getX(), console.getY(), console.getZ())] == Block.getId(Blocks.BEACON.defaultBlockState()),
                    "console block not in the snapshot");
            check(!snapshot.blockEntities().isEmpty(), "chest/beacon block entity data missing");
            this.probe = console.offset(2, 0, -2);
            TardisInteriorManager.interiorLevel(server).setBlockAndUpdate(this.probe, Blocks.GOLD_BLOCK.defaultBlockState());
        });
        this.steps.add(server -> {
            PortalSnapshot snapshot = PortalSnapshot.fromBytes(PortalWatcher.encodedView(this.key(PortalSide.EXTERIOR)));
            int index = snapshot.box().indexOfWorld(this.probe.getX(), this.probe.getY(), this.probe.getZ());
            check(snapshot.states()[index] == Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()), "block change was not picked up as a delta");
            PortalSnapshot outside = PortalSnapshot.fromBytes(PortalWatcher.encodedView(this.key(PortalSide.INTERIOR)));
            check(outside.environment().hasSky(), "exterior environment should have a sky");
            this.wait = 2 * Config.LIGHT_DELTA_INTERVAL.getAsInt() + 1; // let a few full sweeps run (timings at debug level)
        });
        // Walk-through for non-players: an item thrown at the open exterior doorway arrives inside.
        this.steps.add(server -> {
            ServerLevel overworld = server.overworld();
            Vec3 anchor = this.record.exteriorShape().anchor(this.record.exteriorDoorPos(), this.record.exteriorFacing());
            Vec3 out = Vec3.atLowerCornerOf(this.record.exteriorFacing().getUnitVec3i());
            Vec3 from = anchor.add(out.scale(2.0)).add(0.0, 1.0, 0.0);
            ItemEntity item = new ItemEntity(overworld, from.x, from.y, from.z, new ItemStack(Items.DIAMOND));
            item.setDeltaMovement(out.scale(-0.45).add(0.0, 0.15, 0.0));
            overworld.addFreshEntity(item);
            this.waitFor(() -> !TardisInteriorManager.interiorLevel(server).getEntities((Entity) null,
                    new AABB(this.record.interiorDoorPos()).inflate(6), e -> e instanceof ItemEntity).isEmpty(), 100);
        });
        this.steps.add(server -> {
            PortalDoorBlockEntity door = TardisInteriorManager.get(server).loadedDoor(server, this.record, PortalSide.INTERIOR);
            check(door.getDoorOpenAmount(1.0F) == 1.0F, "open animation did not finish: " + door.getDoorOpenAmount(1.0F));
            TardisInteriorManager.get(server).setDoorOpen(server, this.record, false);
            check(!door.isOpen(), "interior door did not close");
            // Views stay while the doors are shut, so the far side is ready the moment they open again.
            check(PortalWatcher.viewCount() > 0, "closing the door dropped its views");
        });
        this.steps.add(server -> {
            TardisInteriorManager.get(server).setDoorOpen(server, this.record, true);
            check(PortalWatcher.pinView(server, this.record, PortalSide.EXTERIOR), "could not pin the view again");
            this.waitFor(() -> PortalWatcher.encodedView(this.key(PortalSide.EXTERIOR)) != null, 600);
        });
        this.steps.add(server -> {
            if (keepOpen) {
                ArtronIndustries.LOGGER.info("ARTRON SMOKE TEST leaving TARDIS #{} open for the restart check", this.record.id());
                return;
            }
            TardisInteriorManager manager = TardisInteriorManager.get(server);
            // Deleted while open and streamed: views must go and both doors must unlink.
            manager.delete(server, this.record);
            check(PortalWatcher.viewCount() == 0, "deleting an open TARDIS did not drop its views");
            check(manager.get(this.record.uuid()) == null, "record survived deletion");
            PortalDoorBlockEntity ex = (PortalDoorBlockEntity) server.overworld().getBlockEntity(this.record.exteriorDoorPos());
            check(ex != null && ex.getTardisId() == null, "exterior door still linked after deletion");
        });
    }

    private PortalViewKey key(PortalSide nearSide) {
        return new PortalViewKey(this.record.uuid(), nearSide);
    }

    /** Holds the next step until {@code condition} is true, failing after {@code timeoutTicks}. */
    private void waitFor(BooleanSupplier condition, int timeoutTicks) {
        this.until = condition;
        this.untilTimeout = timeoutTicks;
    }

    private void tick(MinecraftServer server) {
        if (this.step >= this.steps.size()) {
            return;
        }
        if (this.wait > 0) {
            this.wait--;
            return;
        }
        try {
            if (this.until != null) {
                if (!this.until.getAsBoolean()) {
                    check(--this.untilTimeout > 0, "timed out waiting before step " + (this.step + 1));
                    return;
                }
                this.until = null;
            }
            this.steps.get(this.step++).accept(server);
            if (this.step == this.steps.size()) {
                ArtronIndustries.LOGGER.info("ARTRON SMOKE TEST PASSED ({} steps)", this.steps.size());
                server.halt(false);
            }
        } catch (RuntimeException e) {
            ArtronIndustries.LOGGER.error("ARTRON SMOKE TEST FAILED at step {}", this.step, e);
            this.step = this.steps.size();
            server.halt(false);
        }
    }
}
