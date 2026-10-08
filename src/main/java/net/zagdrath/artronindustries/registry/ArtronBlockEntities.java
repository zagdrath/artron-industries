/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.registry;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.entity.InteriorDoorBlockEntity;
import net.zagdrath.artronindustries.block.entity.TestExteriorDoorBlockEntity;

public final class ArtronBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ArtronIndustries.MODID);

    public static final Supplier<BlockEntityType<TestExteriorDoorBlockEntity>> TEST_EXTERIOR_DOOR = BLOCK_ENTITY_TYPES.register(
            "test_exterior_door", () -> new BlockEntityType<>(TestExteriorDoorBlockEntity::new, ArtronBlocks.TEST_EXTERIOR_DOOR.get()));
    public static final Supplier<BlockEntityType<InteriorDoorBlockEntity>> INTERIOR_DOOR = BLOCK_ENTITY_TYPES.register(
            "interior_door", () -> new BlockEntityType<>(InteriorDoorBlockEntity::new, ArtronBlocks.INTERIOR_DOOR.get()));

    private ArtronBlockEntities() {}
}
