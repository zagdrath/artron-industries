/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.zagdrath.artronindustries.boti.SnapshotBox;

/**
 * The ground around a snapshot, made up from its edge: outside the box, every column has the ground of the nearest edge
 * column (the same blocks up to the same height, nothing above), so the surface runs on past the streamed box the way
 * it was at its edge. "Ground" skips plants, leaves and logs, so a tree standing on the edge is not drawn out into a wall.
 * Inside the box it is the snapshot itself, so the skirt's faces meet the real blocks correctly. Immutable.
 */
public final class SkirtBlockGetter implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int NO_GROUND = Integer.MIN_VALUE;

    private final SnapshotBlockGetter snapshot;
    private final SnapshotBox box;
    /** World y of the ground of each column of the box (x fastest), or {@link #NO_GROUND}. */
    private final int[] ground;

    public SkirtBlockGetter(SnapshotBlockGetter snapshot) {
        this.snapshot = snapshot;
        this.box = snapshot.box();
        this.ground = new int[this.box.sizeX() * this.box.sizeZ()];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < this.box.sizeZ(); lz++) {
            for (int lx = 0; lx < this.box.sizeX(); lx++) {
                int top = NO_GROUND;
                for (int y = this.box.maxY(); y >= this.box.origin().getY(); y--) {
                    BlockState state = snapshot.getBlockState(pos.set(this.box.origin().getX() + lx, y, this.box.origin().getZ() + lz));
                    if (isGround(state)) {
                        top = y;
                        break;
                    }
                }
                this.ground[lz * this.box.sizeX() + lx] = top;
            }
        }
    }

    private static boolean isGround(BlockState state) {
        if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
            return false;
        }
        return state.isSolid() || !state.getFluidState().isEmpty();
    }

    public SnapshotBox box() {
        return this.box;
    }

    /** The ground height the column at world {@code (x, z)} has (its own inside the box, its edge column's outside). */
    public int groundAt(int x, int z) {
        return this.ground[(this.clampZ(z) - this.box.origin().getZ()) * this.box.sizeX() + (this.clampX(x) - this.box.origin().getX())];
    }

    public static boolean hasGround(int y) {
        return y != NO_GROUND;
    }

    private int clampX(int x) {
        return Math.clamp(x, this.box.origin().getX(), this.box.maxX());
    }

    private int clampZ(int z) {
        return Math.clamp(z, this.box.origin().getZ(), this.box.maxZ());
    }

    private boolean inBoxColumns(BlockPos pos) {
        return pos.getX() == this.clampX(pos.getX()) && pos.getZ() == this.clampZ(pos.getZ());
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (this.inBoxColumns(pos)) {
            return this.snapshot.getBlockState(pos);
        }
        if (pos.getY() > this.groundAt(pos.getX(), pos.getZ())) {
            return AIR;
        }
        return this.snapshot.getBlockState(new BlockPos(this.clampX(pos.getX()), pos.getY(), this.clampZ(pos.getZ())));
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
        if (this.inBoxColumns(pos)) {
            return this.snapshot.getBrightness(layer, pos);
        }
        // Lit like the edge column at the same height (the snapshot clamps into the box), but never darker than its open
        // air just above its ground.
        return this.snapshot.getBrightness(layer, new BlockPos(pos.getX(), Math.max(pos.getY(), this.groundAt(pos.getX(), pos.getZ()) + 1), pos.getZ()));
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

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return this.snapshot.getBlockTint(new BlockPos(this.clampX(pos.getX()), pos.getY(), this.clampZ(pos.getZ())), resolver);
    }

    @Override
    public int getHeight() {
        return this.snapshot.getHeight();
    }

    @Override
    public int getMinY() {
        return this.snapshot.getMinY();
    }
}
