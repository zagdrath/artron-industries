/*
 * Copyright (c) 2026 Zagdrath
 * SPDX-License-Identifier: MIT
 */

package net.zagdrath.artronindustries.client.boti;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.authlib.GameProfile;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.zagdrath.artronindustries.ArtronIndustries;
import net.zagdrath.artronindustries.boti.PortalViewKey;
import net.zagdrath.artronindustries.client.ArtronClientConfig;
import net.zagdrath.artronindustries.network.BotiEntitiesPayload;

/**
 * Lightweight stand-ins for the entities on the far side of each view. They live in a detached registry: created with
 * the client level so their renderers work, but never added to it, never ticked by the game, and positioned in far-side
 * coordinates. Each client tick they move part of the way to the last received position, so vanilla's partial-tick
 * interpolation produces smooth motion between the (every-2-ticks) updates.
 */
public final class BotiEntities {
    private static final class Dummy {
        final Entity entity;
        double targetX;
        double targetY;
        double targetZ;
        float targetYRot;
        float targetXRot;
        float targetHead;
        float targetBody;
        int stepsLeft;

        Dummy(Entity entity) {
            this.entity = entity;
        }
    }

    private static final Map<PortalViewKey, Int2ObjectMap<Dummy>> VIEWS = new HashMap<>();
    private static int nextStandInId = -1_000_000;

    private BotiEntities() {}

    static void onPayload(BotiEntitiesPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || BotiClientCache.get(payload.key()) == null) {
            return;
        }
        Int2ObjectMap<Dummy> dummies = VIEWS.computeIfAbsent(payload.key(), k -> new Int2ObjectOpenHashMap<>());
        IntOpenHashSet present = new IntOpenHashSet();
        int interval = Math.max(1, net.zagdrath.artronindustries.Config.ENTITY_UPDATE_INTERVAL.getAsInt());
        for (BotiEntitiesPayload.Entry entry : payload.entities()) {
            present.add(entry.id());
            Dummy dummy = dummies.get(entry.id());
            if (dummy == null) {
                Entity entity = create(level, entry);
                if (entity == null) {
                    continue;
                }
                // Renderers read the id; negative ids never collide with entities the client really tracks.
                entity.setId(nextStandInId--);
                dummy = new Dummy(entity);
                entity.snapTo(entry.x(), entry.y(), entry.z(), entry.yRot(), entry.xRot());
                entity.setYHeadRot(entry.headYRot());
                entity.setYBodyRot(entry.bodyYRot());
                entity.setOldPosAndRot();
                dummies.put(entry.id(), dummy);
            }
            if (entry.data().isPresent()) {
                try {
                    dummy.entity.getEntityData().assignValues(entry.data().get());
                } catch (RuntimeException e) {
                    ArtronIndustries.LOGGER.debug("Ignoring BOTI entity data for {}", entry.type(), e);
                }
            }
            dummy.targetX = entry.x();
            dummy.targetY = entry.y();
            dummy.targetZ = entry.z();
            dummy.targetYRot = entry.yRot();
            dummy.targetXRot = entry.xRot();
            dummy.targetHead = entry.headYRot();
            dummy.targetBody = entry.bodyYRot();
            dummy.stepsLeft = interval;
        }
        dummies.int2ObjectEntrySet().removeIf(e -> !present.contains(e.getIntKey()));
        if (ArtronIndustries.LOGGER.isDebugEnabled() && payload.entities().size() != dummies.size()) {
            ArtronIndustries.LOGGER.debug("BOTI entities {}: {} received, {} stand-ins", payload.key(), payload.entities().size(), dummies.size());
        }
    }

    private static @Nullable Entity create(ClientLevel level, BotiEntitiesPayload.Entry entry) {
        if (entry.player().isPresent()) {
            BotiEntitiesPayload.PlayerProfile p = entry.player().get();
            return new RemotePlayer(level, new GameProfile(p.id(), p.name()));
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entry.type());
        try {
            return type == null ? null : type.create(level, EntitySpawnReason.LOAD);
        } catch (RuntimeException e) {
            ArtronIndustries.LOGGER.warn("Cannot create BOTI stand-in for {}", entry.type(), e);
            return null;
        }
    }

    /** Advances interpolation and walk animations (client tick). */
    static void tick() {
        for (Int2ObjectMap<Dummy> dummies : VIEWS.values()) {
            for (Dummy d : dummies.values()) {
                Entity e = d.entity;
                e.setOldPosAndRot();
                e.tickCount++;
                float headO = e.getYHeadRot();
                if (e instanceof LivingEntity living) {
                    living.yHeadRotO = living.yHeadRot;
                    living.yBodyRotO = living.yBodyRot;
                }
                if (d.stepsLeft > 0) {
                    float f = 1.0F / d.stepsLeft;
                    double nx = Mth.lerp(f, e.getX(), d.targetX);
                    double ny = Mth.lerp(f, e.getY(), d.targetY);
                    double nz = Mth.lerp(f, e.getZ(), d.targetZ);
                    double moved = Math.sqrt((nx - e.getX()) * (nx - e.getX()) + (nz - e.getZ()) * (nz - e.getZ()));
                    e.setPos(nx, ny, nz);
                    e.setYRot(Mth.rotLerp(f, e.getYRot(), d.targetYRot));
                    e.setXRot(Mth.lerp(f, e.getXRot(), d.targetXRot));
                    e.setYHeadRot(Mth.rotLerp(f, headO, d.targetHead));
                    if (e instanceof LivingEntity living) {
                        living.yBodyRot = Mth.rotLerp(f, living.yBodyRot, d.targetBody);
                        living.walkAnimation.update(Math.min((float) moved * 4.0F, 1.0F), 0.4F, living.isBaby() ? 3.0F : 1.0F);
                    }
                    d.stepsLeft--;
                } else if (e instanceof LivingEntity living) {
                    living.walkAnimation.update(0.0F, 0.4F, 1.0F);
                }
            }
        }
    }

    /** Stand-ins to draw for {@code key}, capped by {@code boti.maxEntities}. */
    static Collection<Entity> get(PortalViewKey key) {
        Int2ObjectMap<Dummy> dummies = VIEWS.get(key);
        if (dummies == null || !ArtronClientConfig.RENDER_ENTITIES.getAsBoolean()) {
            return java.util.List.of();
        }
        int max = ArtronClientConfig.MAX_ENTITIES.getAsInt();
        return dummies.values().stream().limit(max).map(d -> d.entity).toList();
    }

    static void release(PortalViewKey key) {
        VIEWS.remove(key);
    }

    static void clearAll() {
        VIEWS.clear();
    }
}
