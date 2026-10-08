/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.exterior;

import java.util.List;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * The Hudolin police box exterior (Blockbench export, 256x256 texture). Facing north (-Z) as built; the doors are the two
 * leaves of the export's {@code doors} part, split so each swings inwards on its own hinge at the outer edge. The centre
 * strip of the door trim is fixed to the left leaf, the one that opens second.
 * <p>
 * Model x is mirrored when drawn (see {@link HudolinExteriorRenderer}), so the leaf at model x 0..9 is the right-hand one
 * for someone outside looking in.
 */
public class HudolinExteriorModel extends Model<HudolinExteriorModel.State> {
    /** How far a fully open leaf has swung in: flat against the side wall. */
    private static final float OPEN_ANGLE = 90.0F * Mth.DEG_TO_RAD;

    private final ModelPart rightDoor;
    private final ModelPart leftDoor;
    /** Everything but the doors. */
    private final List<ModelPart> body;

    /**
     * @param right      open amount of the right leaf, 0 = shut, 1 = open (already eased)
     * @param left       open amount of the left leaf
     * @param doorsOnly  draw only the two leaves (see {@link HudolinExteriorRenderer#submitBehindDoorway})
     */
    public record State(float right, float left, boolean doorsOnly) {}

    public HudolinExteriorModel(ModelPart root) {
        super(root, RenderTypes::entityCutout);
        this.rightDoor = root.getChild("door_right");
        this.leftDoor = root.getChild("door_left");
        this.body = List.of(root.getChild("corner_posts"), root.getChild("sign_plates"), root.getChild("roof"),
                root.getChild("door_trim"), root.getChild("walls"), root.getChild("bb_main"));
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        root.addOrReplaceChild("corner_posts", CubeListBuilder.create()
                .texOffs(113, 0).addBox(10.0F, -49.0F, -13.0F, 3.0F, 47.0F, 3.0F)
                .texOffs(117, 76).addBox(-13.0F, -49.0F, -13.0F, 3.0F, 47.0F, 3.0F)
                .texOffs(126, 0).addBox(-13.0F, -49.0F, 10.0F, 3.0F, 47.0F, 3.0F)
                .texOffs(117, 127).addBox(10.0F, -49.0F, 10.0F, 3.0F, 47.0F, 3.0F), PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition signPlates = root.addOrReplaceChild("sign_plates", CubeListBuilder.create()
                .texOffs(139, 0).addBox(-11.0F, -48.0F, -14.0F, 22.0F, 4.0F, 4.0F)
                .texOffs(139, 9).addBox(-11.0F, -48.0F, 10.0F, 22.0F, 4.0F, 4.0F), PartPose.offset(0.0F, 24.0F, 0.0F));
        signPlates.addOrReplaceChild("cube_r1", CubeListBuilder.create()
                .texOffs(139, 27).addBox(-21.0F, -4.0F, -1.0F, 22.0F, 4.0F, 4.0F), PartPose.offsetAndRotation(-13.0F, -44.0F, -10.0F, 0.0F, 1.5708F, 0.0F));
        signPlates.addOrReplaceChild("cube_r2", CubeListBuilder.create()
                .texOffs(139, 18).addBox(-21.0F, -4.0F, -1.0F, 22.0F, 4.0F, 4.0F), PartPose.offsetAndRotation(11.0F, -44.0F, -10.0F, 0.0F, 1.5708F, 0.0F));

        root.addOrReplaceChild("roof", CubeListBuilder.create()
                .texOffs(0, 31).addBox(-12.0F, -50.0F, -12.0F, 24.0F, 2.0F, 24.0F)
                .texOffs(0, 58).addBox(-10.0F, -51.0F, -10.0F, 20.0F, 1.0F, 20.0F)
                .texOffs(81, 58).addBox(-8.0F, -52.0F, -8.0F, 16.0F, 1.0F, 16.0F), PartPose.offset(0.0F, 24.0F, 0.0F));

        // The fixed frame around the doors. Its centre strip moves with the left leaf instead.
        root.addOrReplaceChild("door_trim", CubeListBuilder.create()
                .texOffs(0, 140).addBox(-10.0F, -43.0F, -12.0F, 1.0F, 41.0F, 1.0F)
                .texOffs(97, 51).addBox(-10.0F, -44.0F, -12.0F, 20.0F, 1.0F, 1.0F)
                .texOffs(5, 140).addBox(9.0F, -43.0F, -12.0F, 1.0F, 41.0F, 1.0F), PartPose.offset(0.0F, 24.0F, 0.0F));

        // Each leaf pivots on its hinge at the inner back corner of the frame (x = +/-9, z = -11), the front outer corner of
        // the shut leaf: it stays tucked behind the frame as it swings, and lies flat against the side wall when open.
        root.addOrReplaceChild("door_right", CubeListBuilder.create()
                .texOffs(130, 76).addBox(-9.0F, -43.0F, 0.0F, 9.0F, 41.0F, 1.0F), PartPose.offset(9.0F, 24.0F, -11.0F));
        root.addOrReplaceChild("door_left", CubeListBuilder.create()
                .texOffs(130, 119).addBox(0.0F, -43.0F, 0.0F, 9.0F, 41.0F, 1.0F)
                .texOffs(10, 140).addBox(8.5F, -43.0F, -0.5F, 1.0F, 41.0F, 1.0F), PartPose.offset(-9.0F, 24.0F, -11.0F));

        PartDefinition walls = root.addOrReplaceChild("walls", CubeListBuilder.create()
                .texOffs(0, 80).addBox(10.0F, -43.0F, -9.0F, 1.0F, 41.0F, 18.0F), PartPose.offset(0.0F, 24.0F, 0.0F));
        walls.addOrReplaceChild("cube_r3", CubeListBuilder.create()
                .texOffs(78, 80).addBox(0.0F, -41.0F, -1.0F, 1.0F, 41.0F, 18.0F), PartPose.offsetAndRotation(-10.0F, -2.0F, 8.0F, 0.0F, 3.1416F, 0.0F));
        walls.addOrReplaceChild("cube_r4", CubeListBuilder.create()
                .texOffs(39, 80).addBox(0.0F, -41.0F, -1.0F, 1.0F, 41.0F, 18.0F), PartPose.offsetAndRotation(8.0F, -2.0F, 10.0F, 0.0F, -1.5708F, 0.0F));
        wallTrim(walls, "wall_trim_1", PartPose.offsetAndRotation(0.0F, 0.0F, -32.0F, 0.0F, -1.5708F, 0.0F), 97, 54, 15, 20, 25);
        wallTrim(walls, "wall_trim_2", PartPose.offsetAndRotation(32.0F, 0.0F, 0.0F, 0.0F, 3.1416F, 0.0F), 139, 36, 30, 35, 40);
        wallTrim(walls, "wall_trim_3", PartPose.offsetAndRotation(0.0F, 0.0F, 32.0F, 0.0F, 1.5708F, 0.0F), 139, 39, 45, 50, 55);

        root.addOrReplaceChild("bb_main", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-14.0F, -2.0F, -14.0F, 28.0F, 2.0F, 28.0F), PartPose.offset(0.0F, 24.0F, 0.0F));

        return LayerDefinition.create(mesh, 256, 256);
    }

    /** The door-like trim on a side or back wall: a top rail at ({@code railU}, {@code railV}), three uprights at v = 140. */
    private static void wallTrim(PartDefinition walls, String name, PartPose pose, int railU, int railV, int outerU, int middleU, int innerU) {
        walls.addOrReplaceChild(name, CubeListBuilder.create()
                .texOffs(railU, railV).addBox(22.0F, -44.0F, -12.0F, 20.0F, 1.0F, 1.0F)
                .texOffs(outerU, 140).addBox(41.0F, -43.0F, -12.0F, 1.0F, 41.0F, 1.0F)
                .texOffs(middleU, 140).addBox(31.5F, -43.0F, -11.5F, 1.0F, 41.0F, 1.0F)
                .texOffs(innerU, 140).addBox(22.0F, -43.0F, -12.0F, 1.0F, 41.0F, 1.0F), pose);
    }

    @Override
    public void setupAnim(State state) {
        super.setupAnim(state);
        // Seen from above, positive yRot turns the right leaf's free end (model -x of its hinge) towards +z, into the box.
        this.rightDoor.yRot = state.right() * OPEN_ANGLE;
        this.leftDoor.yRot = -state.left() * OPEN_ANGLE;
        for (ModelPart part : this.body) {
            part.visible = !state.doorsOnly();
        }
    }
}
