/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.zagdrath.artronindustries.portal.PortalSide;

/**
 * Identifies one view through a door: the TARDIS plus the side the viewer stands on. {@code nearSide == EXTERIOR} is the
 * interior seen from outside; {@code INTERIOR} is the outside world seen from the interior.
 */
public record PortalViewKey(UUID tardis, PortalSide nearSide) {
    public static final StreamCodec<ByteBuf, PortalViewKey> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, PortalViewKey::tardis,
            PortalSide.STREAM_CODEC, PortalViewKey::nearSide,
            PortalViewKey::new);

    public PortalSide farSide() {
        return this.nearSide.opposite();
    }
}
