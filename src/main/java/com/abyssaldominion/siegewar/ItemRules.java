package com.abyssaldominion.siegewar;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Disables restricted items in place; survival belongings are never confiscated. */
public final class ItemRules {
    private final AbyssalDominionSiegeWarPlugin plugin;
    private final Map<UUID, Map<Material, Long>> warned = new HashMap<>();
    public ItemRules(AbyssalDominionSiegeWarPlugin plugin) { this.plugin = plugin; }
    public boolean blacklisted(Material material) { return plugin.settings().blacklistedItems.contains(material); }
    public boolean blocked(Player player, ItemStack item) {
        if (item == null || !blacklisted(item.getType())) return false;
        warn(player, item.getType(), false);
        return true;
    }
    public void warnInventory(Player player) {
        Set<Material> seen = new HashSet<>();
        for (ItemStack item : player.getInventory().getContents())
            if (item != null && blacklisted(item.getType()) && seen.add(item.getType()))
                warn(player, item.getType(), true);
        if (player.isGliding() && blacklisted(Material.ELYTRA)) player.setGliding(false);
    }
    public void warn(Player player, Material material, boolean force) {
        long now = System.currentTimeMillis();
        Map<Material, Long> times = warned.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        if (force || now - times.getOrDefault(material, 0L) >= 2000) {
            times.put(material, now);
            plugin.say(player, "blacklisted-item", Map.of("item", material.name()));
        }
    }
    public void forget(UUID player) { warned.remove(player); }
}
