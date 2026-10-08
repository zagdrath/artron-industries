/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.registry.ArtronBlockEntities;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * PLACEHOLDER for the TARDIS exterior. Placing it allocates a new TARDIS and links this door as its exterior. The real
 * exterior (built separately) replaces this block; it only has to implement PortalEndpoint and supply a PortalShape.
 */
public class TestExteriorDoorBlock extends PortalDoorBlock {
    public TestExteriorDoorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected BlockEntityType<? extends PortalDoorBlockEntity> blockEntityType() {
        return ArtronBlockEntities.TEST_EXTERIOR_DOOR.get();
    }

    @Override
    protected void onPlacedServer(ServerLevel level, BlockPos pos, BlockState state, PortalDoorBlockEntity door, @Nullable LivingEntity placer) {
        TardisRecord record = TardisInteriorManager.get(level.getServer())
                .create(level, pos, state.getValue(FACING), this.portalShape(state));
        door.link(record.uuid(), false);
        if (placer instanceof Player player) {
            player.sendOverlayMessage(Component.translatable("message.artronindustries.tardis.created", record.id()));
        }
    }

    /**
     * Places both halves of a door at {@code pos} and allocates a new TARDIS for it, as if a player had placed it.
     * Returns {@code null} if there is no room.
     */
    public @Nullable TardisRecord placeNewTardis(ServerLevel level, BlockPos pos, Direction facing) {
        if (!level.getBlockState(pos).canBeReplaced() || !level.getBlockState(pos.above()).canBeReplaced()) {
            return null;
        }
        BlockState lower = this.defaultBlockState().setValue(FACING, facing).setValue(HALF, DoubleBlockHalf.LOWER);
        level.setBlockAndUpdate(pos, lower);
        level.setBlockAndUpdate(pos.above(), lower.setValue(HALF, DoubleBlockHalf.UPPER));
        if (!(level.getBlockEntity(pos) instanceof PortalDoorBlockEntity door)) {
            return null;
        }
        TardisRecord record = TardisInteriorManager.get(level.getServer()).create(level, pos, facing, this.portalShape(lower));
        door.link(record.uuid(), false);
        return record;
    }
}
