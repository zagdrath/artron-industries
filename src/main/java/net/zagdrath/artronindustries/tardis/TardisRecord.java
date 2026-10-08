/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;

/** Everything the server knows about one TARDIS. Mutated only through {@link TardisInteriorManager}. */
public final class TardisRecord {
    public static final Codec<TardisRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("uuid").forGetter(r -> r.uuid),
            Codec.INT.fieldOf("id").forGetter(r -> r.id),
            Codec.INT.fieldOf("cell").forGetter(r -> r.cellIndex),
            BlockPos.CODEC.fieldOf("interior_origin").forGetter(r -> r.interiorOrigin),
            BlockPos.CODEC.fieldOf("interior_door").forGetter(r -> r.interiorDoorPos),
            Direction.CODEC.fieldOf("interior_facing").forGetter(r -> r.interiorDoorFacing),
            PortalShape.CODEC.optionalFieldOf("interior_shape", PortalShape.DEFAULT_DOOR).forGetter(r -> r.interiorShape),
            Codec.BOOL.optionalFieldOf("interior_generated", false).forGetter(r -> r.interiorGenerated),
            Level.RESOURCE_KEY_CODEC.optionalFieldOf("exterior_level").forGetter(r -> Optional.ofNullable(r.exteriorLevel)),
            BlockPos.CODEC.optionalFieldOf("exterior_door").forGetter(r -> Optional.ofNullable(r.exteriorDoorPos)),
            Direction.CODEC.optionalFieldOf("exterior_facing", Direction.NORTH).forGetter(r -> r.exteriorFacing),
            PortalShape.CODEC.optionalFieldOf("exterior_shape", PortalShape.DEFAULT_DOOR).forGetter(r -> r.exteriorShape),
            // door_open is what older saves have; door_state replaces it.
            Codec.BOOL.optionalFieldOf("door_open", false).forGetter(r -> r.doorState.isOpen()),
            DoorState.CODEC.optionalFieldOf("door_state").forGetter(r -> Optional.of(r.doorState))
    ).apply(i, TardisRecord::new));

    final UUID uuid;
    final int id;
    final int cellIndex;
    final BlockPos interiorOrigin;
    BlockPos interiorDoorPos;
    Direction interiorDoorFacing;
    PortalShape interiorShape;
    boolean interiorGenerated;
    @Nullable ResourceKey<Level> exteriorLevel;
    @Nullable BlockPos exteriorDoorPos;
    Direction exteriorFacing;
    PortalShape exteriorShape;
    DoorState doorState;

    private TardisRecord(UUID uuid, int id, int cellIndex, BlockPos interiorOrigin, BlockPos interiorDoorPos, Direction interiorDoorFacing,
                         PortalShape interiorShape, boolean interiorGenerated, Optional<ResourceKey<Level>> exteriorLevel,
                         Optional<BlockPos> exteriorDoorPos, Direction exteriorFacing, PortalShape exteriorShape, boolean doorOpen,
                         Optional<DoorState> doorState) {
        this.uuid = uuid;
        this.id = id;
        this.cellIndex = cellIndex;
        this.interiorOrigin = interiorOrigin;
        this.interiorDoorPos = interiorDoorPos;
        this.interiorDoorFacing = interiorDoorFacing;
        this.interiorShape = interiorShape;
        this.interiorGenerated = interiorGenerated;
        this.exteriorLevel = exteriorLevel.orElse(null);
        this.exteriorDoorPos = exteriorDoorPos.orElse(null);
        this.exteriorFacing = exteriorFacing;
        this.exteriorShape = exteriorShape;
        this.doorState = doorState.orElse(doorOpen ? DoorState.BOTH_OPEN : DoorState.CLOSED);
    }

    TardisRecord(UUID uuid, int id, int cellIndex, BlockPos interiorOrigin, BlockPos interiorDoorPos, Direction interiorDoorFacing) {
        this(uuid, id, cellIndex, interiorOrigin, interiorDoorPos, interiorDoorFacing, PortalShape.DEFAULT_DOOR, false,
                Optional.empty(), Optional.empty(), Direction.NORTH, PortalShape.DEFAULT_DOOR, false, Optional.empty());
    }

    public UUID uuid() {
        return this.uuid;
    }

    /** Short numeric id used by commands. */
    public int id() {
        return this.id;
    }

    public int cellIndex() {
        return this.cellIndex;
    }

    public BlockPos interiorOrigin() {
        return this.interiorOrigin;
    }

    public BlockPos interiorDoorPos() {
        return this.interiorDoorPos;
    }

    public Direction interiorDoorFacing() {
        return this.interiorDoorFacing;
    }

    public PortalShape interiorShape() {
        return this.interiorShape;
    }

    public boolean interiorGenerated() {
        return this.interiorGenerated;
    }

    public @Nullable ResourceKey<Level> exteriorLevel() {
        return this.exteriorLevel;
    }

    public @Nullable BlockPos exteriorDoorPos() {
        return this.exteriorDoorPos;
    }

    public Direction exteriorFacing() {
        return this.exteriorFacing;
    }

    public PortalShape exteriorShape() {
        return this.exteriorShape;
    }

    /** Whether either door leaf is open. */
    public boolean doorOpen() {
        return this.doorState.isOpen();
    }

    public DoorState doorState() {
        return this.doorState;
    }

    public boolean hasExterior() {
        return this.exteriorLevel != null && this.exteriorDoorPos != null;
    }

    public BlockPos doorPos(PortalSide side) {
        return side == PortalSide.EXTERIOR ? this.exteriorDoorPos : this.interiorDoorPos;
    }

    public Direction doorFacing(PortalSide side) {
        return side == PortalSide.EXTERIOR ? this.exteriorFacing : this.interiorDoorFacing;
    }

    public PortalShape shape(PortalSide side) {
        return side == PortalSide.EXTERIOR ? this.exteriorShape : this.interiorShape;
    }

    /** Exterior -> interior transform, or {@code null} while the TARDIS has no exterior. */
    public @Nullable DoorPairTransform transform() {
        if (!this.hasExterior()) {
            return null;
        }
        return DoorPairTransform.between(
                this.exteriorShape.anchor(this.exteriorDoorPos, this.exteriorFacing), this.exteriorFacing,
                this.interiorShape.anchor(this.interiorDoorPos, this.interiorDoorFacing), this.interiorDoorFacing);
    }
}
