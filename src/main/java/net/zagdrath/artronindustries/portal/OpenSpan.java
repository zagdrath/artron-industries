/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.portal;

/**
 * The open part of a doorway, across its width: {@code from} and {@code to} are fractions of the {@link PortalShape} width
 * measured from the viewer's left edge (0 = left, 1 = right). Double doors open one half at a time.
 */
public record OpenSpan(float from, float to) {
    public static final OpenSpan NONE = new OpenSpan(0.0F, 0.0F);
    public static final OpenSpan FULL = new OpenSpan(0.0F, 1.0F);
    public static final OpenSpan LEFT_HALF = new OpenSpan(0.0F, 0.5F);
    public static final OpenSpan RIGHT_HALF = new OpenSpan(0.5F, 1.0F);

    public OpenSpan {
        from = Math.clamp(from, 0.0F, 1.0F);
        to = Math.clamp(to, from, 1.0F);
    }

    public boolean isEmpty() {
        return this.to - this.from <= 0.001F;
    }

    /** The span covered by whichever halves are open. */
    public static OpenSpan ofHalves(boolean left, boolean right) {
        if (left && right) {
            return FULL;
        }
        return left ? LEFT_HALF : right ? RIGHT_HALF : NONE;
    }
}
