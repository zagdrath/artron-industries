/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

class SpongeSchematicTest {
    private static final String PARLOUR = "/data/artronindustries/tardis_interior/victorian_parlour.schem";
    private static CompoundTag structure;
    private static Map<String, CompoundTag> blocks;

    @BeforeAll
    static void readParlour() throws IOException {
        try (InputStream in = SpongeSchematicTest.class.getResourceAsStream(PARLOUR)) {
            assertNotNull(in, "parlour schematic missing from the resources");
            structure = SpongeSchematic.read(in);
        }
        blocks = new HashMap<>();
        for (Tag t : structure.getListOrEmpty("blocks")) {
            CompoundTag block = (CompoundTag) t;
            ListTag pos = block.getListOrEmpty("pos");
            blocks.put(pos.getIntOr(0, -1) + "," + pos.getIntOr(1, -1) + "," + pos.getIntOr(2, -1), block);
        }
    }

    private static String name(int x, int y, int z) {
        CompoundTag block = blocks.get(x + "," + y + "," + z);
        int state = block.getIntOr("state", -1);
        return ((CompoundTag) structure.getListOrEmpty("palette").get(state)).getStringOr("id", "?");
    }

    @Test
    void sizeAndVersion() {
        ListTag size = structure.getListOrEmpty("size");
        assertEquals(39, size.getIntOr(0, 0));
        assertEquals(16, size.getIntOr(1, 0));
        assertEquals(43, size.getIntOr(2, 0));
        assertEquals(39 * 16 * 43, blocks.size());
        assertEquals(5023, structure.getIntOr("DataVersion", 0));
        assertEquals("minecraft:air", ((CompoundTag) structure.getListOrEmpty("palette").getFirst()).getStringOr("id", "?"));
    }

    @Test
    void blocksLandWhereTheyWereBuilt() {
        // The doorway: gray concrete powder (left as built), gray concrete, button panelling in front, spruce floor.
        assertEquals("minecraft:gray_concrete_powder", name(12, 2, 39));
        assertEquals("minecraft:gray_concrete_powder", name(13, 5, 39));
        assertEquals("minecraft:gray_concrete", name(14, 2, 39));
        assertEquals("minecraft:gray_concrete", name(15, 5, 39));
        assertEquals("minecraft:spruce_button", name(12, 2, 38));
        assertEquals("minecraft:spruce_planks", name(13, 1, 37));
    }

    @Test
    void propertiesAreSplitOut() {
        CompoundTag button = (CompoundTag) structure.getListOrEmpty("palette").get(blocks.get("12,2,38").getIntOr("state", -1));
        assertEquals("wall", button.getCompoundOrEmpty("properties").getStringOr("face", "?"));
        assertEquals("north", button.getCompoundOrEmpty("properties").getStringOr("facing", "?"));
    }

    @Test
    void cornerMarkersAreStripped() {
        assertEquals("minecraft:air", name(0, 0, 0));
        assertEquals("minecraft:air", name(38, 15, 42));
    }

    @Test
    void blockEntitiesKeepTheirData() {
        int withNbt = 0;
        for (CompoundTag block : blocks.values()) {
            CompoundTag nbt = block.getCompound("nbt").orElse(null);
            if (nbt != null) {
                withNbt++;
                assertTrue(nbt.getStringOr("id", "").startsWith("minecraft:"), () -> "block entity without an id: " + nbt);
                assertTrue(nbt.getInt("x").isEmpty(), "block entity kept its old position");
            }
        }
        assertEquals(81, withNbt);
        assertTrue(blocks.get("8,1,9").getCompound("nbt").orElseThrow().getStringOr("id", "").equals("minecraft:barrel"));
    }

    @Test
    void stateStrings() {
        CompoundTag plain = SpongeSchematic.stateTag("minecraft:stone", 5023);
        assertEquals("minecraft:stone", plain.getStringOr("id", "?"));
        assertTrue(plain.getCompound("properties").isEmpty());
        CompoundTag stairs = SpongeSchematic.stateTag("minecraft:oak_stairs[facing=south,half=bottom]", 5023);
        assertEquals("minecraft:oak_stairs", stairs.getStringOr("id", "?"));
        assertEquals("bottom", stairs.getCompoundOrEmpty("properties").getStringOr("half", "?"));
    }

    /** Older schematics keep the field names of their own version, for the data fixer to rename. */
    @Test
    void olderStateFields() {
        CompoundTag stairs = SpongeSchematic.stateTag("minecraft:oak_stairs[facing=south]", SpongeSchematic.LOWERCASE_STATE_FIELDS - 1);
        assertEquals("minecraft:oak_stairs", stairs.getStringOr("Name", "?"));
        assertEquals("south", stairs.getCompoundOrEmpty("Properties").getStringOr("facing", "?"));
    }

    @Test
    void rejectsOtherVersions() {
        CompoundTag v1 = new CompoundTag();
        v1.putInt("Version", 1);
        assertThrows(IllegalArgumentException.class, () -> SpongeSchematic.toStructure(v1));
    }
}
