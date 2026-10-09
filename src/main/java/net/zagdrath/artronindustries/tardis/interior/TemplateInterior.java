/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.interior;

import java.io.IOException;
import java.io.InputStream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.InteriorDoorwayBlock;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * An interior built from a Sponge schematic ({@code data/<namespace>/tardis_interior/<path>.schem}, so data packs can
 * replace it). The schematic's lowest corner is placed at the cell origin. Its door is part of the build: the blocks in
 * the doorway box become an {@link InteriorDoorway} whose leaves swing open.
 */
public final class TemplateInterior implements TardisInterior.Generator {
    private final Identifier template;
    private final InteriorDoorway.Placed doorway;
    private final Direction facing;

    private TemplateInterior(Identifier template, InteriorDoorway.Placed doorway, Direction facing) {
        this.template = template;
        this.doorway = doorway;
        this.facing = facing;
    }

    /**
     * @param template    the schematic, {@code namespace:path} for {@code data/namespace/tardis_interior/path.schem}
     * @param doorwayFrom one corner of the doorway box, in schematic coordinates
     * @param doorwayTo   the opposite corner
     * @param facing      the way the door faces, into the room
     * @param planeDepth  how far behind the front of the box the doorway plane and the hinges are
     */
    public static TardisInterior create(Identifier id, Identifier template, BlockPos doorwayFrom, BlockPos doorwayTo, Direction facing, float planeDepth) {
        InteriorDoorway.Placed doorway = InteriorDoorway.fromBox(doorwayFrom, doorwayTo, facing, planeDepth);
        return new TardisInterior(id, doorway.master(), facing, doorway.doorway().shape(), new TemplateInterior(template, doorway, facing));
    }

    public Identifier resource() {
        return this.template.withPath(p -> "tardis_interior/" + p + ".schem");
    }

    @Override
    public void generate(ServerLevel level, TardisRecord record) {
        StructureTemplate structure = this.load(level);
        BlockPos origin = record.interiorOrigin();
        Vec3i size = structure.getSize();
        for (int cx = origin.getX() >> 4; cx <= (origin.getX() + size.getX()) >> 4; cx++) {
            for (int cz = origin.getZ() >> 4; cz <= (origin.getZ() + size.getZ()) >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        // The build is placed as saved: no shape updates, so walls, fences and panes keep the connections they were built with.
        StructurePlaceSettings settings = new StructurePlaceSettings().setKnownShape(true);
        structure.placeInWorld(level, origin, origin, settings, level.getRandom(), Block.UPDATE_CLIENTS);
        InteriorDoorwayBlock.build(level, record, origin.offset(this.doorway.master()), this.facing, this.doorway.doorway());
    }

    private StructureTemplate load(ServerLevel level) {
        Identifier location = this.resource();
        Resource resource = level.getServer().getResourceManager().getResource(location)
                .orElseThrow(() -> new IllegalStateException("TARDIS interior template " + location + " is missing"));
        CompoundTag tag;
        try (InputStream in = resource.open()) {
            tag = SpongeSchematic.read(in);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read TARDIS interior template " + location, e);
        }
        int version = tag.getIntOr("DataVersion", 0);
        CompoundTag fixed = DataFixTypes.STRUCTURE.updateToCurrentVersion(level.getServer().getFixerUpper(), tag, version);
        StructureTemplate structure = new StructureTemplate();
        structure.load(level.holderLookup(Registries.BLOCK), fixed);
        ArtronIndustries.LOGGER.debug("Loaded TARDIS interior template {} ({}), data version {}", location, structure.getSize(), version);
        return structure;
    }
}
