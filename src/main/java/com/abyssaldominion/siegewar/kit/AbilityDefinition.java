/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.bukkit.Material
 */
package com.abyssaldominion.siegewar.kit;

import org.bukkit.Material;

import java.util.List;

public record AbilityDefinition(
        String id,
        String kind,
        Material material,
        int cooldownSeconds,
        int charges,
        double damage,
        double radius,
        int maxTargets,
        int durationTicks,
        int amplifier,
        int fuseTicks,
        boolean terrainDamage,
        List<KitDefinition.Effect> effects) {
    public AbilityDefinition {
        effects = List.copyOf(effects);
    }
}
