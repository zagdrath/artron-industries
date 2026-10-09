/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.interior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ExtrudedPixelsTest {
    private static final double EPS = 1.0E-5;
    private static final float PIXEL = 1.0F / 16.0F;
    private static final float DEPTH = 4.0F / 16.0F;

    /** Each part of the Hell Bent door is a closed solid, wound outwards, as big as its pixels. */
    @Test
    void hellBentPartsAreClosedSolids() throws IOException {
        DoorTextureMaskTest.Texture texture = DoorTextureMaskTest.hellBent();
        byte[] mask = texture.split();
        for (byte part : new byte[]{DoorTextureMask.FRAME, DoorTextureMask.LEFT, DoorTextureMask.RIGHT}) {
            int pixels = 0;
            for (byte p : mask) {
                pixels += p == part ? 1 : 0;
            }
            List<ExtrudedPixels.Quad> quads = ExtrudedPixels.extrude(mask, texture.width(), texture.height(), part, PIXEL, DEPTH);
            double[] flux = new double[3];
            double volume = 0.0;
            double front = 0.0;
            for (ExtrudedPixels.Quad quad : quads) {
                float[] v = quad.vertices();
                double[] a = {v[5] - v[0], v[6] - v[1], v[7] - v[2]};
                double[] b = {v[15] - v[0], v[16] - v[1], v[17] - v[2]};
                double[] cross = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
                double area = Math.sqrt(cross[0] * cross[0] + cross[1] * cross[1] + cross[2] * cross[2]);
                double winding = cross[0] * quad.nx() + cross[1] * quad.ny() + cross[2] * quad.nz();
                assertTrue(winding > 0.0, "quad wound against its normal in part " + part);
                flux[0] += quad.nx() * area;
                flux[1] += quad.ny() * area;
                flux[2] += quad.nz() * area;
                // Divergence theorem: the volume is the flux of (x, 0, 0); every vertex of a quad facing x has the same x.
                volume += quad.nx() * area * v[0];
                front += quad.nz() > 0.0F ? area : 0.0;
            }
            for (double f : flux) {
                assertEquals(0.0, f, EPS, "surface of part " + part + " is not closed");
            }
            assertEquals(pixels * PIXEL * PIXEL * DEPTH, volume, EPS, "volume of part " + part);
            assertEquals(pixels * PIXEL * PIXEL, front, EPS, "front of part " + part);
        }
    }

    /** No corner of a quad lies part way along another quad's edge, where the surface would rasterise with cracks. */
    @Test
    void hellBentPartsHaveNoTJunctions() throws IOException {
        DoorTextureMaskTest.Texture texture = DoorTextureMaskTest.hellBent();
        byte[] mask = texture.split();
        for (byte part : new byte[]{DoorTextureMask.FRAME, DoorTextureMask.LEFT, DoorTextureMask.RIGHT}) {
            List<ExtrudedPixels.Quad> quads = ExtrudedPixels.extrude(mask, texture.width(), texture.height(), part, PIXEL, DEPTH);
            List<float[]> corners = new ArrayList<>();
            for (ExtrudedPixels.Quad quad : quads) {
                for (int k = 0; k < 20; k += 5) {
                    corners.add(new float[]{quad.vertices()[k], quad.vertices()[k + 1], quad.vertices()[k + 2]});
                }
            }
            for (ExtrudedPixels.Quad quad : quads) {
                float[] v = quad.vertices();
                for (int edge = 0; edge < 4; edge++) {
                    int a = edge * 5;
                    int b = (edge + 1) % 4 * 5;
                    for (float[] c : corners) {
                        assertTrue(!strictlyInside(v[a], v[a + 1], v[a + 2], v[b], v[b + 1], v[b + 2], c),
                                () -> "T-junction in part " + part + " at " + c[0] * 16 + ", " + c[1] * 16 + ", " + c[2] * 16);
                    }
                }
            }
        }
    }

    /** Whether {@code c} lies on the open segment from a to b. */
    private static boolean strictlyInside(float ax, float ay, float az, float bx, float by, float bz, float[] c) {
        double ex = bx - ax;
        double ey = by - ay;
        double ez = bz - az;
        double t = ((c[0] - ax) * ex + (c[1] - ay) * ey + (c[2] - az) * ez) / (ex * ex + ey * ey + ez * ez);
        if (t <= EPS || t >= 1.0 - EPS) {
            return false;
        }
        double dx = ax + t * ex - c[0];
        double dy = ay + t * ey - c[1];
        double dz = az + t * ez - c[2];
        return dx * dx + dy * dy + dz * dz < EPS * EPS;
    }

    /**
     * Hinged on its outer edge at the front, the left leaf swinging out into the room never goes behind its own back face
     * (so never crosses the doorway plane just behind it) and never passes through the frame beside it. The right leaf is
     * its mirror image.
     */
    @Test
    void hellBentLeftLeafClearsTheFrameAndThePlane() throws IOException {
        DoorTextureMaskTest.Texture texture = DoorTextureMaskTest.hellBent();
        byte[] mask = texture.split();
        int width = texture.width();
        float sample = 0.25F;
        for (int y = 0; y < texture.height(); y++) {
            // Pixels (sixteenths) from the hinge: the frame of this row lies between 0 and its leaf's outer edge.
            int edge = 0;
            while (mask[y * width + edge] == DoorTextureMask.FRAME) {
                edge++;
            }
            for (int degrees = 0; degrees <= 90; degrees += 3) {
                double angle = Math.toRadians(degrees);
                for (float x = edge; x <= width / 2.0F; x += sample) {
                    for (float z = -4.0F; z <= 0.0F; z += sample) {
                        // Out into the room: the leaf's free end, along +x, turns towards +z.
                        double turnedX = x * Math.cos(angle) - z * Math.sin(angle);
                        double turnedZ = x * Math.sin(angle) + z * Math.cos(angle);
                        assertTrue(turnedZ >= -4.0 - EPS, "behind the leaf's back at row " + y + ", " + degrees + " degrees");
                        boolean inFrameDepth = turnedZ < -EPS;
                        assertTrue(!inFrameDepth || turnedX >= edge - EPS, "through the frame at row " + y + ", " + degrees + " degrees");
                    }
                }
            }
        }
    }
}
