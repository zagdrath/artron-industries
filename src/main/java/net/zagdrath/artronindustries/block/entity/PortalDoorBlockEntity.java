/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.block.entity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.zagdrath.artronindustries.block.PortalDoorBlock;
import net.zagdrath.artronindustries.portal.PortalEndpoint;
import net.zagdrath.artronindustries.portal.PortalEndpoints;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * PLACEHOLDER door block entity shared by {@code test_exterior_door} and {@code interior_door}. Holds the TARDIS link and
 * a locally animated open amount mirroring the authoritative state in {@link TardisInteriorManager}.
 */
public abstract class PortalDoorBlockEntity extends BlockEntity implements PortalEndpoint {
    /** Ticks a full open or close animation takes. */
    public static final int OPEN_TICKS = 10;

    private @Nullable UUID tardisId;
    private boolean open;
    private int openTicks;
    private int prevOpenTicks;
    private boolean animationPrimed;

    protected PortalDoorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public void tick() {
        this.prevOpenTicks = this.openTicks;
        if (this.open && this.openTicks < OPEN_TICKS) {
            this.openTicks++;
        } else if (!this.open && this.openTicks > 0) {
            this.openTicks--;
        }
    }

    /** Toggles the TARDIS door. Returns a message explaining why nothing happened, or {@code null} on success. */
    public @Nullable Component toggle(ServerLevel level) {
        TardisInteriorManager manager = TardisInteriorManager.get(level.getServer());
        TardisRecord record = manager.get(this.tardisId);
        if (record == null) {
            return Component.translatable("message.artronindustries.door.unlinked");
        }
        if (!record.hasExterior()) {
            return Component.translatable("message.artronindustries.door.no_exterior");
        }
        manager.setDoorOpen(level.getServer(), record, !record.doorOpen());
        return null;
    }

    /** Links this door to a TARDIS. Server only. */
    public void link(@Nullable UUID tardis, boolean open) {
        this.tardisId = tardis;
        this.setOpenFromManager(open);
        this.sync();
    }

    /** Applies the authoritative open state. Server only; synced to clients which animate locally. */
    public void setOpenFromManager(boolean open) {
        if (this.open == open) {
            return;
        }
        this.open = open;
        if (this.level != null) {
            this.level.playSound(null, this.worldPosition, open ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        this.sync();
    }

    public boolean isOpen() {
        return this.open;
    }

    private void sync() {
        this.setChanged();
        if (this.level != null && !this.level.isClientSide()) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level == null) {
            return;
        }
        PortalEndpoints.add(this.level, this);
        if (this.level instanceof ServerLevel serverLevel) {
            // Reconcile with the authoritative record: the TARDIS may have been deleted or toggled while unloaded.
            TardisRecord record = TardisInteriorManager.get(serverLevel.getServer()).get(this.tardisId);
            if (record == null || !this.worldPosition.equals(record.doorPos(this.getPortalSide()))) {
                this.tardisId = null;
                this.open = false;
            } else {
                this.open = record.doorOpen();
            }
            this.openTicks = this.prevOpenTicks = this.open ? OPEN_TICKS : 0;
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.level != null) {
            PortalEndpoints.remove(this.level, this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.level != null) {
            PortalEndpoints.remove(this.level, this);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (this.level instanceof ServerLevel serverLevel) {
            TardisInteriorManager manager = TardisInteriorManager.get(serverLevel.getServer());
            TardisRecord record = manager.get(this.tardisId);
            if (record != null && pos.equals(record.doorPos(this.getPortalSide()))) {
                this.onDoorRemoved(serverLevel, manager, record);
            }
        }
    }

    protected abstract void onDoorRemoved(ServerLevel level, TardisInteriorManager manager, TardisRecord record);

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.tardisId = input.read("tardis", UUIDUtil.CODEC).orElse(null);
        this.open = input.getBooleanOr("open", false);
        if (!this.animationPrimed) {
            // First load (world load or chunk arriving on the client): show the current state without animating.
            this.animationPrimed = true;
            this.openTicks = this.prevOpenTicks = this.open ? OPEN_TICKS : 0;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("tardis", UUIDUtil.CODEC, this.tardisId);
        output.putBoolean("open", this.open);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // --- PortalEndpoint -----------------------------------------------------------------------------------------

    @Override
    public PortalShape getPortalShape() {
        return this.getBlockState().getBlock() instanceof PortalDoorBlock door ? door.portalShape(this.getBlockState()) : PortalShape.DEFAULT_DOOR;
    }

    @Override
    public Direction getFacing() {
        BlockState state = this.getBlockState();
        return state.hasProperty(PortalDoorBlock.FACING) ? state.getValue(PortalDoorBlock.FACING) : Direction.NORTH;
    }

    @Override
    public float getDoorOpenAmount(float partialTick) {
        return Mth.lerp(partialTick, this.prevOpenTicks, this.openTicks) / OPEN_TICKS;
    }

    @Override
    public @Nullable UUID getTardisId() {
        return this.tardisId;
    }

    @Override
    public BlockPos getPortalPos() {
        return this.worldPosition;
    }

    @Override
    public @Nullable Level getPortalLevel() {
        return this.level;
    }

    @Override
    public boolean isPortalRemoved() {
        return this.isRemoved();
    }
}
