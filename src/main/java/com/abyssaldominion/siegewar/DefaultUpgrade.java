package com.abyssaldominion.siegewar;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Copy legacy definitions to their sole active home without replacing edited settings. */
public final class DefaultUpgrade {
    public static void apply(JavaPlugin plugin) throws Exception {
        Path root = plugin.getDataFolder().toPath(), config = root.resolve("config.yml");
        if (!Files.isRegularFile(config)) return;
        YamlConfiguration current = Settings.read(plugin, "config.yml");
        YamlConfiguration defaults;
        try (Reader reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource("config.yml")), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        Map<String, String> original100 = Map.of(
                "kits", "2a2ab8f5b0333f0e1400565ae198c902a8e7d5361dc96b2de8fe42422e1bdb7c",
                "abilities", "9a8e988a0d7f577365f7751e062cd067f082f791c88ac641a1a04b84b488e5dd");
        boolean changed = false;
        Path backup = root.resolve("backup/pre-1.0.3");
        for (String key : defaults.getKeys(false)) {
            if (current.contains(key)) continue;
            Object value = defaults.get(key);
            Path legacy = root.resolve(key + ".yml");
            if (original100.containsKey(key) && Files.isRegularFile(legacy)) {
                Files.createDirectories(backup);
                if (!Files.exists(backup.resolve(key + ".yml"))) Files.copy(legacy, backup.resolve(key + ".yml"));
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(legacy)));
                if (!hash.equals(original100.get(key))) {
                    YamlConfiguration old = Settings.read(plugin, key + ".yml");
                    if (!old.isConfigurationSection(key)) throw new IllegalArgumentException("Missing legacy " + key + " mapping");
                    value = old.get(key);
                }
            }
            current.set(key, value);
            changed = true;
        }
        if (!changed) return;
        Files.createDirectories(backup);
        if (!Files.exists(backup.resolve("config.yml"))) Files.copy(config, backup.resolve("config.yml"));
        Path pending = config.resolveSibling("config.yml.pending");
        try {
            current.save(pending.toFile());
            try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(pending, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
        plugin.getLogger().info("Configuration migrated to 1.0.3. Kit/ability definitions now live only in config.yml; legacy files are retained as reference.");
    }
    private DefaultUpgrade() {}
}
