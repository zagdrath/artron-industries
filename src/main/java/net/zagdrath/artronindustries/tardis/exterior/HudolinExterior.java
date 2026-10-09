/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.tardis.exterior;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.artronindustries.portal.PortalShape;
import net.zagdrath.artronindustries.registry.ArtronSounds;
import net.zagdrath.artronindustries.tardis.DoorSounds;
import net.zagdrath.artronindustries.tardis.DoorState;

/**
 * The Hudolin police box (drawn by {@code HudolinExteriorRenderer}). The box is 1 3/4 blocks square and 3 1/4 tall,
 * centred on the lower block, with its double doors on the facing side. Its outline and collision are built from the
 * model's own boxes; the doors are 18 px wide, 41 px tall and stand on the 2 px base, their front face 11 px in front of
 * the block centre.
 */
public final class HudolinExterior extends TardisExterior {
    /**
     * The doorway: the opening between the door frame, its plane on the front face of the shut doors, flush with the back
     * of the frame. The doors swing in behind it and are drawn again over the far side; with the plane any further out,
     * the slice of frame and door between it and the doors would show the far side when seen edge-on.
     */
    public static final PortalShape DOORWAY = new PortalShape(18.0F / 16.0F, 41.0F / 16.0F, 2.0F / 16.0F, 11.0F / 16.0F);

    /** The model, everything but the doors: base, corner posts, walls and their trim, sign plates, roof, door frame. */
    private static final VoxelShape BODY = body();
    private static final VoxelShape RIGHT_SHUT = ModelBox.of(0, -43, -11, 9, 41, 1).shape();
    private static final VoxelShape LEFT_SHUT = Shapes.or(ModelBox.of(-9, -43, -11, 9, 41, 1).shape(), ModelBox.of(-0.5, -43, -11.5, 1, 41, 1).shape());
    /**
     * The shut left leaf's collision while the right one is open, one pixel short of the middle: a player (9.6 px wide)
     * then just fits through the right half.
     */
    private static final VoxelShape LEFT_SHUT_NARROW = ModelBox.of(-9, -43, -11, 8, 41, 1).shape();
    /** Open leaves lie flat against the side walls, turned about their hinges (see HudolinExteriorModel). */
    private static final VoxelShape RIGHT_OPEN = ModelBox.of(-9, -43, 0, 9, 41, 1).turned(1, 9, -11).shape();
    private static final VoxelShape LEFT_OPEN = Shapes.or(ModelBox.of(0, -43, 0, 9, 41, 1).turned(3, -9, -11).shape(),
            ModelBox.of(8.5, -43, -0.5, 1, 41, 1).turned(3, -9, -11).shape());
    /** By part (lower, upper, top), door state, then facing. */
    private static final Map<DoorState, Map<Direction, VoxelShape>>[] OUTLINE = shapes(false);
    private static final Map<DoorState, Map<Direction, VoxelShape>>[] COLLISION = shapes(true);

    private static final DoorSounds SOUNDS = new DoorSounds(ArtronSounds.HUDOLIN_DOOR_OPEN, ArtronSounds.HUDOLIN_DOOR_CLOSE);

    HudolinExterior(Identifier id) {
        super(id);
    }

    @Override
    public PortalShape doorway() {
        return DOORWAY;
    }

    @Override
    public DoorSounds doorSounds() {
        return SOUNDS;
    }

    @Override
    public VoxelShape shape(boolean collision, int part, DoorState doors, Direction facing) {
        return (collision ? COLLISION : OUTLINE)[part].get(doors).get(facing);
    }

    /**
     * A box of the Hudolin model as exported from Blockbench: model pixels, y negative upwards from the ground, x mirrored
     * when drawn. Kept in those units so the shapes can be checked against the model line by line.
     */
    private record ModelBox(double x1, double y1, double z1, double x2, double y2, double z2) {
        /** {@code addBox(x, y, z, w, h, d)} of a part posed at y = 24 (the ground), {@code y} already including any child offset. */
        static ModelBox of(double x, double y, double z, double w, double h, double d) {
            return new ModelBox(x, -(y + h), z, x + w, -y, z + d);
        }

        /** Turned by {@code quarterTurns} of the part's yRot (+90 degrees each), then moved by the part's x/z offset. */
        ModelBox turned(int quarterTurns, double ox, double oz) {
            double[] a = turn(quarterTurns, this.x1, this.z1);
            double[] b = turn(quarterTurns, this.x2, this.z2);
            return new ModelBox(Math.min(a[0], b[0]) + ox, this.y1, Math.min(a[1], b[1]) + oz,
                    Math.max(a[0], b[0]) + ox, this.y2, Math.max(a[1], b[1]) + oz);
        }

        private static double[] turn(int quarterTurns, double x, double z) {
            return switch (Math.floorMod(quarterTurns, 4)) {
                case 0 -> new double[]{x, z};
                case 1 -> new double[]{z, -x};
                case 2 -> new double[]{-x, -z};
                default -> new double[]{-z, x};
            };
        }

        /** In block pixels of the lower block of a north-facing box: model x is mirrored, the model is centred on the block. */
        VoxelShape shape() {
            return Block.box(8 - this.x2, this.y1, 8 + this.z1, 8 - this.x1, this.y2, 8 + this.z2);
        }
    }

    private static VoxelShape body() {
        List<ModelBox> boxes = new ArrayList<>(List.of(
                ModelBox.of(-14, -2, -14, 28, 2, 28),
                ModelBox.of(10, -49, -13, 3, 47, 3),
                ModelBox.of(-13, -49, -13, 3, 47, 3),
                ModelBox.of(-13, -49, 10, 3, 47, 3),
                ModelBox.of(10, -49, 10, 3, 47, 3),
                ModelBox.of(-11, -48, -14, 22, 4, 4),
                ModelBox.of(-11, -48, 10, 22, 4, 4),
                ModelBox.of(-21, -48, -1, 22, 4, 4).turned(1, -13, -10),
                ModelBox.of(-21, -48, -1, 22, 4, 4).turned(1, 11, -10),
                ModelBox.of(-12, -50, -12, 24, 2, 24),
                ModelBox.of(-10, -51, -10, 20, 1, 20),
                ModelBox.of(-8, -52, -8, 16, 1, 16),
                ModelBox.of(-10, -43, -12, 1, 41, 1),
                ModelBox.of(-10, -44, -12, 20, 1, 1),
                ModelBox.of(9, -43, -12, 1, 41, 1),
                ModelBox.of(10, -43, -9, 1, 41, 18),
                ModelBox.of(0, -43, -1, 1, 41, 18).turned(2, -10, 8),
                ModelBox.of(0, -43, -1, 1, 41, 18).turned(3, 8, 10)));
        // The door-like trim on the two sides and the back.
        int[][] trims = {{3, 0, -32}, {2, 32, 0}, {1, 0, 32}};
        for (int[] t : trims) {
            boxes.add(ModelBox.of(22, -44, -12, 20, 1, 1).turned(t[0], t[1], t[2]));
            boxes.add(ModelBox.of(41, -43, -12, 1, 41, 1).turned(t[0], t[1], t[2]));
            boxes.add(ModelBox.of(31.5, -43, -11.5, 1, 41, 1).turned(t[0], t[1], t[2]));
            boxes.add(ModelBox.of(22, -43, -12, 1, 41, 1).turned(t[0], t[1], t[2]));
        }
        VoxelShape shape = Shapes.empty();
        for (ModelBox box : boxes) {
            shape = Shapes.or(shape, box.shape());
        }
        return shape.optimize();
    }

    @SuppressWarnings("unchecked")
    private static Map<DoorState, Map<Direction, VoxelShape>>[] shapes(boolean collision) {
        Map<DoorState, Map<Direction, VoxelShape>>[] shapes = new Map[3];
        for (int part = 0; part < 3; part++) {
            shapes[part] = new EnumMap<>(DoorState.class);
            for (DoorState doors : DoorState.values()) {
                VoxelShape left = doors.leftOpen() ? LEFT_OPEN : collision && doors.rightOpen() ? LEFT_SHUT_NARROW : LEFT_SHUT;
                VoxelShape shape = Shapes.or(BODY, doors.rightOpen() ? RIGHT_OPEN : RIGHT_SHUT, left).optimize();
                // Every part has the whole box, seen from its own height.
                shapes[part].put(doors, Shapes.rotateHorizontal(shape.move(0.0, -part, 0.0)));
            }
        }
        return shapes;
    }
}
