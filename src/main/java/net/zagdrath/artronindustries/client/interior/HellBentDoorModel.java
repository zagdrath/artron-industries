/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.block.HellBentDoorBlock;
import net.zagdrath.artronindustries.portal.PortalShape;

/**
 * The Hell Bent door's frame and leaves, made from its texture whenever resources (re)load: split by
 * {@link DoorTextureMask} and each part made solid by {@link ExtrudedPixels}, so the leaves' scalloped edges follow
 * whatever the texture draws.
 */
public final class HellBentDoorModel implements ResourceManagerReloadListener {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "hell_bent_door");
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "textures/block/hell_bent_door.png");
    /** The same texture in the block atlas, where the door is drawn from in the world. */
    public static final Identifier SPRITE = Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "block/hell_bent_door");
    public static final HellBentDoorModel INSTANCE = new HellBentDoorModel();

    /** The door's three parts, in door space (see ExtrudedPixels). */
    public record Parts(List<ExtrudedPixels.Quad> frame, List<ExtrudedPixels.Quad> left, List<ExtrudedPixels.Quad> right) {}

    private volatile @Nullable Parts parts;

    private HellBentDoorModel() {}

    /** The parts, or null if the texture could not be read. */
    public @Nullable Parts parts() {
        return this.parts;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        this.parts = null;
        Resource resource = resourceManager.getResource(TEXTURE).orElse(null);
        if (resource == null) {
            ArtronIndustries.LOGGER.warn("Hell Bent door texture {} is missing; the door is not drawn", TEXTURE);
            return;
        }
        try (InputStream in = resource.open(); NativeImage image = NativeImage.read(in)) {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    argb[y * width + x] = image.getPixel(x, y);
                }
            }
            this.parts = build(argb, width, height);
        } catch (IOException | RuntimeException e) {
            ArtronIndustries.LOGGER.warn("Could not read the Hell Bent door texture {}; the door is not drawn", TEXTURE, e);
        }
    }

    /** The parts of a door texture, its width across the door's two blocks whatever its resolution. */
    static Parts build(int[] argb, int width, int height) {
        byte[] mask = DoorTextureMask.split(argb, width, height, DoorTextureMask.HELL_BENT_FRAME_COLOURS);
        float pixel = (float) HellBentDoorBlock.DOORWAY.width() / width;
        float depth = HellBentDoorBlock.THICKNESS / 16.0F;
        return new Parts(
                ExtrudedPixels.extrude(mask, width, height, DoorTextureMask.FRAME, pixel, depth),
                ExtrudedPixels.extrude(mask, width, height, DoorTextureMask.LEFT, pixel, depth),
                ExtrudedPixels.extrude(mask, width, height, DoorTextureMask.RIGHT, pixel, depth));
    }

    /**
     * From door space to the space of a door block entity whose master cell faces {@code facing}: the texture's bottom
     * left corner on the cell's front bottom left corner, as seen from the room.
     */
    public static Matrix4f doorToBlock(Direction facing) {
        Direction right = PortalShape.right(facing);
        Vector3f r = new Vector3f(right.getStepX(), 0.0F, right.getStepZ());
        Vector3f f = new Vector3f(facing.getStepX(), 0.0F, facing.getStepZ());
        Vector3f corner = new Vector3f(0.5F, 0.0F, 0.5F).add(new Vector3f(f).mul(0.5F)).sub(new Vector3f(r).mul(0.5F));
        // Columns: right, up, out of the front (facing); then the corner.
        return new Matrix4f(
                r.x, 0.0F, r.z, 0.0F,
                0.0F, 1.0F, 0.0F, 0.0F,
                f.x, 0.0F, f.z, 0.0F,
                corner.x, corner.y, corner.z, 1.0F);
    }

    /**
     * Emits {@code quads} into {@code buffer}, a block render type's, placed by {@code pose}, the texture taken from
     * {@code sprite}. Each face is shaded as a block's face would be ({@code lighting}), by its direction in the door's
     * own block space ({@code toBlock}): that is the space the blocks round it were shaded in, even when the door is seen
     * through another doorway.
     */
    public static void emitBlock(List<ExtrudedPixels.Quad> quads, Matrix4f toBlock, PoseStack.Pose pose, VertexConsumer buffer, int light,
                                 TextureAtlasSprite sprite, CardinalLighting lighting) {
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (ExtrudedPixels.Quad quad : quads) {
            toBlock.transformDirection(quad.nx(), quad.ny(), quad.nz(), normal);
            int grey = Math.round(shade(normal, lighting) * 255.0F);
            int colour = ARGB.color(255, grey, grey, grey);
            float[] v = quad.vertices();
            for (int k = 0; k < 20; k += 5) {
                pose.pose().transformPosition(v[k], v[k + 1], v[k + 2], position);
                buffer.addVertex(position.x(), position.y(), position.z()).setColor(colour).setUv(sprite.getU(v[k + 3]), sprite.getV(v[k + 4]))
                        .setLight(light);
            }
        }
    }

    /** A block face's shade for any direction: the axis shades weighted by the squares of the direction's components. */
    private static float shade(Vector3f normal, CardinalLighting lighting) {
        return normal.x() * normal.x() * (normal.x() > 0.0F ? lighting.east() : lighting.west())
                + normal.y() * normal.y() * (normal.y() > 0.0F ? lighting.up() : lighting.down())
                + normal.z() * normal.z() * (normal.z() > 0.0F ? lighting.south() : lighting.north());
    }

    /** Emits {@code quads} into {@code buffer}, an entity render type's for the door's own texture, placed by {@code pose}. */
    public static void emit(List<ExtrudedPixels.Quad> quads, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        for (ExtrudedPixels.Quad quad : quads) {
            pose.transformNormal(quad.nx(), quad.ny(), quad.nz(), normal);
            float[] v = quad.vertices();
            for (int k = 0; k < 20; k += 5) {
                pose.pose().transformPosition(v[k], v[k + 1], v[k + 2], position);
                buffer.addVertex(position.x(), position.y(), position.z(), -1, v[k + 3], v[k + 4], overlay, light, normal.x(), normal.y(), normal.z());
            }
        }
    }
}
