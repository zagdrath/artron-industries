/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalSnapshot;

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
    }

}
