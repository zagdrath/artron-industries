/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.registry;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.HellBentDoorBlock;
import net.zagdrath.artronindustries.block.InteriorDoorBlock;
import net.zagdrath.artronindustries.block.InteriorDoorwayBlock;
import net.zagdrath.artronindustries.block.RoundelBlock;
import net.zagdrath.artronindustries.block.TardisBlock;
import net.zagdrath.artronindustries.block.TardisTopBlock;

public final class ArtronBlocks {
    /** The TARDIS. Its collision follows the doors, so its shape is not cached. */
    public static final DeferredBlock<TardisBlock> TARDIS = ArtronIndustries.BLOCKS.registerBlock(
            "tardis", TardisBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLUE).strength(3.0F, 1200.0F).sound(SoundType.METAL).noOcclusion().dynamicShape().pushReaction(PushReaction.IMMOVEABLE));
    /** The TARDIS's third block, for the roof's outline and collision. No item, no drops of its own. */
    public static final DeferredBlock<TardisTopBlock> TARDIS_TOP = ArtronIndustries.BLOCKS.registerBlock(
            "tardis_top", TardisTopBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLUE).strength(3.0F, 1200.0F).sound(SoundType.METAL).noOcclusion().dynamicShape().noLootTable()
                    .pushReaction(PushReaction.IMMOVEABLE));
    /** PLACEHOLDER interior door. */
    public static final DeferredBlock<InteriorDoorBlock> INTERIOR_DOOR = ArtronIndustries.BLOCKS.registerBlock(
            "interior_door", InteriorDoorBlock::new,
            p -> p.mapColor(MapColor.SNOW).strength(3.0F, 1200.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.IMMOVEABLE));
    /** The Hell Bent roundel door: a 2x3 interior door, one block per cell (see HellBentDoorBlock). */
    public static final DeferredBlock<HellBentDoorBlock> HELL_BENT_DOOR = ArtronIndustries.BLOCKS.registerBlock(
            "hell_bent_door", HellBentDoorBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(3.0F, 1200.0F).sound(SoundType.STONE).noOcclusion().pushReaction(PushReaction.IMMOVEABLE));
    /** A TARDIS roundel wall. */
    public static final DeferredBlock<RoundelBlock> ROUNDEL = ArtronIndustries.BLOCKS.registerBlock(
            "roundel", RoundelBlock::new,
            p -> p.mapColor(MapColor.STONE).strength(1.5F, 6.0F).sound(SoundType.STONE).requiresCorrectToolForDrops());
    /** A cell of a template interior's door (see InteriorDoorway). Placed only by interior generation; no item, no drops. */
    public static final DeferredBlock<InteriorDoorwayBlock> INTERIOR_DOORWAY = ArtronIndustries.BLOCKS.registerBlock(
            "interior_doorway", InteriorDoorwayBlock::new,
            p -> p.strength(-1.0F, 3600000.0F).noLootTable().noOcclusion().pushReaction(PushReaction.IMMOVEABLE));

    public static final DeferredItem<BlockItem> TARDIS_ITEM = ArtronIndustries.ITEMS.registerSimpleBlockItem(TARDIS);
    public static final DeferredItem<BlockItem> INTERIOR_DOOR_ITEM = ArtronIndustries.ITEMS.registerSimpleBlockItem(INTERIOR_DOOR);
    public static final DeferredItem<BlockItem> HELL_BENT_DOOR_ITEM = ArtronIndustries.ITEMS.registerSimpleBlockItem(HELL_BENT_DOOR);
    public static final DeferredItem<BlockItem> ROUNDEL_ITEM = ArtronIndustries.ITEMS.registerSimpleBlockItem(ROUNDEL);

    static {
        // The TARDIS was test_exterior_door (and test_exterior_top) before it was the real thing; worlds from then keep it.
        ArtronIndustries.BLOCKS.addAlias(id("test_exterior_door"), TARDIS.getId());
        ArtronIndustries.BLOCKS.addAlias(id("test_exterior_top"), TARDIS_TOP.getId());
        ArtronIndustries.ITEMS.addAlias(id("test_exterior_door"), TARDIS_ITEM.getId());
    }

    private ArtronBlocks() {}

    public static void init() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, path);
    }
}
