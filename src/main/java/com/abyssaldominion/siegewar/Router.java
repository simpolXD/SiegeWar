package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.runtime.ArenaRepository.*;
import com.abyssaldominion.siegewar.store.SiegeStore.CoinAction;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public final class Router implements CommandExecutor, TabCompleter {
    private final AbyssalDominionSiegeWarPlugin p;
    private final Map<String, Location[]> positions = new HashMap<>();
    private final Map<UUID, Long> lastCommand = new HashMap<>();

    public Router(AbyssalDominionSiegeWarPlugin p) {
        this.p = p;
    }

    public boolean onCommand(CommandSender s, Command cmd, String label, String[] raw) {
        try {
            if (s instanceof Player player
                    && (raw.length == 0
                            || !Set.of("leave", "help", "recover")
                                    .contains(raw[0].toLowerCase(Locale.ROOT)))) {
                long now = System.currentTimeMillis(),
                        before = lastCommand.getOrDefault(player.getUniqueId(), 0L);
                if (now - before < p.settings().general.getLong("command-interval-millis", 250)) {
                    p.say(s, "rate-limited");
                    return true;
                }
                lastCommand.put(player.getUniqueId(), now);
                if (lastCommand.size() > 1024)
                    lastCommand.entrySet().removeIf(e -> now - e.getValue() > 60000);
            }
            dispatch(s, raw);
        } catch (Exception ex) {
            p.error(s, ex);
        }
        return true;
    }

    private Player player(CommandSender s) {
        if (!(s instanceof Player player))
            throw new IllegalArgumentException("This command needs an in-game position");
        return player;
    }

    private static String arg(String[] args, int n, String usage) {
        if (args.length <= n) throw new IllegalArgumentException("Usage: /siegewar " + usage);
        return args[n].toLowerCase(Locale.ROOT);
    }

    private void dispatch(CommandSender s, String[] args) throws Exception {
        if (args.length == 0) {
            if (s instanceof Player who) {
                p.require(who, "use");
                p.views().main(who);
            } else help(s);
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            if (args.length == 1) {
                help(s);
                return;
            }
            admin(s, Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (Set.of("arena", "setlobby", "coins", "reload", "diagnostics", "validate")
                .contains(sub)) {
            admin(s, args);
            return;
        }
        switch (sub) {
            case "help" -> help(s);
            case "version" -> {
                p.say(s, "version", Map.of("version", p.getPluginMeta().getVersion()));
                p.tell(s, "<gray>Private H2 <h2>; HikariCP <hikari>.</gray>",
                        Map.of("h2", com.abyssaldominion.siegewar.store.SiegeStore.driverVersion(), "hikari", BuildInfo.hikari()));
            }
            case "menu" -> {
                p.require(s, "use");
                p.views().main(player(s));
            }
            case "join" -> {
                p.require(s, "join");
                if (args.length == 1) p.views().arenas(player(s));
                else p.game().join(player(s), args[1].toLowerCase(Locale.ROOT), false);
            }
            case "randomjoin" -> {
                p.require(s, "join");
                Player who = player(s);
                List<Game.Session> choices =
                        p.game().sessions().stream()
                                .filter(
                                        a ->
                                                !a.ending
                                                        && (a.engine.state()
                                                                        == com.abyssaldominion
                                                                                .siegewar.core
                                                                                .MatchEngine.State
                                                                                .WAITING
                                                                || a.engine.state()
                                                                        == com.abyssaldominion
                                                                                .siegewar.core
                                                                                .MatchEngine.State
                                                                                .COUNTDOWN))
                                .toList();
                if (choices.isEmpty())
                    throw new IllegalStateException("No eligible arenas are available");
                p.game()
                        .join(
                                who,
                                choices.get(
                                                java.util.concurrent.ThreadLocalRandom.current()
                                                        .nextInt(choices.size()))
                                        .id,
                                false);
            }
            case "leave" -> p.game().leave(player(s));
            case "recover" -> {
                Player who = player(s);
                if (p.game().session(who.getUniqueId()) != null) p.game().leave(who);
                else p.snapshots().restore(who);
            }
            case "spectate" -> {
                p.require(s, "spectate");
                p.game().join(player(s), arg(args, 1, "spectate <arena>"), true);
            }
            case "team" -> {
                if (args.length == 1) p.views().teams(player(s));
                else p.game().team(player(s), args[1]);
            }
            case "kits" -> p.views().kits(player(s));
            case "kit" -> p.game().select(player(s), arg(args, 1, "kit <kit-id>"));
            case "buykit" -> {
                p.require(s, "use");
                p.views().confirm(player(s), arg(args, 1, "buykit <kit-id>"));
            }
            case "ability" -> p.abilities().use(player(s), arg(args, 1, "ability <ability-id>"));
            case "stats" -> {
                p.require(s, "stats");
                UUID id = args.length > 1 ? p.resolve(args[1]) : player(s).getUniqueId();
                p.statsMessage(s, id);
            }
            case "top" -> {
                p.require(s, "stats");
                p.top(s, arg(args, 1, "top <wins|kills|deaths>"));
            }
            case "list", "arenas" -> {
                if (s instanceof Player who) p.views().arenas(who);
                else list(s);
            }
            default -> p.say(s, "unknown-command");
        }
    }

    private void help(CommandSender s) {
        p.tell(
                s,
                "<gold><bold>AbyssalDominionSiegeWar</bold></gold> <gray>"
                        + p.getPluginMeta().getVersion()
                        + "</gray>");
        for (String row :
                List.of(
                        "/siegewar — open menu",
                        "/siegewar join <arena> | randomjoin | leave",
                        "/siegewar team <attackers|defenders|auto>",
                        "/siegewar kits | kit <id> | buykit <id>",
                        "/siegewar ability <id> | spectate <arena>",
                        "/siegewar stats [name/UUID] | top <wins|kills|deaths>"))
            p.tell(s, "<yellow><usage></yellow>", Map.of("usage", row));
        if (s.hasPermission("abyssaldominionsiegewar.admin.setup"))
            for (String row :
                    List.of(
                            "/siegewar setlobby",
                            "/siegewar arena create <id>",
                            "/siegewar arena setspawn <id>"
                                    + " <waiting|attackers|defenders|king|spectator|exit>",
                            "/siegewar arena setbounds <id> [x1 y1 z1 x2 y2 z2]",
                            "/siegewar arena capture <id> — saves your FAWE selection",
                            "/siegewar arena validate <id> | enable <id>",
                            "/siegewar arena mode <id> <normal|hardcore>",
                            "/siegewar arena timer <id> <seconds>",
                            "/siegewar arena start|stop|reset|disable <id>",
                            "/siegewar coins <give|take|set> <name/UUID> <amount>",
                            "/siegewar diagnostics | reload"))
                p.tell(s, "<gray><usage></gray>", Map.of("usage", row));
    }

    private void admin(CommandSender s, String[] args) throws Exception {
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(s);
            case "setlobby" -> {
                p.require(s, "admin.setup");
                Location l;
                if (args.length >= 5) {
                    World w = Bukkit.getWorld(args[1]);
                    if (w == null) throw new IllegalArgumentException("Unknown world");
                    l =
                            new Location(
                                    w,
                                    Double.parseDouble(args[2]),
                                    Double.parseDouble(args[3]),
                                    Double.parseDouble(args[4]));
                } else l = player(s).getLocation();
                for (Arena a : p.arenas().all().values())
                    if (p.game().inside(a, l))
                        throw new IllegalArgumentException("Lobby must be outside reset bounds");
                p.arenas().setLobby(Game.point(l));
                p.tell(s, "<green>Main lobby saved.</green>");
            }
            case "arena" -> arena(s, args);
            case "validate" -> {
                p.require(s, "admin.diagnostics");
                validation(s, arg(args, 1, "validate <arena>"));
            }
            case "coins" -> {
                p.require(s, "admin.coins");
                String action = arg(args, 1, "coins <give|take|set> <player> <amount>");
                if (args.length < 4)
                    throw new IllegalArgumentException(
                            "Usage: /siegewar coins <give|take|set> <player> <amount>");
                UUID id = p.resolve(args[2]);
                long amount = Long.parseLong(args[3]);
                if (amount < 0 || amount > 1000000000L)
                    throw new IllegalArgumentException("Amount must be 0..1000000000");
                CoinAction ca = CoinAction.valueOf(action.toUpperCase(Locale.ROOT));
                UUID actor = s instanceof Player who ? who.getUniqueId() : new UUID(0, 0);
                p.io(() -> p.store().coins(UUID.randomUUID(), actor, id, ca, amount))
                        .whenComplete(
                                (v, e) ->
                                        p.sync(
                                                () -> {
                                                    if (e != null) p.error(s, e);
                                                    else {
                                                        p.refreshStats(id);
                                                        p.say(
                                                                s,
                                                                "coins-updated",
                                                                Map.of("coins", v.coins()));
                                                    }
                                                }));
            }
            case "reload" -> {
                p.require(s, "admin.reload");
                p.reloadAll();
                p.say(s, "reload-success");
            }
            case "diagnostics" -> {
                p.require(s, "admin.diagnostics");
                p.tell(
                        s,
                        "<gold>SiegeWar " + p.getPluginMeta().getVersion() + " diagnostics</gold>");
                for (String name :
                        List.of(
                                "FastAsyncWorldEdit",
                                "WorldGuard",
                                "Multiverse-Core",
                                "PlaceholderAPI",
                                "Geyser-Spigot",
                                "floodgate")) {
                    var hook = p.getServer().getPluginManager().getPlugin(name);
                    p.tell(
                            s,
                            "<gray><name>: <status></gray>",
                            Map.of(
                                    "name",
                                    name,
                                    "status",
                                    hook == null
                                            ? "not installed"
                                            : hook.getPluginMeta().getVersion()
                                                    + " ("
                                                    + hook.isEnabled()
                                                    + ")"));
                }
                p.tell(s, "<gray>Private H2 <h2>; HikariCP <hikari>; driver <driver></gray>",
                        Map.of("h2", com.abyssaldominion.siegewar.store.SiegeStore.driverVersion(), "hikari", BuildInfo.hikari(),
                                "driver", com.abyssaldominion.siegewar.store.SiegeStore.driverClass()));
                for (String key : List.of("team-capacity", "min-players", "max-team-difference", "round-seconds",
                        "respawn-seconds", "king-health", "king-type", "allow-block-edit", "regenerate-after-match",
                        "max-concurrent-resets", "max-projectiles-per-player", "max-entities-per-arena", "blacklisted-items",
                        "database.pool-size", "require-worldguard", "require-multiverse"))
                    p.tell(s, "<gray><key>: <value></gray>", Map.of("key", key, "value", Objects.toString(p.settings().general.get(key), "default")));
                list(s);
                p.tell(
                        s,
                        "<gray>Pending inventory operations: <count></gray>",
                        Map.of("count", p.snapshots().pendingCount()));
            }
            default ->
                    throw new IllegalArgumentException("Unknown admin command; use /siegewar help");
        }
    }

    private void list(CommandSender s) {
        if (p.arenas().all().isEmpty())
            p.tell(
                    s,
                    "<yellow>No arenas. Create one with <usage>.</yellow>",
                    Map.of("usage", "/siegewar arena create <id>"));
        for (Arena a : p.arenas().all().values()) {
            Game.Session r = p.game().arena(a.id());
            p.tell(
                    s,
                    "<gold><arena></gold> <gray><state> — <players> players</gray>",
                    Map.of(
                            "arena",
                            a.id(),
                            "state",
                            r == null ? "DISABLED" : r.engine.state(),
                            "players",
                            r == null ? 0 : r.players.size()));
        }
    }

    private void validation(CommandSender s, String id) {
        List<String> errors = p.game().validate(id);
        if (errors.isEmpty())
            p.tell(s, "<green>Arena <arena> is ready to enable.</green>", Map.of("arena", id));
        else
            for (String error : errors) p.tell(s, "<red>• <reason></red>", Map.of("reason", error));
    }

    private void arena(CommandSender s, String[] args) throws Exception {
        String action = arg(args, 1, "arena <action> <id>");
        if (action.equals("list")) {
            p.require(s, "admin.diagnostics");
            list(s);
            return;
        }
        String id = arg(args, 2, "arena " + action + " <id>");
        Settings.validId(id);
        if (Set.of("enable", "disable", "start", "stop", "reset").contains(action)) {
            p.require(s, "admin.manage");
            switch (action) {
                case "enable" -> p.game().enable(id, true);
                case "disable" -> p.game().enable(id, false);
                case "start" -> p.game().forceStart(id);
                case "stop" -> p.game().stop(id);
                case "reset" -> {
                    p.game().reset(id, s);
                    return;
                }
            }
            p.tell(
                    s,
                    "<green>Arena <arena>: <action>.</green>",
                    Map.of("arena", id, "action", action));
            return;
        }
        if (action.equals("validate")) {
            p.require(s, "admin.diagnostics");
            validation(s, id);
            return;
        }
        p.require(s, "admin.setup");
        if (action.equals("create")) {
            if (p.arenas().all().size() >= p.settings().integer("max-arenas", 1, 128, 16))
                throw new IllegalArgumentException("Arena limit reached");
            p.arenas().create(id);
            Arena a =
                    p.game()
                            .definition(id)
                            .rules(
                                    "NORMAL",
                                    p.settings().integer("countdown-seconds", 1, 600, 15),
                                    p.settings().integer("round-seconds", 1, 86400, 600));
            if (p.arenas().lobby().isPresent())
                a =
                        a.spawn("waiting", p.arenas().lobby().get())
                                .spawn("exit", p.arenas().lobby().get());
            p.arenas().save(a);
            p.tell(
                    s,
                    "<green>Created <arena>. Set team/King spawns and capture the FAWE"
                            + " selection.</green>",
                    Map.of("arena", id));
            return;
        }
        p.game().ensureEditable(id);
        Arena a = p.game().definition(id);
        switch (action) {
            case "setspawn" -> {
                String kind = arg(args, 3, "arena setspawn <id> <spawn-kind>");
                p.arenas().save(a.spawn(kind, Game.point(player(s).getLocation())));
            }
            case "setking", "setattackers", "setdefenders", "lobby", "spectator", "exit" -> {
                String kind =
                        switch (action) {
                            case "setking" -> "king";
                            case "setattackers" -> "attackers";
                            case "setdefenders" -> "defenders";
                            case "lobby" -> "waiting";
                            default -> action;
                        };
                p.arenas().save(a.spawn(kind, Game.point(player(s).getLocation())));
            }
            case "setregion" -> {
                String region = arg(args, 3, "arena setregion <id> <worldguard-region>");
                p.arenas()
                        .save(
                                a.regions(
                                        region,
                                        args.length > 4 ? args[4] : region,
                                        args.length > 5 ? args[5] : region));
            }
            case "setbounds" -> {
                Player who = player(s);
                Bounds b;
                if (args.length != 3 && args.length != 9)
                    throw new IllegalArgumentException("Usage: /siegewar arena setbounds <id> [x1 y1 z1 x2 y2 z2]");
                if (args.length == 9) {
                    int x = Integer.parseInt(args[3]),
                            y = Integer.parseInt(args[4]),
                            z = Integer.parseInt(args[5]),
                            u = Integer.parseInt(args[6]),
                            v = Integer.parseInt(args[7]),
                            w = Integer.parseInt(args[8]);
                    b =
                            new Bounds(
                                    who.getWorld().getUID(),
                                    who.getWorld().getName(),
                                    Math.min(x, u),
                                    Math.min(y, v),
                                    Math.min(z, w),
                                    Math.max(x, u),
                                    Math.max(y, v),
                                    Math.max(z, w));
                } else b = p.templates().selection(who);
                p.arenas().save(a.bounds(b));
            }
            case "pos1", "pos2" -> {
                String key =
                        (s instanceof Player who ? who.getUniqueId().toString() : "console")
                                + ":"
                                + id;
                Location[] pos = positions.computeIfAbsent(key, k -> new Location[2]);
                pos[action.equals("pos1") ? 0 : 1] = player(s).getLocation();
                if (pos[0] != null && pos[1] != null) {
                    if (!pos[0].getWorld().equals(pos[1].getWorld()))
                        throw new IllegalArgumentException(
                                "Both corners must be in the same world");
                    Location l = pos[0], r = pos[1];
                    p.arenas()
                            .save(
                                    a.bounds(
                                            new Bounds(
                                                    l.getWorld().getUID(),
                                                    l.getWorld().getName(),
                                                    Math.min(l.getBlockX(), r.getBlockX()),
                                                    Math.min(l.getBlockY(), r.getBlockY()),
                                                    Math.min(l.getBlockZ(), r.getBlockZ()),
                                                    Math.max(l.getBlockX(), r.getBlockX()),
                                                    Math.max(l.getBlockY(), r.getBlockY()),
                                                    Math.max(l.getBlockZ(), r.getBlockZ()))));
                }
            }
            case "capture" -> {
                if (a.bounds() == null) {
                    a = a.bounds(p.templates().selection(player(s)));
                    p.arenas().save(a);
                }
                if (!a.spawns().containsKey("spectator") && a.spawns().containsKey("defenders"))
                    a = a.spawn("spectator", a.spawns().get("defenders"));
                if (a.region().isBlank()
                        && p.getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
                    String region = "asw_" + id;
                    a = a.regions(region, region, region);
                    p.arenas().save(a);
                    p.protection().create(a);
                    p.tell(
                            s,
                            "<green>Created WorldGuard region <region> for this arena.</green>",
                            Map.of("region", region));
                } else p.arenas().save(a);
                p.game().capture(id, s);
                return;
            }
            case "mode" ->
                    p.arenas()
                            .save(
                                    a.rules(
                                            arg(args, 3, "arena mode <id> <normal|hardcore>")
                                                    .toUpperCase(Locale.ROOT),
                                            a.countdown(),
                                            a.round()));
            case "timer" ->
                    p.arenas()
                            .save(
                                    a.rules(
                                            a.mode(),
                                            a.countdown(),
                                            Integer.parseInt(
                                                    arg(args, 3, "arena timer <id> <seconds>"))));
            case "countdown" ->
                    p.arenas()
                            .save(
                                    a.rules(
                                            a.mode(),
                                            Integer.parseInt(
                                                    arg(args, 3, "arena countdown <id> <seconds>")),
                                            a.round()));
            case "edit" -> {
                p.views().editor(player(s), id);
                return;
            }
            case "finish" -> {
                p.game().enable(id, true);
                p.tell(s, "<green>Arena enabled.</green>");
                return;
            }
            default ->
                    throw new IllegalArgumentException("Unknown arena action; use /siegewar help");
        }
        p.game().refresh(id);
        p.say(s, "setup-success");
    }

    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        int offset = args.length > 0 && args[0].equalsIgnoreCase("admin") ? 1 : 0;
        int n = args.length - offset;
        if (n == 1) {
            choices.addAll(
                    List.of(
                            "help",
                            "menu",
                            "join",
                            "randomjoin",
                            "leave",
                            "team",
                            "kits",
                            "kit",
                            "buykit",
                            "ability",
                            "spectate",
                            "stats",
                            "top",
                            "list",
                            "version"));
            if (s.hasPermission("abyssaldominionsiegewar.admin.setup"))
                choices.addAll(
                        List.of("admin", "arena", "setlobby", "coins", "reload", "diagnostics"));
        } else if (n >= 2) {
            String root = args[offset].toLowerCase(Locale.ROOT);
            if (root.equals("arena")) {
                if (n == 2)
                    choices.addAll(
                            List.of(
                                    "create",
                                    "setspawn",
                                    "setking",
                                    "setattackers",
                                    "setdefenders",
                                    "setbounds",
                                    "setregion",
                                    "capture",
                                    "edit",
                                    "validate",
                                    "enable",
                                    "disable",
                                    "start",
                                    "stop",
                                    "reset",
                                    "mode",
                                    "timer",
                                    "countdown",
                                    "pos1",
                                    "pos2"));
                else if (n == 3) choices.addAll(p.arenas().all().keySet());
                else if (n == 4 && args[offset + 1].equalsIgnoreCase("setspawn"))
                    choices.addAll(
                            List.of(
                                    "waiting",
                                    "attackers",
                                    "defenders",
                                    "king",
                                    "spectator",
                                    "exit"));
                else if (n == 4 && args[offset + 1].equalsIgnoreCase("mode"))
                    choices.addAll(List.of("normal", "hardcore"));
            } else if (n == 2)
                switch (root) {
                    case "join", "spectate" -> choices.addAll(p.arenas().all().keySet());
                    case "team" -> choices.addAll(List.of("attackers", "defenders", "auto"));
                    case "kit", "buykit" -> choices.addAll(p.settings().kits.keySet());
                    case "ability" -> choices.addAll(p.settings().abilities.keySet());
                    case "top" -> choices.addAll(List.of("wins", "kills", "deaths"));
                    case "coins" -> choices.addAll(List.of("give", "take", "set"));
                }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(a -> a.startsWith(prefix)).distinct().sorted().toList();
    }
}
