/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.PortalDoorBlock;
import net.zagdrath.artronindustries.block.entity.TardisBlockEntity;
import net.zagdrath.artronindustries.boti.PortalGeometry;
import net.zagdrath.artronindustries.boti.PortalSnapshot;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.registry.ArtronBlocks;

/**
 * Lightweight client-side block entity instances for a view, created from the snapshot's block entity NBT so their
 * vanilla renderers can draw them. They are attached to the client level only so renderers that require a level work;
 * they are never added to it and never tick.
 */
public final class BotiBlockEntities {
    private static final Map<BotiClientCache.View, Int2ObjectMap<BlockEntity>> INSTANCES = new IdentityHashMap<>();

    private BotiBlockEntities() {}

    /** Current instances for {@code view}, recreated when the view's block entity data changed. */
    static Collection<BlockEntity> get(BotiClientCache.View view, ClientLevel level) {
        Int2ObjectMap<BlockEntity> map = INSTANCES.get(view);
        if (map == null || view.consumeBlockEntitiesDirty()) {
            map = create(view.snapshot(), level);
            INSTANCES.put(view, map);
        }
        return map.values();
    }

    private static Int2ObjectMap<BlockEntity> create(PortalSnapshot snapshot, ClientLevel level) {
        Int2ObjectMap<BlockEntity> map = new Int2ObjectOpenHashMap<>();
        for (Int2ObjectMap.Entry<PortalSnapshot.BlockEntityData> e : snapshot.blockEntities().int2ObjectEntrySet()) {
            int index = e.getIntKey();
            BlockPos pos = snapshot.box().worldPos(index);
            BlockState state = Block.stateById(snapshot.states()[index]);
            CompoundTag tag = e.getValue().tag().copy();
            tag.putString("id", e.getValue().type().toString());
            try {
                BlockEntity be = BlockEntity.loadStatic(pos, state, tag, level.registryAccess());
                if (be != null) {
                    be.setLevel(level);
                    map.put(index, be);
                }
            } catch (RuntimeException ex) {
                ArtronIndustries.LOGGER.debug("Skipping BOTI block entity {} at {}", e.getValue().type(), pos, ex);
            }
        }
        return map;
    }

    static void release(BotiClientCache.View view) {
        INSTANCES.remove(view);
        ARRIVAL_EXTERIORS.remove(view);
    }

    private static final Map<BotiClientCache.View, BlockEntity> ARRIVAL_EXTERIORS = new IdentityHashMap<>();

    /**
     * For the arrival cover of a view looking out of a TARDIS: a stand-in for the police box behind the exterior doorway,
     * doors open, for as long as the cover is up (the real one is only drawn once its section is compiled, which can be
     * after its block has arrived). Null for views looking in.
     */
    static @Nullable BlockEntity arrivalExterior(BotiClientCache.View view, ClientLevel level) {
        PortalGeometry geometry = view.snapshot().geometry();
        if (view.snapshot().key().nearSide() != PortalSide.INTERIOR) {
            return null;
        }
        BlockPos pos = geometry.farPos();
        return ARRIVAL_EXTERIORS.computeIfAbsent(view, v -> {
            BlockState state = ArtronBlocks.TARDIS.get().defaultBlockState()
                    .setValue(PortalDoorBlock.FACING, geometry.farFacing()).setValue(PortalDoorBlock.HALF, DoubleBlockHalf.LOWER);
            TardisBlockEntity box = new TardisBlockEntity(pos, state);
            box.setLevel(level);
            box.showOpen();
            return box;
        });
    }

}
