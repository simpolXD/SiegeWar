package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.runtime.ArenaRepository;
import com.abyssaldominion.siegewar.store.SiegeStore;
import com.abyssaldominion.siegewar.store.SiegeStore.Stats;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public final class AbyssalDominionSiegeWarPlugin extends JavaPlugin {
    private volatile Settings settings;
    private SiegeStore store;
    private ArenaRepository arenas;
    private Templates templates;
    private Game game;
    private Snapshots snapshots;
    private Views views;
    private Abilities abilities;
    private ItemRules items;
    private Protection protection = Protection.absent();
    private SiegeExpansion expansion;
    private final ExecutorService database =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(256),
                    Thread.ofPlatform().name("siege-database-").factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID, Stats> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> ownership = new ConcurrentHashMap<>();
    private volatile boolean closing;

    @Override
    public void onEnable() {
        try {
            Protection availableProtection = Protection.absent();
            if (getServer().getPluginManager().isPluginEnabled("WorldGuard"))
                availableProtection = new WorldGuardProtection(getDataFolder().toPath());
            // Undo interrupted match flags even if later configuration/database validation fails.
            availableProtection.recover();
            DefaultUpgrade.apply(this);
            settings = new Settings(this);
            if (!BuildInfo.version().equals(getPluginMeta().getVersion()))
                throw new IllegalStateException("Build metadata version mismatch");
            if (settings.flag("integrations.worldguard", true)) protection = availableProtection;
            store =
                    new SiegeStore(
                            settings.dataPath(this, "database.path", "database/siege"),
                            settings.integer("database.pool-size", 1, 16, 4),
                            settings.dataPath(this, "database.backup-directory", "backup"));
            if (!BuildInfo.h2().equals(SiegeStore.driverVersion()))
                throw new IllegalStateException("Unexpected private H2 version");
            arenas = new ArenaRepository(getDataFolder().toPath().resolve("arenas.yml"));
            templates = new Templates(this);
            snapshots = new Snapshots(this);
            game = new Game(this);
            views = new Views(this);
            abilities = new Abilities(this);
            items = new ItemRules(this);
            Router router = new Router(this);
            Objects.requireNonNull(getCommand("siegewar"), "siegewar missing in plugin.yml")
                    .setExecutor(router);
            getCommand("siegewar").setTabCompleter(router);
            getServer().getPluginManager().registerEvents(new Events(this), this);
            getServer().getPluginManager().registerEvents(new ArenaGuards(this), this);
            game.load();
            getServer().getScheduler().runTaskTimer(this, game::tick, 20, 20);
            if (settings.flag("integrations.placeholderapi", true)
                    && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try {
                    expansion = new SiegeExpansion(this);
                    if (!expansion.register())
                        getLogger().warning("PlaceholderAPI identifier was already registered");
                } catch (LinkageError | RuntimeException ex) {
                    expansion = null;
                    getLogger()
                            .log(
                                    Level.WARNING,
                                    "PlaceholderAPI hook unavailable; core gameplay remains"
                                        + " available",
                                    ex);
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                ensure(player);
                snapshots.restore(player);
            }
            getLogger()
                    .info(
                            "AbyssalDominionSiegeWar "
                                    + getPluginMeta().getVersion()
                                    + " ready; "
                                    + game.sessions().size()
                                    + " playable arenas. /siegewar help");
        } catch (Exception | LinkageError ex) {
            getLogger().log(Level.SEVERE, "Startup failed; plugin has been disabled", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        closing = true;
        if (expansion != null) expansion.unregister();
        if (game != null) game.shutdown();
        database.shutdown();
        try {
            database.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (snapshots != null && store != null)
            for (Player player : Bukkit.getOnlinePlayers())
                try {
                    snapshots.restoreNow(player);
                } catch (Exception ex) {
                    getLogger()
                            .log(
                                    Level.SEVERE,
                                    "Recovery retained for next login: " + player.getUniqueId(),
                                    ex);
                }
        if (templates != null) templates.close();
        if (store != null) { store.close(); SiegeStore.unloadDriver(); }
    }

    public Settings settings() {
        return settings;
    }

    public SiegeStore store() {
        return store;
    }

    public ArenaRepository arenas() {
        return arenas;
    }

    public Templates templates() {
        return templates;
    }

    public Game game() {
        return game;
    }

    public Snapshots snapshots() {
        return snapshots;
    }

    public Views views() {
        return views;
    }

    public Abilities abilities() {
        return abilities;
    }

    public ItemRules items() { return items; }

    public Protection protection() {
        return protection;
    }

    public <T> CompletableFuture<T> io(Callable<T> task) {
        if (database.isShutdown())
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Plugin is shutting down"));
        try {
            return CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return task.call();
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    },
                    database);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Database queue is busy; try again shortly", e));
        }
    }

    public void sync(Runnable task) {
        if (closing) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else
            getServer()
                    .getScheduler()
                    .runTask(
                            this,
                            () -> {
                                if (!closing) task.run();
                            });
    }

    public void ensure(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        io(() -> {
                    cache.put(id, store.ensurePlayer(id, name));
                    ownership.put(id, store.owned(id));
                    return null;
                })
                .exceptionally(
                        e -> {
                            getLogger().log(Level.SEVERE, "Could not load player profile " + id, e);
                            return null;
                        });
    }

    public void refreshStats(UUID id) {
        if (closing) return;
        io(() -> {
                    store.stats(id).ifPresent(s -> cache.put(id, s));
                    ownership.put(id, store.owned(id));
                    return null;
                })
                .exceptionally(
                        e -> {
                            getLogger().log(Level.WARNING, "Could not refresh statistics", e);
                            return null;
                        });
    }

    public Stats stats(UUID id) {
        return cache.get(id);
    }

    public Set<String> owned(UUID id) {
        return ownership.getOrDefault(id, Set.of());
    }

    public void updateCachedOnIo(UUID id) {
        store.stats(id).ifPresent(s -> cache.put(id, s));
        ownership.put(id, store.owned(id));
    }

    public UUID resolve(String input) {
        try {
            return UUID.fromString(input);
        } catch (IllegalArgumentException ignored) {
        }
        Player player = Bukkit.getPlayerExact(input);
        if (player != null) return player.getUniqueId();
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(input);
        if (offline != null) return offline.getUniqueId();
        return cache.values().stream()
                .filter(s -> s.name().equalsIgnoreCase(input))
                .map(Stats::player)
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "Unknown player; use an online/cached name or UUID"));
    }

    public void statsMessage(CommandSender sender, UUID id) {
        io(() -> store.stats(id))
                .whenComplete(
                        (s, e) ->
                                sync(
                                        () -> {
                                            if (e != null) {
                                                error(sender, e);
                                                return;
                                            }
                                            if (s.isEmpty()) {
                                                tell(
                                                        sender,
                                                        "<yellow>No siege statistics found for that"
                                                            + " player.</yellow>");
                                                return;
                                            }
                                            Stats v = s.get();
                                            cache.put(id, v);
                                            say(
                                                    sender,
                                                    "stats",
                                                    Map.of(
                                                            "player",
                                                            v.name(),
                                                            "kills",
                                                            v.kills(),
                                                            "deaths",
                                                            v.deaths(),
                                                            "wins",
                                                            v.wins(),
                                                            "coins",
                                                            v.coins()));
                                        }));
    }

    public void top(CommandSender sender, String metric) {
        if (!Set.of("wins", "kills", "deaths").contains(metric))
            throw new IllegalArgumentException("Choose wins, kills, or deaths");
        io(() -> store.top(metric, 10))
                .whenComplete(
                        (rows, e) ->
                                sync(
                                        () -> {
                                            if (e != null) {
                                                error(sender, e);
                                                return;
                                            }
                                            tell(
                                                    sender,
                                                    "<gold>Top <metric></gold>",
                                                    Map.of("metric", metric));
                                            int i = 1;
                                            for (Stats s : rows) {
                                                long value =
                                                        switch (metric) {
                                                            case "wins" -> s.wins();
                                                            case "kills" -> s.kills();
                                                            default -> s.deaths();
                                                        };
                                                tell(
                                                        sender,
                                                        "<yellow><rank>.</yellow>"
                                                            + " <white><player></white> <gray>—"
                                                            + " <value></gray>",
                                                        Map.of(
                                                                "rank", i++, "player", s.name(),
                                                                "value", value));
                                            }
                                            if (rows.isEmpty())
                                                tell(
                                                        sender,
                                                        "<gray>No recorded results yet.</gray>");
                                        }));
    }

    public void require(CommandSender sender, String node) {
        if (!sender.hasPermission("abyssaldominionsiegewar." + node))
            throw new SecurityException("Permission required: abyssaldominionsiegewar." + node);
    }

    public void say(CommandSender sender, String key) {
        say(sender, key, Map.of());
    }

    public void say(CommandSender sender, String key, Map<String, ?> args) {
        String template = settings.messages.getString(key);
        if (template == null) template = "<gray>" + key.replace('-', ' ') + "</gray>";
        sender.sendMessage(
                Text.of(settings.messages.getString("prefix", "<gold>[Siege]</gold> "))
                        .append(Text.of(template, args)));
    }

    public void tell(CommandSender sender, String template) {
        tell(sender, template, Map.of());
    }

    public void tell(CommandSender sender, String template, Map<String, ?> args) {
        sender.sendMessage(Text.of(template, args));
    }

    public void error(CommandSender sender, Throwable ex) {
        while ((ex instanceof CompletionException || ex instanceof ExecutionException)
                && ex.getCause() != null) ex = ex.getCause();
        if (ex instanceof IllegalArgumentException
                || ex instanceof IllegalStateException
                || ex instanceof SecurityException) {
            say(
                    sender,
                    "error",
                    Map.of("reason", Objects.toString(ex.getMessage(), "Action unavailable")));
        } else {
            getLogger().log(Level.SEVERE, "Siege action failed", ex);
            say(
                    sender,
                    "error",
                    Map.of("reason", "An internal error occurred; see the server log"));
        }
    }

    public void reloadAll() throws Exception {
        if (game.anyBusy() || snapshots.hasPending())
            throw new IllegalStateException(
                    "Wait until matches, recovery, and template jobs finish");
        Settings candidate = new Settings(this);
        if (candidate.integer("max-concurrent-resets", 1, 16, 1) != settings.integer("max-concurrent-resets", 1, 16, 1))
            throw new IllegalStateException("Reset worker limit changes require a server restart");
        for (String key : List.of("database", "integrations"))
            if (!Objects.equals(settings.general.getConfigurationSection(key).getValues(true),
                    candidate.general.getConfigurationSection(key).getValues(true)))
                throw new IllegalStateException("Database/integration changes require a server restart");
        ArenaRepository candidateArenas =
                new ArenaRepository(getDataFolder().toPath().resolve("arenas.yml"));
        game.shutdown();
        settings = candidate;
        arenas = candidateArenas;
        game.load();
    }
}
