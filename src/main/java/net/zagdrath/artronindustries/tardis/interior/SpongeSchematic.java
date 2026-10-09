/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

/**
 * Reads Sponge schematics ({@code .schem}, versions 2 and 3, as written by WorldEdit and FAWE) into the NBT of a vanilla
 * structure template, so the game's own data fixing and placement take over from there.
 * <p>
 * Magenta wool on a corner of the bounding box is taken as a selection marker and left out (placed as air).
 */
public final class SpongeSchematic {
    private static final String MARKER = "minecraft:magenta_wool";
    private static final String AIR = "minecraft:air";
    /** Block states are saved as {@code {id, properties}} from this data version on, {@code {Name, Properties}} before. */
    static final int LOWERCASE_STATE_FIELDS = 5006;

    private SpongeSchematic() {}

    /** Reads a gzipped {@code .schem} and returns the equivalent structure template NBT, with its {@code DataVersion}. */
    public static CompoundTag read(InputStream in) throws IOException {
        return toStructure(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()));
    }

    public static CompoundTag toStructure(CompoundTag root) {
        // Version 3 nests everything in "Schematic"; version 2 has it at the root.
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        int version = schematic.getIntOr("Version", 0);
        if (version != 2 && version != 3) {
            throw new IllegalArgumentException("Unsupported schematic version " + version + " (only Sponge versions 2 and 3 are)");
        }
        int width = schematic.getIntOr("Width", 0);
        int height = schematic.getIntOr("Height", 0);
        int length = schematic.getIntOr("Length", 0);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalArgumentException("Schematic has no size");
        }
        int dataVersion = schematic.getIntOr("DataVersion", 0);
        CompoundTag blocks = version == 3 ? schematic.getCompoundOrEmpty("Blocks") : schematic;
        CompoundTag paletteTag = blocks.getCompoundOrEmpty("Palette");
        byte[] data = blocks.getByteArray(version == 3 ? "Data" : "BlockData")
                .orElseThrow(() -> new IllegalArgumentException("Schematic has no block data"));

        // Schematic palette ids -> structure palette indices, with air first so markers can map to it.
        ListTag palette = new ListTag();
        palette.add(stateTag(AIR, dataVersion));
        Map<Integer, Integer> paletteIndex = new HashMap<>();
        int markerId = -1;
        for (String key : paletteTag.keySet()) {
            int id = paletteTag.getIntOr(key, -1);
            if (key.equals(MARKER)) {
                markerId = id;
            }
            if (key.equals(AIR)) {
                paletteIndex.put(id, 0);
            } else {
                paletteIndex.put(id, palette.size());
                palette.add(stateTag(key, dataVersion));
            }
        }

        Map<Long, CompoundTag> blockEntities = new HashMap<>();
        for (Tag t : blocks.getListOrEmpty("BlockEntities")) {
            if (t instanceof CompoundTag entry) {
                int[] pos = entry.getIntArray("Pos").orElse(null);
                if (pos == null || pos.length != 3) {
                    continue;
                }
                CompoundTag nbt = version == 3 ? entry.getCompoundOrEmpty("Data").copy() : withoutKeys(entry, "Pos", "Id");
                nbt.putString("id", entry.getStringOr("Id", nbt.getStringOr("id", "")));
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                blockEntities.put(key(pos[0], pos[1], pos[2]), nbt);
            }
        }

        ListTag blockList = new ListTag();
        int index = 0;
        int offset = 0;
        while (offset < data.length) {
            // Unsigned LEB128 varints, x fastest, then z, then y.
            int value = 0;
            int shift = 0;
            byte b;
            do {
                b = data[offset++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            int x = index % width;
            int z = index / width % length;
            int y = index / (width * length);
            index++;
            boolean marker = value == markerId && isCorner(x, y, z, width, height, length);
            Integer state = marker ? Integer.valueOf(0) : paletteIndex.get(value);
            if (state == null) {
                throw new IllegalArgumentException("Block data refers to palette id " + value + " which is not in the palette");
            }
            CompoundTag block = new CompoundTag();
            block.put("pos", intList(x, y, z));
            block.putInt("state", state);
            CompoundTag nbt = marker ? null : blockEntities.get(key(x, y, z));
            if (nbt != null) {
                block.put("nbt", nbt);
            }
            blockList.add(block);
        }
        if (index != width * height * length) {
            throw new IllegalArgumentException("Schematic has " + index + " blocks, expected " + width * height * length);
        }

        ListTag entities = new ListTag();
        for (Tag t : schematic.getListOrEmpty("Entities")) {
            if (t instanceof CompoundTag entry) {
                ListTag pos = entry.getListOrEmpty("Pos");
                if (pos.size() != 3) {
                    continue;
                }
                double ex = pos.getDoubleOr(0, 0.0);
                double ey = pos.getDoubleOr(1, 0.0);
                double ez = pos.getDoubleOr(2, 0.0);
                CompoundTag nbt = version == 3 ? entry.getCompoundOrEmpty("Data").copy() : withoutKeys(entry, "Pos", "Id");
                nbt.putString("id", entry.getStringOr("Id", nbt.getStringOr("id", "")));
                // Placed copies get their own identity.
                nbt.remove("UUID");
                CompoundTag entity = new CompoundTag();
                ListTag p = new ListTag();
                p.add(DoubleTag.valueOf(ex));
                p.add(DoubleTag.valueOf(ey));
                p.add(DoubleTag.valueOf(ez));
                entity.put("pos", p);
                entity.put("blockPos", intList((int) Math.floor(ex), (int) Math.floor(ey), (int) Math.floor(ez)));
                entity.put("nbt", nbt);
                entities.add(entity);
            }
        }

        CompoundTag structure = new CompoundTag();
        structure.put("size", intList(width, height, length));
        structure.put("palette", palette);
        structure.put("blocks", blockList);
        structure.put("entities", entities);
        structure.putInt("DataVersion", dataVersion);
        return structure;
    }

    /**
     * {@code minecraft:oak_stairs[facing=south,half=bottom]} as block state NBT in the format of {@code dataVersion}, which
     * the data fixer then brings up to date along with everything else in the schematic.
     */
    static CompoundTag stateTag(String state, int dataVersion) {
        boolean lowercase = dataVersion >= LOWERCASE_STATE_FIELDS;
        CompoundTag tag = new CompoundTag();
        int bracket = state.indexOf('[');
        if (bracket < 0) {
            tag.putString(lowercase ? "id" : "Name", state);
            return tag;
        }
        tag.putString(lowercase ? "id" : "Name", state.substring(0, bracket));
        CompoundTag properties = new CompoundTag();
        String inner = state.substring(bracket + 1, state.endsWith("]") ? state.length() - 1 : state.length());
        for (String pair : inner.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                properties.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        tag.put(lowercase ? "properties" : "Properties", properties);
        return tag;
    }

    private static boolean isCorner(int x, int y, int z, int width, int height, int length) {
        return (x == 0 || x == width - 1) && (y == 0 || y == height - 1) && (z == 0 || z == length - 1);
    }

    private static CompoundTag withoutKeys(CompoundTag tag, String... keys) {
        CompoundTag copy = tag.copy();
        for (String key : keys) {
            copy.remove(key);
        }
        return copy;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x1FFFFF) | (((long) y & 0x1FFFFF) << 21) | (((long) z & 0x1FFFFF) << 42);
    }

    private static ListTag intList(int... values) {
        ListTag list = new ListTag();
        for (int v : values) {
            list.add(IntTag.valueOf(v));
        }
        return list;
    }
}
