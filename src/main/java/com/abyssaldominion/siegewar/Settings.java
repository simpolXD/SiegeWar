package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.kit.*;

import org.bukkit.*;
import org.bukkit.configuration.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Settings {
    public final YamlConfiguration general, messages, menus, scoreboard;
    public final Map<String, KitDefinition> kits = new LinkedHashMap<>();
    public final Map<String, AbilityDefinition> abilities = new LinkedHashMap<>();
    public final Set<Material> blacklistedItems;
    public final Set<org.bukkit.potion.PotionEffectType> blacklistedEffects;

    public Settings(JavaPlugin plugin) throws Exception {
        for (String f :
                List.of(
                        "config.yml",
                        "messages.yml",
                        "menus.yml",
                        "scoreboards.yml",
                        "arenas.yml"))
            if (!new File(plugin.getDataFolder(), f).exists()) plugin.saveResource(f, false);
        general = read(plugin, "config.yml");
        messages = read(plugin, "messages.yml");
        menus = read(plugin, "menus.yml");
        scoreboard = read(plugin, "scoreboards.yml");
        integer("team-capacity", 1, 128, 8);
        integer("min-players", 2, 256, 2);
        if (integer("min-players", 2, 256, 2) > integer("team-capacity", 1, 128, 8) * 2)
            throw new IllegalArgumentException("min-players exceeds team capacity");
        integer("round-seconds", 1, 86400, 600);
        integer("countdown-seconds", 1, 600, 15);
        integer("respawn-seconds", 1, 3600, 5);
        integer("max-team-difference", 1, 128, 1);
        integer("spectator-limit", 0, 128, 16);
        integer("max-arenas", 1, 128, 16);
        integer("command-interval-millis", 0, 10000, 250);
        integer("max-concurrent-resets", 1, 16, 1);
        integer("max-projectiles-per-player", 1, 128, 32);
        integer("max-entities-per-arena", 1, 2048, 512);
        integer("database.pool-size", 1, 16, 4);
        boundedLong("kill-reward-cooldown-seconds", 0, 86400, 30);
        for (String key : List.of("allow-block-edit", "regenerate-after-match", "scoreboard-enabled",
                "require-worldguard", "require-multiverse", "integrations.worldguard", "integrations.placeholderapi",
                "integrations.require-geyser", "integrations.require-floodgate")) flag(key, true);
        Set<Material> disabled = new LinkedHashSet<>();
        for (String name : stringList("blacklisted-items")) disabled.add(material(name));
        blacklistedItems = Set.copyOf(disabled);
        Set<org.bukkit.potion.PotionEffectType> effects = new HashSet<>();
        for (String name : stringList("blacklisted-effects")) {
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
            var effect = key == null ? null : Registry.MOB_EFFECT.get(key);
            if (effect == null) throw new IllegalArgumentException("Unknown blacklisted effect: " + name);
            effects.add(effect);
        }
        blacklistedEffects = Set.copyOf(effects);
        for (String command : stringList("allowed-commands"))
            if (!command.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("allowed-commands must contain lowercase command labels");
        dataPath(plugin, "database.path", "database/siege");
        dataPath(plugin, "database.backup-directory", "backup");
        boundedLong("kill-coins", 0, 1000000000L, 1);
        boundedLong("win-coins", 0, 1000000000L, 3);
        boundedLong("reward-minimum-seconds", 0, 86400, 10);
        boundedLong("max-arena-blocks", 1, 20000000L, 2000000L);
        boundedLong("max-schematic-bytes", 1, 268435456L, 67108864L);
        finite(numeric(general, "king-health", 30), 1, 2048, "king-health");
        String king = general.getString("king-type", "ZOMBIE").toUpperCase(Locale.ROOT);
        org.bukkit.entity.EntityType type;
        try { type = org.bukkit.entity.EntityType.valueOf(king); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Unknown king-type: " + king); }
        if (!type.isSpawnable() || type.getEntityClass() == null
                || !org.bukkit.entity.LivingEntity.class.isAssignableFrom(type.getEntityClass()))
            throw new IllegalArgumentException("king-type must be a spawnable living entity");
        general.set("king-type", king);
        YamlConfiguration a = general;
        for (String id : section(a, "abilities").getKeys(false)) {
            validId(id);
            ConfigurationSection n = a.getConfigurationSection("abilities." + id);
            String kind = n.getString("kind", "BUFF").toUpperCase(Locale.ROOT);
            if (!Set.of("BUFF", "ATTACK", "HEAL", "AXE", "TNT", "STEW").contains(kind))
                throw new IllegalArgumentException("Unknown ability kind: " + id);
            int cd = range(whole(n, "cooldown-seconds", 1), 0, 3600, id + " cooldown"),
                    charges = range(whole(n, "charges", -1), -1, 64, id + " charges");
            if (charges == 0 || (charges == -1 && cd == 0))
                throw new IllegalArgumentException("Unbounded ability: " + id);
            abilities.put(
                    id,
                    new AbilityDefinition(
                            id,
                            kind,
                            material(n.getString("material")),
                            cd,
                            charges,
                            finite(numeric(n, "damage", 0), 0, 100, id),
                            finite(numeric(n, "radius", 0), 0, 32, id),
                            range(whole(n, "max-targets", 1), 1, 32, id),
                            range(whole(n, "duration-ticks", 100), 0, 1200, id),
                            range(whole(n, "amplifier", 0), 0, 2, id),
                            range(whole(n, "fuse-ticks", 40), 0, 200, id),
                            bool(n, "terrain-damage", false),
                            effects(n.getMapList("effects"))));
        }
        YamlConfiguration k = general;
        for (String id : section(k, "kits").getKeys(false)) {
            validId(id);
            ConfigurationSection n = k.getConfigurationSection("kits." + id);
            String team = n.getString("team", "").toUpperCase(Locale.ROOT);
            if (!Set.of("ATTACKERS", "DEFENDERS").contains(team))
                throw new IllegalArgumentException("Invalid kit team: " + id);
            long price = whole(n, "price", 0);
            if (price < 0 || price > 1000000000L)
                throw new IllegalArgumentException("Invalid kit price: " + id);
            kits.put(
                    id,
                    new KitDefinition(
                            id,
                            n.getString("display-name", id),
                            team,
                            price,
                            bool(n, "default-kit", false),
                            material(n.getString("icon")),
                            gear(n.getMapList("items")),
                            gear(n.getMapList("armor")),
                            effects(n.getMapList("kill-effects"))));
        }
        for (String team : List.of("ATTACKERS", "DEFENDERS")) {
            if (kits.values().stream().filter(kd -> kd.team().equals(team)).count() > 18)
                throw new IllegalArgumentException("At most 18 kits per team fit the kit menu");
            if (kits.values().stream()
                    .noneMatch(kd -> kd.team().equals(team) && kd.defaultKit() && kd.price() == 0))
                throw new IllegalArgumentException("Missing free default kit for " + team);
        }
    }

    static YamlConfiguration read(JavaPlugin plugin, String f) throws Exception {
        var options = new org.yaml.snakeyaml.LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        try (Reader input = Files.newBufferedReader(plugin.getDataFolder().toPath().resolve(f))) {
            new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(input);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.load(new File(plugin.getDataFolder(), f));
        return y;
    }

    private static boolean bool(ConfigurationSection section, String key, boolean fallback) {
        Object value = section.get(key, fallback);
        if (!(value instanceof Boolean flag)) throw new IllegalArgumentException(key + " must be true or false");
        return flag;
    }

    private static int mapInt(Map<?, ?> map, String key, int fallback, int min, int max) {
        Object value = map.containsKey(key) ? map.get(key) : fallback;
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max)
            throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max);
        return n.intValue();
    }

    private static double numeric(ConfigurationSection section, String key, double fallback) {
        Object value = section.get(key, fallback);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()))
            throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " must be a finite number");
        return n.doubleValue();
    }

    private static int whole(ConfigurationSection section, String key, int fallback) {
        double value = numeric(section, key, fallback);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(key + " must be an integer");
        return (int) value;
    }

    private static ConfigurationSection section(YamlConfiguration y, String k) {
        ConfigurationSection s = y.getConfigurationSection(k);
        if (s == null) throw new IllegalArgumentException("Missing " + k + " mapping");
        return s;
    }

    public int integer(String key, int min, int max, int fallback) {
        integral(key);
        return range(general.getInt(key, fallback), min, max, key);
    }

    private long boundedLong(String key, long min, long max, long fallback) {
        integral(key);
        long value = general.getLong(key, fallback);
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
        return value;
    }

    public boolean flag(String key, boolean fallback) {
        if (general.contains(key) && !(general.get(key) instanceof Boolean))
            throw new IllegalArgumentException(key + " must be true or false");
        return general.getBoolean(key, fallback);
    }

    private void integral(String key) {
        if (!general.contains(key)) return;
        Object value = general.get(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.longValue())
            throw new IllegalArgumentException(key + " must be an integer");
    }

    private List<String> stringList(String key) {
        if (!general.contains(key)) return List.of();
        if (!(general.get(key) instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalArgumentException(key + " must be a YAML list of names");
        return general.getStringList(key);
    }

    public Path dataPath(JavaPlugin plugin, String key, String fallback) {
        Object configured = general.get(key, fallback);
        if (!(configured instanceof String value) || !value.matches("[A-Za-z0-9_/-]{1,160}"))
            throw new IllegalArgumentException(key + " must be a relative local path");
        Path relative = Path.of(value);
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path path = root.resolve(relative).normalize();
        if (relative.isAbsolute() || path.equals(root) || !path.startsWith(root))
            throw new IllegalArgumentException(key + " escapes the plugin data folder");
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IllegalArgumentException(key + " contains a symbolic link");
        return path;
    }

    public static int range(int v, int min, int max, String id) {
        if (v < min || v > max)
            throw new IllegalArgumentException(id + " must be " + min + ".." + max);
        return v;
    }

    public static double finite(double v, double min, double max, String id) {
        if (!Double.isFinite(v) || v < min || v > max)
            throw new IllegalArgumentException("Invalid numeric value: " + id);
        return v;
    }

    public static void validId(String id) {
        if (id == null || !id.matches("[a-z0-9_-]{1,32}"))
            throw new IllegalArgumentException(
                    "ID must contain 1–32 lowercase letters, digits, _ or -");
    }

    static Material material(String name) {
        Material m = name == null ? null : Material.matchMaterial(name);
        if (m == null || !m.isItem())
            throw new IllegalArgumentException("Unknown item material: " + name);
        return m;
    }

    private List<KitDefinition.Gear> gear(List<Map<?, ?>> entries) {
        List<KitDefinition.Gear> out = new ArrayList<>();
        for (Map<?, ?> n : entries) {
            String ability = Objects.toString(n.get("ability"), "");
            if (!ability.isEmpty() && !abilities.containsKey(ability))
                throw new IllegalArgumentException("Unknown kit ability " + ability);
            Map<String, Integer> ench = new LinkedHashMap<>();
            if (n.get("enchantments") instanceof Map<?, ?> map)
                map.forEach(
                        (a, b) ->
                                ench.put(
                                        a.toString(),
                                        mapInt(Map.of("level", b), "level", 1, 1, 10)));
            int amount = mapInt(n, "amount", 1, 1, 64), slot = mapInt(n, "slot", -1, -1, 35);
            out.add(
                    new KitDefinition.Gear(
                            material(Objects.toString(n.get("material"))),
                            range(amount, 1, 64, "amount"),
                            ability,
                            range(slot, -1, 35, "slot"),
                            ench));
        }
        return List.copyOf(out);
    }

    private static List<KitDefinition.Effect> effects(List<Map<?, ?>> entries) {
        if (entries.size() > 8)
            throw new IllegalArgumentException("At most eight effects are permitted");
        List<KitDefinition.Effect> out = new ArrayList<>();
        for (Map<?, ?> n : entries) {
            String id = n.get("type").toString().toLowerCase(Locale.ROOT);
            if (Registry.MOB_EFFECT.get(NamespacedKey.minecraft(id)) == null)
                throw new IllegalArgumentException("Unknown potion effect " + id);
            out.add(
                    new KitDefinition.Effect(
                            id,
                            mapInt(n, "ticks", 1, 1, 1200),
                            mapInt(n, "amplifier", 0, 0, 2)));
        }
        return List.copyOf(out);
    }
}
