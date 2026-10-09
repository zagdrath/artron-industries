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
import net.zagdrath.artronindustries.tardis.DoorSounds;
import net.zagdrath.artronindustries.tardis.DoorState;
import net.zagdrath.artronindustries.tardis.TardisInteriorManager;
import net.zagdrath.artronindustries.tardis.TardisRecord;

/**
 * Door block entity shared by the TARDIS and {@code interior_door}. Holds the TARDIS link and
 * the {@link DoorState} mirroring the authoritative one in {@link TardisInteriorManager}, with each door leaf animated
 * locally. Single doors open with the right leaf.
 */
public abstract class PortalDoorBlockEntity extends BlockEntity implements PortalEndpoint {
    private @Nullable UUID tardisId;
    private DoorState doorState = DoorState.CLOSED;
    /** Ticks a leaf takes to swing fully open or shut: as long as this end's door sounds last (DoorSounds). Synced. */
    private int swingTicks = DoorSounds.DEFAULT_SWING_TICKS;
    private final Leaf right = new Leaf();
    private final Leaf left = new Leaf();
    private boolean animationPrimed;

    /** Open animation of one door leaf, in ticks out of {@code swing}. */
    private static final class Leaf {
        int ticks;
        int prevTicks;

        void tick(boolean open, int swing) {
            this.prevTicks = Math.min(this.ticks, swing);
            this.ticks = Mth.clamp(this.ticks + (open ? 1 : -1), 0, swing);
        }

        void snap(boolean open, int swing) {
            this.ticks = this.prevTicks = open ? swing : 0;
        }

        float amount(float partialTick, int swing) {
            return Math.min(Mth.lerp(partialTick, this.prevTicks, this.ticks) / swing, 1.0F);
        }
    }

    protected PortalDoorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public void tick() {
        this.right.tick(this.doorState.rightOpen(), this.swingTicks);
        this.left.tick(this.doorState.leftOpen(), this.swingTicks);
    }

    /**
     * Moves the TARDIS doors on to their next state (right leaf, both, shut). Returns a message explaining why nothing
     * happened, or {@code null} on success.
     */
    public @Nullable Component toggle(ServerLevel level) {
        TardisInteriorManager manager = TardisInteriorManager.get(level.getServer());
        TardisRecord record = manager.get(this.tardisId);
        if (record == null) {
            return Component.translatable("message.artronindustries.door.unlinked");
        }
        if (!record.hasExterior()) {
            return Component.translatable("message.artronindustries.door.no_exterior");
        }
        manager.setDoorState(level.getServer(), record, record.doorState().next());
        return null;
    }

    /** Links this door to a TARDIS. Server only. */
    public void link(@Nullable UUID tardis, DoorState state) {
        this.tardisId = tardis;
        if (this.level instanceof ServerLevel serverLevel) {
            this.swingTicks = this.doorSounds(serverLevel).swingTicks();
        }
        this.setDoorStateFromManager(state);
        this.sync();
    }

    /** Applies the authoritative door state. Server only; synced to clients which animate locally. */
    public void setDoorStateFromManager(DoorState state) {
        if (this.doorState == state) {
            return;
        }
        boolean opening = state.ordinal() > this.doorState.ordinal();
        this.doorState = state;
        if (this.level instanceof ServerLevel serverLevel) {
            DoorSounds sounds = this.doorSounds(serverLevel);
            this.swingTicks = sounds.swingTicks();
            serverLevel.playSound(null, this.worldPosition, (opening ? sounds.open() : sounds.close()).get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        this.sync();
    }

    /**
     * What this end of the doorway sounds like: this door's own sounds if it has any, otherwise its TARDIS's exterior's
     * doors outside and its interior's inside.
     */
    private DoorSounds doorSounds(ServerLevel level) {
        DoorSounds own = this.ownDoorSounds();
        if (own != null) {
            return own;
        }
        TardisRecord record = TardisInteriorManager.get(level.getServer()).get(this.tardisId);
        if (record == null) {
            return DoorSounds.IRON_DOOR;
        }
        return this.getPortalSide() == PortalSide.EXTERIOR ? record.exterior().doorSounds() : record.interior().doorSounds();
    }

    /** The sounds of a door that sounds the same whatever TARDIS it belongs to, such as a placeable interior door; or null. */
    protected @Nullable DoorSounds ownDoorSounds() {
        return null;
    }

    /** Shows the doors fully open, without animating, syncing or playing a sound: for client-side stand-ins. */
    public void showOpen() {
        this.doorState = DoorState.BOTH_OPEN;
        this.snapAnimation();
    }

    public boolean isOpen() {
        return this.doorState.isOpen();
    }

    public DoorState doorState() {
        return this.doorState;
    }

    /** 0 = shut, 1 = fully open, for the {@code left} or right leaf. Interpolated with {@code partialTick} on the client. */
    public float getLeafOpenAmount(boolean left, float partialTick) {
        return (left ? this.left : this.right).amount(partialTick, this.swingTicks);
    }

    private void snapAnimation() {
        this.right.snap(this.doorState.rightOpen(), this.swingTicks);
        this.left.snap(this.doorState.leftOpen(), this.swingTicks);
    }

    /** Ticks a leaf of this door takes to swing fully open or shut. */
    public int swingTicks() {
        return this.swingTicks;
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
                this.doorState = DoorState.CLOSED;
            } else {
                this.doorState = record.doorState();
                this.swingTicks = this.doorSounds(serverLevel).swingTicks();
                this.reconcile(serverLevel, TardisInteriorManager.get(serverLevel.getServer()), record);
            }
            this.snapAnimation();
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

    /** Called on load for a door that is still linked, to bring the record up to date with this door. */
    protected void reconcile(ServerLevel level, TardisInteriorManager manager, TardisRecord record) {
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.tardisId = input.read("tardis", UUIDUtil.CODEC).orElse(null);
        // "open" is what older saves have; "door_state" replaces it.
        this.doorState = input.read("door_state", DoorState.CODEC)
                .orElse(input.getBooleanOr("open", false) ? DoorState.BOTH_OPEN : DoorState.CLOSED);
        this.swingTicks = Math.max(1, input.getIntOr("swing_ticks", DoorSounds.DEFAULT_SWING_TICKS));
        if (!this.animationPrimed) {
            // First load (world load or chunk arriving on the client): show the current state without animating.
            this.animationPrimed = true;
            this.snapAnimation();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("tardis", UUIDUtil.CODEC, this.tardisId);
        output.store("door_state", DoorState.CODEC, this.doorState);
        output.putInt("swing_ticks", this.swingTicks);
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
        return this.right.amount(partialTick, this.swingTicks);
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
