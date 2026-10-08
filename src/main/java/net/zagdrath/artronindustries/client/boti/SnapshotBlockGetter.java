/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.SnapshotBox;

/**
 * Read-only view of a snapshot for the vanilla block and fluid renderers. Coordinates are far-side world coordinates;
 * anything outside the box is air with no light. Immutable (it owns copies of the snapshot arrays), so it is safe to
 * mesh from off the render thread.
 */
public final class SnapshotBlockGetter implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final SnapshotBox box;
    private final BlockState[] states;
    private final byte[] light;
    private final Biome[] columnBiomes;
    private final @Nullable Biome fallbackBiome;
    /**
     * When the viewer's dimension has no sky light (looking out of a TARDIS), its lightmap cannot show sky light, so the
     * far side's sky light is folded into block light, scaled by the far side's daylight (0..1). Negative = disabled.
     */
    private final float skyToBlock;

    public SnapshotBlockGetter(PortalSnapshot snapshot, Registry<Biome> biomes, float skyToBlock) {
        this.box = snapshot.box();
        int[] ids = snapshot.states();
        this.states = new BlockState[ids.length];
        for (int i = 0; i < ids.length; i++) {
            this.states[i] = Block.stateById(ids[i]);
        }
        this.light = snapshot.light().clone();
        int[] biomeIds = snapshot.columnBiomes();
        this.columnBiomes = new Biome[biomeIds.length];
        Biome fallback = biomes.byId(snapshot.environment().biomeId());
        for (int i = 0; i < biomeIds.length; i++) {
            Biome biome = biomes.byId(biomeIds[i]);
            this.columnBiomes[i] = biome != null ? biome : fallback;
            if (fallback == null) {
                fallback = biome;
            }
        }
        this.fallbackBiome = fallback;
        this.skyToBlock = skyToBlock;
    }

    public static Registry<Biome> biomeRegistry(net.minecraft.world.level.Level level) {
        return level.registryAccess().lookupOrThrow(Registries.BIOME);
    }

    public SnapshotBox box() {
        return this.box;
    }

    private int index(BlockPos pos) {
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        return this.box.containsWorld(x, y, z) ? this.box.indexOfWorld(x, y, z) : -1;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        int i = this.index(pos);
        return i < 0 ? AIR : this.states[i];
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.getBlockState(pos).getFluidState();
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        int i = this.index(pos);
        if (i < 0) {
            return 0;
        }
        byte packed = this.light[i];
        int block = PortalSnapshot.blockLight(packed);
        int sky = PortalSnapshot.skyLight(packed);
        if (this.skyToBlock >= 0.0F) {
            return layer == LightLayer.BLOCK ? Math.max(block, Math.round(sky * this.skyToBlock)) : 0;
        }
        return layer == LightLayer.BLOCK ? block : sky;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int darkening) {
        return Math.max(this.getBrightness(LightLayer.BLOCK, pos), this.getBrightness(LightLayer.SKY, pos) - darkening);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return LevelLightEngine.EMPTY;
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return CardinalLighting.DEFAULT;
    }

    /** Biome tint blended over the 3x3 columns around {@code pos} (clamped to the box). */
    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        int r = 0;
        int g = 0;
        int b = 0;
        int n = 0;
        int lx0 = pos.getX() - this.box.origin().getX();
        int lz0 = pos.getZ() - this.box.origin().getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int lx = Math.clamp(lx0 + dx, 0, this.box.sizeX() - 1);
                int lz = Math.clamp(lz0 + dz, 0, this.box.sizeZ() - 1);
                Biome biome = this.columnBiomes[lz * this.box.sizeX() + lx];
                if (biome == null) {
                    continue;
                }
                int c = resolver.getColor(biome, pos.getX() + dx, pos.getZ() + dz);
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return this.fallbackBiome == null ? -1 : resolver.getColor(this.fallbackBiome, pos.getX(), pos.getZ());
        }
        return 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
    }

    @Override
    public int getHeight() {
        return this.box.sizeY();
    }

    @Override
    public int getMinY() {
        return this.box.origin().getY();
    }
}
