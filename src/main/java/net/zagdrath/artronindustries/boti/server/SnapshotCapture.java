/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti.server;

import java.util.Map;

import org.joml.Vector3fc;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalEnvironment;
import net.minecraft.util.ARGB;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.boti.SnapshotBox;

/** Reads block states, light, biomes and renderable block entities of a box out of a server level. */
public final class SnapshotCapture {
    /** Block entities in this tag are sent with the snapshot so the client can render them. */
    public static final TagKey<BlockEntityType<?>> BOTI_RENDER_BLOCK_ENTITIES = TagKey.create(Registries.BLOCK_ENTITY_TYPE,
            Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_render_block_entities"));

    public final int[] states;
    public final byte[] light;
    public final int[] columnBiomes;
    public final Int2ObjectOpenHashMap<PortalSnapshot.BlockEntityData> blockEntities = new Int2ObjectOpenHashMap<>();

    private SnapshotCapture(SnapshotBox box) {
        this.states = new int[box.volume()];
        this.light = new byte[box.volume()];
        this.columnBiomes = new int[box.sizeX() * box.sizeZ()];
    }

    /** Captures the whole box. Loads missing chunks synchronously (callers hold a ticket on them). */
    public static SnapshotCapture capture(ServerLevel level, SnapshotBox box) {
        SnapshotCapture c = new SnapshotCapture(box);
        Registry<Biome> biomes = level.registryAccess().lookupOrThrow(Registries.BIOME);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos o = box.origin();
        for (int cx = o.getX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = o.getZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                int x0 = Math.max(o.getX(), cx << 4);
                int x1 = Math.min(box.maxX(), (cx << 4) + 15);
                int z0 = Math.max(o.getZ(), cz << 4);
                int z1 = Math.min(box.maxZ(), (cz << 4) + 15);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        int lx = x - o.getX();
                        int lz = z - o.getZ();
                        pos.set(x, o.getY() + box.sizeY() / 2, z);
                        c.columnBiomes[lz * box.sizeX() + lx] = biomes.getId(level.getBiome(pos).value());
                        for (int ly = 0; ly < box.sizeY(); ly++) {
                            pos.set(x, o.getY() + ly, z);
                            int index = box.index(lx, ly, lz);
                            BlockState state = chunk.getBlockState(pos);
                            c.states[index] = Block.getId(state);
                            c.light[index] = PortalSnapshot.packLight(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
                            if (state.hasBlockEntity()) {
                                BlockEntity be = chunk.getBlockEntity(pos);
                                if (be != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.wrapAsHolder(be.getType()).is(BOTI_RENDER_BLOCK_ENTITIES)) {
                                    c.blockEntities.put(index, blockEntityData(level, be));
                                }
                            }
                        }
                    }
                }
            }
        }
        return c;
    }

    public static PortalSnapshot.BlockEntityData blockEntityData(ServerLevel level, BlockEntity be) {
        return new PortalSnapshot.BlockEntityData(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()), be.getUpdateTag(level.registryAccess()));
    }

    /** Samples the far-side atmosphere at {@code pos}. */
    public static PortalEnvironment environment(ServerLevel level, Vec3 pos) {
        var attributes = level.environmentAttributes();
        DimensionType type = level.dimensionType();
        int biomeId = level.registryAccess().lookupOrThrow(Registries.BIOME).getId(level.getBiome(BlockPos.containing(pos)).value());
        boolean hasSky = type.hasSkyLight() && type.skybox() != DimensionType.Skybox.NONE;
        PortalEnvironment.Sky sky = null;
        if (hasSky && type.skybox() == DimensionType.Skybox.OVERWORLD) {
            sky = new PortalEnvironment.Sky(
                    attributes.getValue(EnvironmentAttributes.SUN_ANGLE, pos, null),
                    attributes.getValue(EnvironmentAttributes.MOON_ANGLE, pos, null),
                    attributes.getValue(EnvironmentAttributes.STAR_ANGLE, pos, null),
                    attributes.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, pos, null),
                    attributes.getValue(EnvironmentAttributes.MOON_PHASE, pos, null).index(),
                    ARGB.colorFromVector4f(attributes.getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, pos, null)),
                    ARGB.colorFromVector4f(attributes.getValue(EnvironmentAttributes.CLOUD_COLOR, pos, null)),
                    attributes.getValue(EnvironmentAttributes.CLOUD_HEIGHT, pos, null),
                    level.getGameTime());
        }
        return new PortalEnvironment(
                rgb(attributes.getValue(EnvironmentAttributes.SKY_COLOR, pos, null)),
                rgb(attributes.getValue(EnvironmentAttributes.FOG_COLOR, pos, null)),
                attributes.getValue(EnvironmentAttributes.FOG_START_DISTANCE, pos, null),
                attributes.getValue(EnvironmentAttributes.FOG_END_DISTANCE, pos, null),
                level.getRainLevel(1.0F),
                level.getThunderLevel(1.0F),
                level.getDefaultClockTime(),
                hasSky,
                biomeId,
                new PortalEnvironment.Light(
                        attributes.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, pos, null),
                        rgb(attributes.getValue(EnvironmentAttributes.SKY_LIGHT_COLOR, pos, null)),
                        rgb(attributes.getValue(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, pos, null)),
                        rgb(attributes.getValue(EnvironmentAttributes.BLOCK_LIGHT_TINT, pos, null))),
                sky);
    }

    private static int rgb(Vector3fc v) {
        int r = Math.clamp(Math.round(v.x() * 255.0F), 0, 255);
        int g = Math.clamp(Math.round(v.y() * 255.0F), 0, 255);
        int b = Math.clamp(Math.round(v.z() * 255.0F), 0, 255);
        return r << 16 | g << 8 | b;
    }

    public Map<Integer, PortalSnapshot.BlockEntityData> blockEntityMap() {
        return this.blockEntities;
    }
}
