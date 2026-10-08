/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.Config;
import net.zagdrath.artronindustries.block.entity.PortalDoorBlockEntity;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.portal.PortalSide;

/**
 * Server-wide registry of TARDISes: allocates interior cells, remembers both door endpoints and owns the door-open state.
 * Stored in the server's global saved data ({@code data/artronindustries/tardis_interiors.dat}).
 */
public final class TardisInteriorManager extends SavedData {
    private static final Codec<TardisInteriorManager> CODEC = RecordCodecBuilder.create(i -> i.group(
            TardisRecord.CODEC.listOf().fieldOf("tardises").forGetter(m -> List.copyOf(m.byUuid.values())),
            Codec.INT.optionalFieldOf("next_id", 1).forGetter(m -> m.nextId),
            Codec.INT.optionalFieldOf("next_cell", 0).forGetter(m -> m.nextCell),
            Codec.INT.listOf().optionalFieldOf("free_cells", List.of()).forGetter(m -> m.freeCells)
    ).apply(i, TardisInteriorManager::new));

    public static final SavedDataType<TardisInteriorManager> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "tardis_interiors"), TardisInteriorManager::new, CODEC);

    /** Notified on the server thread whenever a record changes (door state, links, deletion). */
    public interface Listener {
        void onTardisChanged(MinecraftServer server, TardisRecord record, boolean deleted);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private final Map<UUID, TardisRecord> byUuid = new LinkedHashMap<>();
    private int nextId;
    private int nextCell;
    private final List<Integer> freeCells;

    private TardisInteriorManager() {
        this(List.of(), 1, 0, List.of());
    }

    private TardisInteriorManager(List<TardisRecord> records, int nextId, int nextCell, List<Integer> freeCells) {
        records.forEach(r -> this.byUuid.put(r.uuid, r));
        this.nextId = nextId;
        this.nextCell = nextCell;
        this.freeCells = new ArrayList<>(freeCells);
    }

    public static TardisInteriorManager get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public static @Nullable ServerLevel interiorLevel(MinecraftServer server) {
        return server.getLevel(ArtronDimensions.TARDIS_INTERIORS);
    }

    public Collection<TardisRecord> all() {
        return Collections.unmodifiableCollection(this.byUuid.values());
    }

    public @Nullable TardisRecord get(@Nullable UUID uuid) {
        return uuid == null ? null : this.byUuid.get(uuid);
    }

    public @Nullable TardisRecord byId(int id) {
        for (TardisRecord r : this.byUuid.values()) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    /** The TARDIS whose interior cell contains {@code pos} (only meaningful in the interior dimension). */
    public @Nullable TardisRecord byInteriorPos(BlockPos pos) {
        for (TardisRecord r : this.byUuid.values()) {
            BlockPos o = r.interiorOrigin;
            if (Math.abs(pos.getX() - o.getX()) <= InteriorGenerator.CLAIM_RADIUS && Math.abs(pos.getZ() - o.getZ()) <= InteriorGenerator.CLAIM_RADIUS) {
                return r;
            }
        }
        return null;
    }

    /** Allocates a new TARDIS with its exterior door at {@code doorPos}. The interior is generated lazily. */
    public TardisRecord create(ServerLevel exteriorLevel, BlockPos doorPos, Direction facing, PortalShape shape) {
        int cell;
        if (Config.REUSE_DELETED_CELLS.getAsBoolean() && !this.freeCells.isEmpty()) {
            cell = this.freeCells.removeFirst();
        } else {
            cell = this.nextCell++;
        }
        CellLayout.Cell c = CellLayout.cell(cell);
        int spacing = Config.CELL_SPACING.getAsInt();
        BlockPos origin = new BlockPos(c.x() * spacing, Config.INTERIOR_Y.getAsInt(), c.z() * spacing);
        TardisRecord record = new TardisRecord(UUID.randomUUID(), this.nextId++, cell, origin,
                InteriorGenerator.doorPos(origin), InteriorGenerator.doorFacing());
        record.exteriorLevel = exteriorLevel.dimension();
        record.exteriorDoorPos = doorPos.immutable();
        record.exteriorFacing = facing;
        record.exteriorShape = shape;
        this.byUuid.put(record.uuid, record);
        this.setDirty();
        ArtronIndustries.LOGGER.info("Created TARDIS #{} ({}) in cell {} at {}", record.id, record.uuid, cell, origin);
        return record;
    }

    public void delete(MinecraftServer server, TardisRecord record) {
        this.setDoorOpen(server, record, false);
        for (PortalSide side : PortalSide.values()) {
            PortalDoorBlockEntity door = this.loadedDoor(server, record, side);
            if (door != null) {
                door.link(null, DoorState.CLOSED);
            }
        }
        this.byUuid.remove(record.uuid);
        this.freeCells.add(record.cellIndex);
        this.setDirty();
        notifyChanged(server, record, true);
    }

    /** Generates the starter interior if it has not been generated yet. Returns false if the interior level is missing. */
    public boolean ensureInterior(MinecraftServer server, TardisRecord record) {
        if (record.interiorGenerated) {
            return true;
        }
        ServerLevel level = interiorLevel(server);
        if (level == null) {
            ArtronIndustries.LOGGER.error("TARDIS interior dimension {} is not loaded", ArtronDimensions.TARDIS_INTERIORS.identifier());
            return false;
        }
        record.interiorGenerated = true;
        this.setDirty();
        InteriorGenerator.generate(level, record);
        notifyChanged(server, record, false);
        return true;
    }

    /** Opens both doors, or shuts them. */
    public void setDoorOpen(MinecraftServer server, TardisRecord record, boolean open) {
        this.setDoorState(server, record, open ? DoorState.BOTH_OPEN : DoorState.CLOSED);
    }

    public void setDoorState(MinecraftServer server, TardisRecord record, DoorState state) {
        if (state.isOpen() && (!record.hasExterior() || !this.ensureInterior(server, record))) {
            return;
        }
        if (record.doorState == state) {
            return;
        }
        record.doorState = state;
        this.setDirty();
        for (PortalSide side : PortalSide.values()) {
            PortalDoorBlockEntity door = this.loadedDoor(server, record, side);
            if (door != null) {
                door.setDoorStateFromManager(state);
            }
        }
        notifyChanged(server, record, false);
    }

    public void linkExterior(MinecraftServer server, TardisRecord record, ServerLevel level, BlockPos pos, Direction facing, PortalShape shape) {
        record.exteriorLevel = level.dimension();
        record.exteriorDoorPos = pos.immutable();
        record.exteriorFacing = facing;
        record.exteriorShape = shape;
        this.setDirty();
        notifyChanged(server, record, false);
    }

    public void unlinkExterior(MinecraftServer server, TardisRecord record) {
        this.setDoorOpen(server, record, false);
        record.exteriorLevel = null;
        record.exteriorDoorPos = null;
        this.setDirty();
        notifyChanged(server, record, false);
    }

    public void linkInteriorDoor(MinecraftServer server, TardisRecord record, BlockPos pos, Direction facing, PortalShape shape) {
        record.interiorDoorPos = pos.immutable();
        record.interiorDoorFacing = facing;
        record.interiorShape = shape;
        this.setDirty();
        notifyChanged(server, record, false);
    }

    /** Called when the interior door is broken: the TARDIS keeps its last door position but cannot open until re-placed. */
    public void unlinkInteriorDoor(MinecraftServer server, TardisRecord record) {
        this.setDoorOpen(server, record, false);
    }

    public static @Nullable ServerLevel level(MinecraftServer server, TardisRecord record, PortalSide side) {
        return side == PortalSide.INTERIOR ? interiorLevel(server) : record.exteriorLevel == null ? null : server.getLevel(record.exteriorLevel);
    }

    /** The door block entity on {@code side}, only if its chunk is already loaded. */
    public @Nullable PortalDoorBlockEntity loadedDoor(MinecraftServer server, TardisRecord record, PortalSide side) {
        ServerLevel level = level(server, record, side);
        BlockPos pos = record.doorPos(side);
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof PortalDoorBlockEntity door ? door : null;
    }

    public static boolean isInterior(Level level) {
        return level.dimension() == ArtronDimensions.TARDIS_INTERIORS;
    }

    private static void notifyChanged(MinecraftServer server, TardisRecord record, boolean deleted) {
        for (Listener listener : LISTENERS) {
            listener.onTardisChanged(server, record, deleted);
        }
    }
}
