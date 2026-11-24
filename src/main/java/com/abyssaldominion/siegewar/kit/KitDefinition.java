/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.bukkit.Material
 */
package com.abyssaldominion.siegewar.kit;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

public record KitDefinition(
        String id,
        String displayName,
        String team,
        long price,
        boolean defaultKit,
        Material icon,
        List<Gear> items,
        List<Gear> armor,
        List<Effect> killEffects) {
    public KitDefinition {
        items = List.copyOf(items);
        armor = List.copyOf(armor);
        killEffects = List.copyOf(killEffects);
    }

    public record Effect(String type, int ticks, int amplifier) {}

    public record Gear(
            Material material,
            int amount,
            String ability,
            int slot,
            Map<String, Integer> enchantments) {
        public Gear {
            enchantments = Map.copyOf(enchantments);
        }
    }
}
