package com.abyssaldominion.siegewar;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import org.bukkit.OfflinePlayer;

import java.util.*;

public final class SiegeExpansion extends PlaceholderExpansion {
    private final AbyssalDominionSiegeWarPlugin p;

    public SiegeExpansion(AbyssalDominionSiegeWarPlugin p) {
        this.p = p;
    }

    public String getIdentifier() {
        return "abyssaldominionsiegewar";
    }

    public String getAuthor() {
        return "simp0ol";
    }

    public String getVersion() {
        return p.getPluginMeta().getVersion();
    }

    public boolean persist() {
        return true;
    }

    public String onRequest(OfflinePlayer player, String key) {
        if (player == null) return "";
        var s = p.stats(player.getUniqueId());
        return switch (key) {
            case "coins" -> s == null ? "0" : Long.toString(s.coins());
            case "kills" -> s == null ? "0" : Long.toString(s.kills());
            case "deaths" -> s == null ? "0" : Long.toString(s.deaths());
            case "wins" -> s == null ? "0" : Long.toString(s.wins());
            case "kdr" ->
                    s == null
                            ? "0.00"
                            : String.format(
                                    Locale.ROOT,
                                    "%.2f",
                                    s.kills() / (double) Math.max(1, s.deaths()));
            default -> live(player, key);
        };
    }

    private String live(OfflinePlayer offline, String key) {
        if (!Set.of(
                        "arena",
                        "state",
                        "mode",
                        "team",
                        "kit",
                        "players",
                        "max_players",
                        "attackers",
                        "defenders",
                        "time_remaining",
                        "king_health",
                        "king_max_health")
                .contains(key)) return null;
        Map<String, Object> values = p.game().cachedPlaceholders(offline.getUniqueId());
        return Objects.toString(
                values.get(key),
                switch (key) {
                    case "state" -> "LOBBY";
                    case "arena", "mode", "team", "kit" -> "None";
                    default -> "0";
                });
    }
}
