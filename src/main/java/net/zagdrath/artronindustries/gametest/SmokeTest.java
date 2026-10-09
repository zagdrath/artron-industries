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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.DirectionalPlaceContext;
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
import net.zagdrath.artronindustries.block.HellBentDoorBlock;
import net.zagdrath.artronindustries.block.PortalDoorBlock;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.boti.server.PortalWatcher;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlocks;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;
import net.zagdrath.artronindustries.block.InteriorDoorwayBlock;
import net.zagdrath.artronindustries.block.entity.HellBentDoorBlockEntity;
import net.zagdrath.artronindustries.block.entity.InteriorDoorwayBlockEntity;
import net.zagdrath.artronindustries.portal.OpenSpan;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.tardis.DoorSounds;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.InteriorGenerator;
import net.zagdrath.artronindustries.tardis.exterior.TardisExteriors;
import net.zagdrath.artronindustries.tardis.interior.TardisInteriors;

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
    private @Nullable TardisRecord parlour;
    /** A starter-room TARDIS whose interior door is a Hell Bent door placed in its west wall. */
    private @Nullable TardisRecord hellBent;

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

    /**
     * Places a Hell Bent door as a player standing in front of it would, its bottom left cell at {@code master} and facing
     * {@code facing}, clearing whatever is where it goes first. Returns whether it was placed.
     */
    public static boolean placeHellBentDoor(ServerLevel level, BlockPos master, Direction facing) {
        for (int index = 0; index < HellBentDoorBlock.DOORWAY.cellCount(); index++) {
            level.setBlockAndUpdate(HellBentDoorBlock.DOORWAY.cell(master, facing, index), Blocks.AIR.defaultBlockState());
        }
        ItemStack stack = ArtronBlocks.HELL_BENT_DOOR_ITEM.get().getDefaultInstance();
        return ArtronBlocks.HELL_BENT_DOOR_ITEM.get().place(new DirectionalPlaceContext(level, master, facing.getOpposite(), stack, Direction.UP))
                .consumesAction();
    }

    /** Where the Hell Bent door goes in a starter room: in the west wall, facing east into the room, clear of the glass. */
    public static BlockPos hellBentDoorPos(TardisRecord record) {
        return record.interiorOrigin().offset(-InteriorGenerator.HALF - 1, 1, 3);
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
            // The streaming checks below look for the starter room's console.
            this.record = ArtronBlocks.TARDIS.get().placeNewTardis(overworld, ground, Direction.SOUTH, TardisExteriors.DEFAULT, TardisInteriors.STARTER);
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
            this.wait = DoorSounds.DEFAULT_SWING_TICKS + 2;
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
        // The Victorian Parlour: placed from its schematic, its doorway made of the build's own blocks.
        this.steps.add(server -> {
            ServerLevel overworld = server.overworld();
            BlockPos ground = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(24, 0, 8));
            for (int dy = 0; dy < 3; dy++) {
                overworld.setBlockAndUpdate(ground.above(dy), Blocks.AIR.defaultBlockState());
            }
            this.parlour = ArtronBlocks.TARDIS.get().placeNewTardis(overworld, ground, Direction.SOUTH);
            check(this.parlour != null && this.parlour.interior() == TardisInteriors.VICTORIAN_PARLOUR, "a new TARDIS did not get the parlour");
            check(this.parlour.interiorGenerated(), "parlour not generated");
            overworld.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(ground), 1);
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            interior.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(this.parlour.interiorDoorPos()), 2);
            BlockPos o = this.parlour.interiorOrigin();
            check(interior.getBlockState(o.offset(13, 1, 37)).is(Blocks.SPRUCE_PLANKS), "parlour floor missing");
            check(interior.getBlockState(o).isAir(), "corner marker was placed");
            check(interior.getBlockEntity(this.parlour.interiorDoorPos()) instanceof InteriorDoorwayBlockEntity d
                    && this.parlour.uuid().equals(d.getTardisId()) && d.leaves().size() == 32, "doorway not built or not linked");
            InteriorDoorwayBlockEntity doorway = (InteriorDoorwayBlockEntity) interior.getBlockEntity(this.parlour.interiorDoorPos());
            check(doorway.leaves().get(doorway.doorway().index(0, 0, 1)).is(Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY)), "leaf blocks not kept");
            BlockPos leaf = o.offset(12, 2, 39);
            check(interior.getBlockState(leaf).is(ArtronBlocks.INTERIOR_DOORWAY.get())
                    && !interior.getBlockState(leaf).getCollisionShape(interior, leaf).isEmpty(), "shut leaf does not collide");
            TardisInteriorManager manager = TardisInteriorManager.get(server);
            manager.setDoorState(server, this.parlour, DoorState.RIGHT_OPEN);
            // The exterior's right leaf comes out of this door's left one: from the room (looking south), the east leaf.
            check(interior.getBlockState(o.offset(15, 2, 39)).getValue(InteriorDoorwayBlock.OPEN)
                    && !interior.getBlockState(leaf).getValue(InteriorDoorwayBlock.OPEN), "the wrong leaf opened first");
            manager.setDoorState(server, this.parlour, DoorState.BOTH_OPEN);
            check(interior.getBlockState(leaf).getCollisionShape(interior, leaf).isEmpty(), "open leaf still collides");
            check(interior.getBlockEntity(this.parlour.interiorDoorPos()) instanceof InteriorDoorwayBlockEntity d
                    && d.swingTicks() == this.parlour.interior().doorSounds().swingTicks(), "the parlour door does not swing with its sounds");
            this.wait = this.parlour.interior().doorSounds().swingTicks() + 2;
        });
        // Thrown out near the edge of the 4-wide doorway, an item comes out in front of the police box's doors, not beside them.
        this.steps.add(server -> {
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            Direction facing = this.parlour.interiorDoorFacing();
            Vec3 anchor = this.parlour.interiorShape().anchor(this.parlour.interiorDoorPos(), facing);
            Vec3 in = Vec3.atLowerCornerOf(facing.getUnitVec3i());
            Vec3 from = anchor.add(in.scale(2.0)).add(Vec3.atLowerCornerOf(PortalShape.right(facing).getUnitVec3i()).scale(1.5)).add(0.0, 1.0, 0.0);
            ItemEntity item = new ItemEntity(interior, from.x, from.y, from.z, new ItemStack(Items.EMERALD));
            item.setDeltaMovement(in.scale(-0.45).add(0.0, 0.15, 0.0));
            interior.addFreshEntity(item);
            this.waitFor(() -> this.parlourEmerald(server) != null, 100);
        });
        this.steps.add(server -> {
            ItemEntity item = this.parlourEmerald(server);
            double lateral = this.parlour.exteriorShape().lateral(this.parlour.exteriorDoorPos(), this.parlour.exteriorFacing(), item.position());
            check(Math.abs(lateral) <= this.parlour.exteriorShape().width() / 2.0, "came out beside the exterior doors, " + lateral + " off centre");
            item.discard();
            TardisInteriorManager.get(server).delete(server, this.parlour);
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            check(!interior.getBlockState(this.parlour.interiorOrigin().offset(12, 2, 39)).getValue(InteriorDoorwayBlock.OPEN),
                    "deleting the TARDIS left its doorway open");
        });
        // The Hell Bent door, placed by hand in a starter room's west wall: it becomes the interior door, all six cells of it.
        this.steps.add(server -> {
            ServerLevel overworld = server.overworld();
            BlockPos ground = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(40, 0, 8));
            for (int dy = 0; dy < 3; dy++) {
                overworld.setBlockAndUpdate(ground.above(dy), Blocks.AIR.defaultBlockState());
            }
            this.hellBent = ArtronBlocks.TARDIS.get().placeNewTardis(overworld, ground, Direction.SOUTH, TardisExteriors.DEFAULT, TardisInteriors.STARTER);
            check(this.hellBent != null, "could not place the Hell Bent door's TARDIS");
            TardisInteriorManager manager = TardisInteriorManager.get(server);
            check(manager.ensureInterior(server, this.hellBent), "Hell Bent door's TARDIS interior not generated");
            // Things thrown through the doorways have to tick on both sides.
            overworld.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(ground), 3);
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            BlockPos oldDoor = this.hellBent.interiorDoorPos();
            BlockPos master = hellBentDoorPos(this.hellBent);
            interior.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(master), 3);
            check(placeHellBentDoor(interior, master, Direction.EAST), "could not place the Hell Bent door");
            check(master.equals(this.hellBent.interiorDoorPos()) && this.hellBent.interiorDoorFacing() == Direction.EAST
                    && this.hellBent.interiorShape().equals(HellBentDoorBlock.DOORWAY.shape()), "the Hell Bent door did not become the interior door");
            check(interior.getBlockEntity(oldDoor) instanceof PortalDoorBlockEntity old && old.getTardisId() == null, "the old interior door is still linked");
            for (int index = 0; index < HellBentDoorBlock.DOORWAY.cellCount(); index++) {
                BlockPos pos = HellBentDoorBlock.DOORWAY.cell(master, Direction.EAST, index);
                var cell = interior.getBlockState(pos);
                check(cell.is(ArtronBlocks.HELL_BENT_DOOR.get()) && cell.getValue(HellBentDoorBlock.COLUMN) == HellBentDoorBlock.DOORWAY.i(index)
                        && cell.getValue(HellBentDoorBlock.ROW) == HellBentDoorBlock.DOORWAY.j(index), "Hell Bent door cell " + index + " missing at " + pos);
                check(!cell.getCollisionShape(interior, pos).isEmpty(), "shut Hell Bent cell " + index + " does not collide");
            }
            check(interior.getBlockEntity(master) instanceof HellBentDoorBlockEntity d && this.hellBent.uuid().equals(d.getTardisId())
                    && d.swingTicks() == HellBentDoorBlock.SOUNDS.swingTicks(), "Hell Bent door not linked or not swinging with its own sounds");
            manager.setDoorState(server, this.hellBent, DoorState.RIGHT_OPEN);
            // Its left leaf (from the room, looking west: the south column) opens with the exterior's right one.
            BlockPos rightColumn = HellBentDoorBlock.DOORWAY.cell(master, Direction.EAST, HellBentDoorBlock.DOORWAY.index(1, 0, 0));
            check(interior.getBlockState(master.above(2)).getValue(HellBentDoorBlock.OPEN)
                    && !interior.getBlockState(rightColumn).getValue(HellBentDoorBlock.OPEN), "the wrong Hell Bent leaf opened first");
            manager.setDoorState(server, this.hellBent, DoorState.BOTH_OPEN);
            for (int index = 0; index < HellBentDoorBlock.DOORWAY.cellCount(); index++) {
                BlockPos pos = HellBentDoorBlock.DOORWAY.cell(master, Direction.EAST, index);
                check(interior.getBlockState(pos).getCollisionShape(interior, pos).isEmpty(), "open Hell Bent cell " + index + " still collides");
            }
            this.wait = HellBentDoorBlock.SOUNDS.swingTicks() + 2;
        });
        // Thrown out near the right edge of its 2-wide doorway, an item comes out in front of the police box's doors.
        this.steps.add(server -> {
            PortalDoorBlockEntity door = TardisInteriorManager.get(server).loadedDoor(server, this.hellBent, PortalSide.INTERIOR);
            check(door != null && door.getPassableSpan().equals(OpenSpan.FULL), "the open Hell Bent door is not passable");
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            Direction facing = this.hellBent.interiorDoorFacing();
            Vec3 anchor = this.hellBent.interiorShape().anchor(this.hellBent.interiorDoorPos(), facing);
            Vec3 in = Vec3.atLowerCornerOf(facing.getUnitVec3i());
            Vec3 from = anchor.add(in.scale(2.0)).add(Vec3.atLowerCornerOf(PortalShape.right(facing).getUnitVec3i()).scale(0.7)).add(0.0, 1.0, 0.0);
            ItemEntity item = new ItemEntity(interior, from.x, from.y, from.z, new ItemStack(Items.EMERALD));
            item.setDeltaMovement(in.scale(-0.45).add(0.0, 0.15, 0.0));
            interior.addFreshEntity(item);
            this.waitFor(() -> itemNear(server.overworld(), this.hellBent.exteriorDoorPos(), Items.EMERALD) != null, 100);
        });
        // And one thrown in at the police box comes out of the Hell Bent doorway, into the room.
        this.steps.add(server -> {
            ItemEntity out = itemNear(server.overworld(), this.hellBent.exteriorDoorPos(), Items.EMERALD);
            double lateral = this.hellBent.exteriorShape().lateral(this.hellBent.exteriorDoorPos(), this.hellBent.exteriorFacing(), out.position());
            check(Math.abs(lateral) <= this.hellBent.exteriorShape().width() / 2.0, "came out beside the exterior doors, " + lateral + " off centre");
            out.discard();
            ServerLevel overworld = server.overworld();
            Vec3 anchor = this.hellBent.exteriorShape().anchor(this.hellBent.exteriorDoorPos(), this.hellBent.exteriorFacing());
            Vec3 outward = Vec3.atLowerCornerOf(this.hellBent.exteriorFacing().getUnitVec3i());
            Vec3 from = anchor.add(outward.scale(2.0)).add(0.0, 1.0, 0.0);
            ItemEntity item = new ItemEntity(overworld, from.x, from.y, from.z, new ItemStack(Items.DIAMOND));
            item.setDeltaMovement(outward.scale(-0.45).add(0.0, 0.15, 0.0));
            overworld.addFreshEntity(item);
            this.waitFor(() -> itemNear(TardisInteriorManager.interiorLevel(server), this.hellBent.interiorDoorPos(), Items.DIAMOND) != null, 100);
        });
        // Shut, it collides again; broken anywhere, the whole door goes and drops one door.
        this.steps.add(server -> {
            ServerLevel interior = TardisInteriorManager.interiorLevel(server);
            BlockPos master = this.hellBent.interiorDoorPos();
            Direction facing = this.hellBent.interiorDoorFacing();
            ItemEntity in = itemNear(interior, master, Items.DIAMOND);
            PortalShape shape = this.hellBent.interiorShape();
            check(Math.abs(shape.lateral(master, facing, in.position())) <= shape.width() / 2.0 && shape.signedDistance(master, facing, in.position()) > 0.0,
                    "did not come out of the Hell Bent doorway: " + in.position());
            in.discard();
            TardisInteriorManager.get(server).setDoorState(server, this.hellBent, DoorState.CLOSED);
            BlockPos topRight = HellBentDoorBlock.DOORWAY.cell(master, facing, HellBentDoorBlock.DOORWAY.cellCount() - 1);
            check(!interior.getBlockState(topRight).getCollisionShape(interior, topRight).isEmpty(), "shut Hell Bent door does not collide again");
            interior.destroyBlock(topRight, true);
            for (int index = 0; index < HellBentDoorBlock.DOORWAY.cellCount(); index++) {
                check(interior.getBlockState(HellBentDoorBlock.DOORWAY.cell(master, facing, index)).isAir(), "Hell Bent cell " + index + " left behind");
            }
            int drops = interior.getEntities((Entity) null, new AABB(master).inflate(4), e -> e instanceof ItemEntity i
                    && i.getItem().is(ArtronBlocks.HELL_BENT_DOOR_ITEM.get())).stream().mapToInt(e -> ((ItemEntity) e).getItem().getCount()).sum();
            check(drops == 1, "breaking the Hell Bent door dropped " + drops + " doors");
            check(TardisInteriorManager.get(server).loadedDoor(server, this.hellBent, PortalSide.INTERIOR) == null, "broken Hell Bent door still linked");
            TardisInteriorManager.get(server).delete(server, this.hellBent);
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

    private @Nullable ItemEntity parlourEmerald(MinecraftServer server) {
        return itemNear(server.overworld(), this.parlour.exteriorDoorPos(), Items.EMERALD);
    }

    private static @Nullable ItemEntity itemNear(ServerLevel level, BlockPos pos, Item item) {
        return level.getEntities((Entity) null, new AABB(pos).inflate(6), e -> e instanceof ItemEntity i && i.getItem().is(item)).stream()
                .map(ItemEntity.class::cast).findFirst().orElse(null);
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
