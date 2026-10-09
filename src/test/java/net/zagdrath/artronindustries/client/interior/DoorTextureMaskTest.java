/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class DoorTextureMaskTest {
    /** The Hell Bent door's frame (.) and left (L) and right (R) leaves, as drawn by hand from its texture. */
    private static final String HELL_BENT = """
            ...LLLLLLLLLLLLLRRRRRRRRRRRRR...
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ...LLLLLLLLLLLLLRRRRRRRRRRRRR...
            ...LLLLLLLLLLLLLRRRRRRRRRRRRR...
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ...LLLLLLLLLLLLLRRRRRRRRRRRRR...
            ...LLLLLLLLLLLLLRRRRRRRRRRRRR...
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            LLLLLLLLLLLLLLLLRRRRRRRRRRRRRRRR
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            .LLLLLLLLLLLLLLLRRRRRRRRRRRRRRR.
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ..LLLLLLLLLLLLLLRRRRRRRRRRRRRR..
            ...LLLLLLLLLLLLLRRRRRRRRRRRRR...
            """;

    /** The Hell Bent door's texture, ARGB. */
    static Texture hellBent() throws IOException {
        try (InputStream in = DoorTextureMaskTest.class.getResourceAsStream("/assets/artronindustries/textures/block/hell_bent_door.png")) {
            assertNotNull(in, "door texture missing");
            BufferedImage image = ImageIO.read(in);
            int[] argb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
            return new Texture(argb, image.getWidth(), image.getHeight());
        }
    }

    record Texture(int[] argb, int width, int height) {
        byte[] split() {
            return DoorTextureMask.split(this.argb, this.width, this.height, DoorTextureMask.HELL_BENT_FRAME_COLOURS);
        }
    }

    static String draw(byte[] mask, int width) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < mask.length; index++) {
            out.append(switch (mask[index]) {
                case DoorTextureMask.FRAME -> '.';
                case DoorTextureMask.LEFT -> 'L';
                case DoorTextureMask.RIGHT -> 'R';
                default -> ' ';
            });
            if (index % width == width - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void hellBentDoorSplitsAlongTheRoundelOutlines() throws IOException {
        Texture texture = hellBent();
        assertEquals(32, texture.width());
        assertEquals(48, texture.height());
        byte[] mask = texture.split();
        assertEquals(HELL_BENT, draw(mask, texture.width()));

        int frame = 0;
        for (byte part : mask) {
            frame += part == DoorTextureMask.FRAME ? 1 : 0;
        }
        assertEquals(120, frame);
        // The thick dark line down the middle moves with the right leaf.
        for (int y = 0; y < texture.height(); y++) {
            assertEquals(DoorTextureMask.RIGHT, mask[y * texture.width() + 16], "x = 16, y = " + y);
            assertEquals(0xFF45494E, texture.argb()[y * texture.width() + 16], "centre line colour at y = " + y);
        }
    }

    /** Frame colour inside a leaf, cut off from the edges by the leaf's outline, stays leaf. */
    @Test
    void frameColoursInsideAnOutlineAreLeaf() {
        int f = DoorTextureMask.HELL_BENT_FRAME_COLOURS[0];
        int o = 0xFF45494E;
        int[] argb = {
                f, o, o, o, o, f,
                f, o, f, f, o, f,
                f, o, o, o, o, f};
        assertEquals("""
                .LLRR.
                .LLRR.
                .LLRR.
                """, draw(DoorTextureMask.split(argb, 6, 3, DoorTextureMask.HELL_BENT_FRAME_COLOURS), 6));
    }
}
