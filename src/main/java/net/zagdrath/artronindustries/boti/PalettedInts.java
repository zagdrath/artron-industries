/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * Palette + bit-packed encoding for an int array (block state ids, biome ids). Layout: varint palette size, palette as
 * varints, varint value count, then {@code count} indices packed {@code bits} per value into longs with no value straddling
 * two longs (like vanilla's SimpleBitStorage). A single-entry palette writes no index data at all.
 */
public final class PalettedInts {
    private PalettedInts() {}

    public static int bitsFor(int paletteSize) {
        return paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    public static void write(ByteBuf buf, int[] values) {
        Int2IntOpenHashMap lookup = new Int2IntOpenHashMap();
        lookup.defaultReturnValue(-1);
        IntArrayList palette = new IntArrayList();
        int[] indices = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            int idx = lookup.get(values[i]);
            if (idx < 0) {
                idx = palette.size();
                palette.add(values[i]);
                lookup.put(values[i], idx);
            }
            indices[i] = idx;
        }
        ByteBufCodecs.VAR_INT.encode(buf, palette.size());
        for (int i = 0; i < palette.size(); i++) {
            ByteBufCodecs.VAR_INT.encode(buf, palette.getInt(i));
        }
        ByteBufCodecs.VAR_INT.encode(buf, values.length);
        int bits = bitsFor(palette.size());
        if (bits == 0) {
            return;
        }
        int perLong = 64 / bits;
        long[] packed = new long[(values.length + perLong - 1) / perLong];
        for (int i = 0; i < indices.length; i++) {
            packed[i / perLong] |= (long) indices[i] << ((i % perLong) * bits);
        }
        for (long l : packed) {
            buf.writeLong(l);
        }
    }

    public static int[] read(ByteBuf buf, int maxValues) {
        int paletteSize = ByteBufCodecs.VAR_INT.decode(buf);
        if (paletteSize < 1 || paletteSize > maxValues) {
            throw new IllegalArgumentException("Bad palette size " + paletteSize);
        }
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = ByteBufCodecs.VAR_INT.decode(buf);
        }
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        if (count < 0 || count > maxValues) {
            throw new IllegalArgumentException("Bad value count " + count);
        }
        int[] values = new int[count];
        int bits = bitsFor(paletteSize);
        if (bits == 0) {
            java.util.Arrays.fill(values, palette[0]);
            return values;
        }
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int longs = (count + perLong - 1) / perLong;
        int i = 0;
        for (int l = 0; l < longs; l++) {
            long word = buf.readLong();
            for (int j = 0; j < perLong && i < count; j++, i++) {
                int idx = (int) ((word >>> (j * bits)) & mask);
                if (idx >= paletteSize) {
                    throw new IllegalArgumentException("Palette index out of range");
                }
                values[i] = palette[idx];
            }
        }
        return values;
    }
}
