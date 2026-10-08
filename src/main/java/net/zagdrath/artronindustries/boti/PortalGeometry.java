/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.boti;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;
import net.zagdrath.artronindustries.portal.DoorPairTransform;
import net.zagdrath.artronindustries.portal.PortalShape;

/** Both ends of a view: the door the viewer looks through (near) and the door the snapshot was taken behind (far). */
public record PortalGeometry(BlockPos nearPos, Direction nearFacing, PortalShape nearShape, BlockPos farPos, Direction farFacing, PortalShape farShape) {
    public static final StreamCodec<ByteBuf, PortalGeometry> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PortalGeometry::nearPos,
            Direction.STREAM_CODEC, PortalGeometry::nearFacing,
            PortalShape.STREAM_CODEC, PortalGeometry::nearShape,
            BlockPos.STREAM_CODEC, PortalGeometry::farPos,
            Direction.STREAM_CODEC, PortalGeometry::farFacing,
            PortalShape.STREAM_CODEC, PortalGeometry::farShape,
            PortalGeometry::new);

    /** Maps near-side space onto far-side space (walking into the near door = walking out of the far door). */
    public DoorPairTransform nearToFar() {
        return DoorPairTransform.between(this.nearShape.anchor(this.nearPos, this.nearFacing), this.nearFacing,
                this.farShape.anchor(this.farPos, this.farFacing), this.farFacing);
    }
}
