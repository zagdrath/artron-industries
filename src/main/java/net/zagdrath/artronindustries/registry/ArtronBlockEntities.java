/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.registry;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.entity.HellBentDoorBlockEntity;
import net.zagdrath.artronindustries.block.entity.InteriorDoorBlockEntity;
import net.zagdrath.artronindustries.block.entity.InteriorDoorwayBlockEntity;
import net.zagdrath.artronindustries.block.entity.TardisBlockEntity;

public final class ArtronBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ArtronIndustries.MODID);

    public static final Supplier<BlockEntityType<TardisBlockEntity>> TARDIS = BLOCK_ENTITY_TYPES.register(
            "tardis", () -> new BlockEntityType<>(TardisBlockEntity::new, ArtronBlocks.TARDIS.get()));
    public static final Supplier<BlockEntityType<InteriorDoorBlockEntity>> INTERIOR_DOOR = BLOCK_ENTITY_TYPES.register(
            "interior_door", () -> new BlockEntityType<>(InteriorDoorBlockEntity::new, ArtronBlocks.INTERIOR_DOOR.get()));
    public static final Supplier<BlockEntityType<InteriorDoorwayBlockEntity>> INTERIOR_DOORWAY = BLOCK_ENTITY_TYPES.register(
            "interior_doorway", () -> new BlockEntityType<>(InteriorDoorwayBlockEntity::new, ArtronBlocks.INTERIOR_DOORWAY.get()));
    public static final Supplier<BlockEntityType<HellBentDoorBlockEntity>> HELL_BENT_DOOR = BLOCK_ENTITY_TYPES.register(
            "hell_bent_door", () -> new BlockEntityType<>(HellBentDoorBlockEntity::new, ArtronBlocks.HELL_BENT_DOOR.get()));

    static {
        // Was test_exterior_door; see ArtronBlocks.
        BLOCK_ENTITY_TYPES.addAlias(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "test_exterior_door"),
                Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "tardis"));
    }

    private ArtronBlockEntities() {}
}
