/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * A box of blocks on the far side of a door. Block states are stored as global state ids, light as one byte per block
 * ({@code blockLight << 4 | skyLight}), biomes as one network id per (x, z) column, and block entity data only for block
 * entities with a client renderer (tag {@code artronindustries:boti_render_block_entities}).
 * <p>
 * The arrays are mutable: the client applies deltas to them in place.
 */
public final class PortalSnapshot {
    /** Safety cap on decoded box volume (128^3). */
    public static final int MAX_VOLUME = 128 * 128 * 128;

    public record BlockEntityData(Identifier type, CompoundTag tag) {
        public static final StreamCodec<ByteBuf, BlockEntityData> STREAM_CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, BlockEntityData::type,
                ByteBufCodecs.COMPOUND_TAG, BlockEntityData::tag,
                BlockEntityData::new);
    }

    private final PortalViewKey key;
    private final int sequence;
    private final PortalGeometry geometry;
    private final SnapshotBox box;
    private final int[] states;
    private final byte[] light;
    private final int[] columnBiomes;
    private final Int2ObjectOpenHashMap<BlockEntityData> blockEntities;
    private PortalEnvironment environment;

    public PortalSnapshot(PortalViewKey key, int sequence, PortalGeometry geometry, SnapshotBox box, int[] states, byte[] light,
                          int[] columnBiomes, Map<Integer, BlockEntityData> blockEntities, PortalEnvironment environment) {
        if (states.length != box.volume() || light.length != box.volume() || columnBiomes.length != box.sizeX() * box.sizeZ()) {
            throw new IllegalArgumentException("Snapshot arrays do not match the box");
        }
        this.key = key;
        this.sequence = sequence;
        this.geometry = geometry;
        this.box = box;
        this.states = states;
        this.light = light;
        this.columnBiomes = columnBiomes;
        this.blockEntities = new Int2ObjectOpenHashMap<>(blockEntities);
        this.environment = environment;
    }

    public PortalViewKey key() {
        return this.key;
    }

    public int sequence() {
        return this.sequence;
    }

    public PortalGeometry geometry() {
        return this.geometry;
    }

    public SnapshotBox box() {
        return this.box;
    }

    public int[] states() {
        return this.states;
    }

    public byte[] light() {
        return this.light;
    }

    public int[] columnBiomes() {
        return this.columnBiomes;
    }

    public Int2ObjectOpenHashMap<BlockEntityData> blockEntities() {
        return this.blockEntities;
    }

    public PortalEnvironment environment() {
        return this.environment;
    }

    public void setEnvironment(PortalEnvironment environment) {
        this.environment = environment;
    }

    public static int blockLight(byte packed) {
        return (packed >> 4) & 0xF;
    }

    public static int skyLight(byte packed) {
        return packed & 0xF;
    }

    public static byte packLight(int block, int sky) {
        return (byte) ((block & 0xF) << 4 | (sky & 0xF));
    }

    public void write(ByteBuf buf) {
        PortalViewKey.STREAM_CODEC.encode(buf, this.key);
        ByteBufCodecs.VAR_INT.encode(buf, this.sequence);
        PortalGeometry.STREAM_CODEC.encode(buf, this.geometry);
        SnapshotBox.STREAM_CODEC.encode(buf, this.box);
        PortalEnvironment.STREAM_CODEC.encode(buf, this.environment);
        PalettedInts.write(buf, this.states);
        buf.writeBytes(this.light);
        PalettedInts.write(buf, this.columnBiomes);
        ByteBufCodecs.VAR_INT.encode(buf, this.blockEntities.size());
        for (Int2ObjectOpenHashMap.Entry<BlockEntityData> e : this.blockEntities.int2ObjectEntrySet()) {
            ByteBufCodecs.VAR_INT.encode(buf, e.getIntKey());
            BlockEntityData.STREAM_CODEC.encode(buf, e.getValue());
        }
    }

    public static PortalSnapshot read(ByteBuf buf) {
        PortalViewKey key = PortalViewKey.STREAM_CODEC.decode(buf);
        int sequence = ByteBufCodecs.VAR_INT.decode(buf);
        PortalGeometry geometry = PortalGeometry.STREAM_CODEC.decode(buf);
        SnapshotBox box = SnapshotBox.STREAM_CODEC.decode(buf);
        if (box.sizeX() <= 0 || box.sizeY() <= 0 || box.sizeZ() <= 0 || (long) box.sizeX() * box.sizeY() * box.sizeZ() > MAX_VOLUME) {
            throw new IllegalArgumentException("Bad snapshot box " + box);
        }
        PortalEnvironment environment = PortalEnvironment.STREAM_CODEC.decode(buf);
        int[] states = PalettedInts.read(buf, box.volume());
        byte[] light = new byte[box.volume()];
        buf.readBytes(light);
        int[] biomes = PalettedInts.read(buf, box.sizeX() * box.sizeZ());
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        if (count < 0 || count > box.volume()) {
            throw new IllegalArgumentException("Bad block entity count " + count);
        }
        Int2ObjectOpenHashMap<BlockEntityData> blockEntities = new Int2ObjectOpenHashMap<>(count);
        for (int i = 0; i < count; i++) {
            int index = ByteBufCodecs.VAR_INT.decode(buf);
            blockEntities.put(index, BlockEntityData.STREAM_CODEC.decode(buf));
        }
        return new PortalSnapshot(key, sequence, geometry, box, states, light, biomes, blockEntities, environment);
    }

    public byte[] toBytes() {
        ByteBuf buf = Unpooled.buffer(this.box.volume() * 2);
        try {
            this.write(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    public static PortalSnapshot fromBytes(byte[] bytes) {
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        try {
            PortalSnapshot snapshot = read(buf);
            if (buf.isReadable()) {
                throw new IllegalArgumentException(buf.readableBytes() + " trailing bytes after snapshot");
            }
            return snapshot;
        } finally {
            buf.release();
        }
    }
}
