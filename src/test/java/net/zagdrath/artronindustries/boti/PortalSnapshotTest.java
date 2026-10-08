/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;

class PortalSnapshotTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 16, 17, 255, 256, 300, 5000})
    void palettedIntsRoundTrip(int paletteSize) {
        Random random = new Random(paletteSize);
        int[] values = new int[9216];
        int[] palette = random.ints(paletteSize, 0, 30_000).toArray();
        for (int i = 0; i < values.length; i++) {
            values[i] = palette[i < paletteSize ? i : random.nextInt(paletteSize)];
        }
        ByteBuf buf = Unpooled.buffer();
        PalettedInts.write(buf, values);
        int[] decoded = PalettedInts.read(buf, values.length);
        assertArrayEquals(values, decoded);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void singleValuePaletteHasNoIndexData() {
        ByteBuf buf = Unpooled.buffer();
        int[] values = new int[38400];
        Arrays.fill(values, 7);
        PalettedInts.write(buf, values);
        assertTrue(buf.readableBytes() < 8, "air-only box should encode in a few bytes, was " + buf.readableBytes());
    }

    @Test
    void rejectsHostileSizes() {
        ByteBuf buf = Unpooled.buffer();
        PalettedInts.write(buf, new int[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> PalettedInts.read(buf, 2));
    }

    @Test
    void snapshotRoundTrip() {
        SnapshotBox box = new SnapshotBox(new BlockPos(4091, 60, -7), 24, 16, 24);
        Random random = new Random(42);
        int[] states = new int[box.volume()];
        byte[] light = new byte[box.volume()];
        for (int i = 0; i < states.length; i++) {
            states[i] = random.nextInt(10) == 0 ? random.nextInt(25_000) : 0;
            light[i] = PortalSnapshot.packLight(random.nextInt(16), random.nextInt(16));
        }
        int[] biomes = new int[box.sizeX() * box.sizeZ()];
        Arrays.fill(biomes, 3);
        biomes[5] = 11;
        CompoundTag sign = new CompoundTag();
        sign.putString("front_text", "Hello");
        Map<Integer, PortalSnapshot.BlockEntityData> blockEntities = Map.of(
                box.index(3, 1, 4), new PortalSnapshot.BlockEntityData(Identifier.withDefaultNamespace("sign"), sign),
                box.index(0, 0, 0), new PortalSnapshot.BlockEntityData(Identifier.withDefaultNamespace("chest"), new CompoundTag()));
        PortalGeometry geometry = new PortalGeometry(new BlockPos(10, 64, 10), Direction.EAST, PortalShape.DEFAULT_DOOR,
                new BlockPos(4096, 65, 7), Direction.NORTH, new PortalShape(2.0F, 3.0F, 0.0F, 0.25F));
        PortalEnvironment env = new PortalEnvironment(0x78a7ff, 0xc0d8ff, 192.0F, 0.5F, 0.0F, 123_456L, true, 7);
        PortalViewKey key = new PortalViewKey(UUID.randomUUID(), PortalSide.EXTERIOR);
        PortalSnapshot snapshot = new PortalSnapshot(key, 99, geometry, box, states, light, biomes, blockEntities, env);

        byte[] bytes = snapshot.toBytes();
        PortalSnapshot decoded = PortalSnapshot.fromBytes(bytes);
        assertEquals(key, decoded.key());
        assertEquals(99, decoded.sequence());
        assertEquals(geometry, decoded.geometry());
        assertEquals(box, decoded.box());
        assertEquals(env, decoded.environment());
        assertArrayEquals(states, decoded.states());
        assertArrayEquals(light, decoded.light());
        assertArrayEquals(biomes, decoded.columnBiomes());
        assertEquals(blockEntities, Map.copyOf(decoded.blockEntities()));
        // Mostly-air box with 10% random states: well under one byte per block for states.
        assertTrue(bytes.length < box.volume() * 3, "unexpectedly large: " + bytes.length);
    }

    @Test
    void truncatedSnapshotIsRejected() {
        SnapshotBox box = new SnapshotBox(BlockPos.ZERO, 4, 4, 4);
        PortalSnapshot snapshot = new PortalSnapshot(new PortalViewKey(UUID.randomUUID(), PortalSide.INTERIOR), 1,
                new PortalGeometry(BlockPos.ZERO, Direction.NORTH, PortalShape.DEFAULT_DOOR, BlockPos.ZERO, Direction.SOUTH, PortalShape.DEFAULT_DOOR),
                box, new int[64], new byte[64], new int[16], Map.of(), PortalEnvironment.DARK);
        byte[] bytes = snapshot.toBytes();
        assertThrows(RuntimeException.class, () -> PortalSnapshot.fromBytes(Arrays.copyOf(bytes, bytes.length - 5)));
    }

    @Test
    void boxStartsInFrontOfThePlaneAndIsCentred() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos door = new BlockPos(100, 64, -50);
            SnapshotBox box = SnapshotBox.inFrontOf(door, facing, PortalShape.DEFAULT_DOOR, 24, 16, 24);
            assertEquals(24 * 16 * 24, box.volume());
            assertTrue(!box.containsWorld(door.getX(), door.getY(), door.getZ()), "box must not contain the far door itself");
            BlockPos inFront = door.relative(facing);
            assertTrue(box.containsWorld(inFront.getX(), inFront.getY(), inFront.getZ()));
            BlockPos far = door.relative(facing, 24);
            assertTrue(box.containsWorld(far.getX(), far.getY(), far.getZ()));
            BlockPos tooFar = door.relative(facing, 25);
            assertTrue(!box.containsWorld(tooFar.getX(), tooFar.getY(), tooFar.getZ()));
            // Every block centre in the box is in front of the doorway plane.
            for (int i = 0; i < box.volume(); i += 97) {
                BlockPos p = box.worldPos(i);
                assertTrue(PortalShape.DEFAULT_DOOR.signedDistance(door, facing, Vec3.atCenterOf(p)) >= 0.5);
            }
        }
    }

    @Test
    void boxIndexingIsABijection() {
        SnapshotBox box = new SnapshotBox(new BlockPos(-5, 3, 9), 7, 5, 6);
        Set<Integer> seen = new HashSet<>();
        for (int y = 0; y < 5; y++) for (int z = 0; z < 6; z++) for (int x = 0; x < 7; x++) {
            int i = box.index(x, y, z);
            assertTrue(seen.add(i));
            assertEquals(x, box.localX(i));
            assertEquals(y, box.localY(i));
            assertEquals(z, box.localZ(i));
        }
        assertEquals(box.volume(), seen.size());
    }
}
