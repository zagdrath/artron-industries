/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.ExteriorTopBlock;
import net.zagdrath.artronindustries.block.InteriorDoorBlock;
import net.zagdrath.artronindustries.block.TestExteriorDoorBlock;

public final class ArtronBlocks {
    /** The TARDIS exterior. Its collision follows the doors, so its shape is not cached. */
    public static final DeferredBlock<TestExteriorDoorBlock> TEST_EXTERIOR_DOOR = ArtronIndustries.BLOCKS.registerBlock(
            "test_exterior_door", TestExteriorDoorBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLUE).strength(3.0F, 1200.0F).sound(SoundType.METAL).noOcclusion().dynamicShape().pushReaction(PushReaction.IMMOVEABLE));
    /** The exterior's third block, for the roof's outline and collision. No item, no drops of its own. */
    public static final DeferredBlock<ExteriorTopBlock> TEST_EXTERIOR_TOP = ArtronIndustries.BLOCKS.registerBlock(
            "test_exterior_top", ExteriorTopBlock::new,
            p -> p.mapColor(MapColor.COLOR_BLUE).strength(3.0F, 1200.0F).sound(SoundType.METAL).noOcclusion().dynamicShape().noLootTable()
                    .pushReaction(PushReaction.IMMOVEABLE));
    /** PLACEHOLDER interior door. */
    public static final DeferredBlock<InteriorDoorBlock> INTERIOR_DOOR = ArtronIndustries.BLOCKS.registerBlock(
            "interior_door", InteriorDoorBlock::new,
            p -> p.mapColor(MapColor.SNOW).strength(3.0F, 1200.0F).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.IMMOVEABLE));

    public static final DeferredItem<BlockItem> TEST_EXTERIOR_DOOR_ITEM = ArtronIndustries.ITEMS.registerSimpleBlockItem(TEST_EXTERIOR_DOOR);
    public static final DeferredItem<BlockItem> INTERIOR_DOOR_ITEM = ArtronIndustries.ITEMS.registerSimpleBlockItem(INTERIOR_DOOR);

    private ArtronBlocks() {}

    public static void init() {}
}
