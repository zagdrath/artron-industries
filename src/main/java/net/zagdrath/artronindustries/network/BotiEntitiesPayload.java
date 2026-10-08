/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalViewKey;

/**
 * S2C: the entities currently inside a view's snapshot box (far-side coordinates), sent every
 * {@code boti.entityUpdateInterval} ticks. Entities missing from a payload are gone. Synched entity data (what vanilla
 * renderers read: colours, baby flag, poses, held items...) is included when an entity is new to the view and
 * periodically after that.
 */
public record BotiEntitiesPayload(PortalViewKey key, List<Entry> entities) implements CustomPacketPayload {
    public static final int MAX_ENTITIES = 256;

    /** Profile of a player entity, so the client can create a stand-in that renders their skin. */
    public record PlayerProfile(UUID id, String name) {}

    public record Entry(int id, Identifier type, Optional<PlayerProfile> player, double x, double y, double z,
                        float yRot, float xRot, float headYRot, float bodyYRot, Optional<List<SynchedEntityData.DataValue<?>>> data) {
        void write(RegistryFriendlyByteBuf buf) {
            buf.writeVarInt(this.id);
            Identifier.STREAM_CODEC.encode(buf, this.type);
            buf.writeBoolean(this.player.isPresent());
            this.player.ifPresent(p -> {
                UUIDUtil.STREAM_CODEC.encode(buf, p.id());
                buf.writeUtf(p.name(), 64);
            });
            buf.writeDouble(this.x);
            buf.writeDouble(this.y);
            buf.writeDouble(this.z);
            buf.writeFloat(this.yRot);
            buf.writeFloat(this.xRot);
            buf.writeFloat(this.headYRot);
            buf.writeFloat(this.bodyYRot);
            buf.writeBoolean(this.data.isPresent());
            this.data.ifPresent(values -> {
                for (SynchedEntityData.DataValue<?> value : values) {
                    value.write(buf);
                }
                buf.writeByte(0xFF);
            });
        }

        static Entry read(RegistryFriendlyByteBuf buf) {
            int id = buf.readVarInt();
            Identifier type = Identifier.STREAM_CODEC.decode(buf);
            Optional<PlayerProfile> player = buf.readBoolean()
                    ? Optional.of(new PlayerProfile(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64)))
                    : Optional.empty();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float yRot = buf.readFloat();
            float xRot = buf.readFloat();
            float head = buf.readFloat();
            float body = buf.readFloat();
            Optional<List<SynchedEntityData.DataValue<?>>> data = Optional.empty();
            if (buf.readBoolean()) {
                List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
                int dataId;
                while ((dataId = buf.readUnsignedByte()) != 0xFF) {
                    values.add(SynchedEntityData.DataValue.read(buf, dataId));
                }
                data = Optional.of(values);
            }
            return new Entry(id, type, player, x, y, z, yRot, xRot, head, body, data);
        }
    }

    public static final Type<BotiEntitiesPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArtronIndustries.MODID, "boti_entities"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BotiEntitiesPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public BotiEntitiesPayload decode(RegistryFriendlyByteBuf buf) {
            PortalViewKey key = PortalViewKey.STREAM_CODEC.decode(buf);
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            if (count < 0 || count > MAX_ENTITIES) {
                throw new IllegalArgumentException("Bad BOTI entity count " + count);
            }
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(Entry.read(buf));
            }
            return new BotiEntitiesPayload(key, entries);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, BotiEntitiesPayload payload) {
            PortalViewKey.STREAM_CODEC.encode(buf, payload.key);
            ByteBufCodecs.VAR_INT.encode(buf, payload.entities.size());
            for (Entry entry : payload.entities) {
                entry.write(buf);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
