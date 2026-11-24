/*
 * Decompiled with CFR 0.152.
 */
package com.abyssaldominion.siegewar.runtime.recovery;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PlayerSnapshot(
        UUID player,
        UUID session,
        UUID world,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        byte[] inventory,
        byte[] cursor,
        int heldSlot,
        String gameMode,
        int level,
        float exp,
        int totalExp,
        double health,
        double absorption,
        int food,
        float saturation,
        float exhaustion,
        boolean allowFlight,
        boolean flying,
        float flySpeed,
        float walkSpeed,
        int fireTicks,
        int freezeTicks,
        int remainingAir,
        float fallDistance,
        boolean invulnerable,
        boolean collidable,
        List<Effect> effects) {

    public PlayerSnapshot {
        Objects.requireNonNull(player);
        Objects.requireNonNull(session);
        Objects.requireNonNull(world);
        Objects.requireNonNull(worldName);
        Objects.requireNonNull(gameMode);
        inventory = (byte[]) Objects.requireNonNull(inventory).clone();
        cursor = (byte[]) Objects.requireNonNull(cursor).clone();
        effects = List.copyOf(effects);
    }

    public byte[] inventory() {
        return (byte[]) this.inventory.clone();
    }

    public byte[] cursor() {
        return (byte[]) this.cursor.clone();
    }

    public record Effect(
            String type,
            int ticks,
            int amplifier,
            boolean ambient,
            boolean particles,
            boolean icon) {}
}
