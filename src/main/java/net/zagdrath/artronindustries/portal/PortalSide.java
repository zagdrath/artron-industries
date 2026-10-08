/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.portal;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Which end of a TARDIS door pair an endpoint is. */
public enum PortalSide {
    /** The door in the normal world. Looking through it shows the interior. */
    EXTERIOR,
    /** The door inside the interior dimension. Looking through it shows the outside world. */
    INTERIOR;

    public static final StreamCodec<ByteBuf, PortalSide> STREAM_CODEC = ByteBufCodecs.idMapper(i -> values()[i], Enum::ordinal);

    public PortalSide opposite() {
        return this == EXTERIOR ? INTERIOR : EXTERIOR;
    }
}
