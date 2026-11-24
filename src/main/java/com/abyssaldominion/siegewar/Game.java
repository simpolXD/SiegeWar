package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.core.MatchEngine;
import com.abyssaldominion.siegewar.core.IssuedRegistry;
import com.abyssaldominion.siegewar.core.MatchEngine.*;
import com.abyssaldominion.siegewar.kit.*;
import com.abyssaldominion.siegewar.runtime.ArenaRepository.*;
import com.abyssaldominion.siegewar.store.SiegeStore.*;

import net.kyori.adventure.bossbar.BossBar;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;
import java.util.concurrent.*;

public final class Game {
    public static final class Session {
        public final String id;
        public final UUID generation = UUID.randomUUID();
        public final MatchEngine engine;
        public final Map<UUID, String> selected = new HashMap<>();
        public final Set<UUID> players = new LinkedHashSet<>(),
                spectators = new LinkedHashSet<>(),
                dead = new HashSet<>();
        public final Map<UUID, Scoreboard> previousBoards = new HashMap<>(),
                boards = new HashMap<>();
        public final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
        public final Map<UUID, Map<String, Integer>> charges = new HashMap<>();
        public final Map<UUID, UUID> lives = new HashMap<>();
        public final Map<String, Long> farm = new HashMap<>();
        public final IssuedRegistry issued = new IssuedRegistry();
        public final Map<UUID, Entity> entities = new HashMap<>();
        public final Set<UUID> noSpaceWarned = new HashSet<>();
        public LivingEntity king;
        public Chunk kingChunk;
        public BossBar bar;
        public boolean ending;
        public long began;
        public int announced = -1;

        Session(Arena a, Settings c) {
            id = a.id();
            engine =
                    new MatchEngine(
                            generation,
                            Mode.valueOf(a.mode()),
                            c.integer("team-capacity", 1, 128, 8),
                            c.integer("max-team-difference", 1, 128, 1),
                            c.integer("min-players", 2, 256, 2),
                            a.countdown() * 1000L,
                            a.round() * 1000L);
        }

        public Set<UUID> all() {
            Set<UUID> all = new LinkedHashSet<>(players);
            all.addAll(spectators);
            return all;
        }
    }

    private final AbyssalDominionSiegeWarPlugin p;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<UUID, Session> members = new HashMap<>();
    private final Set<String> editing = new HashSet<>();
    private final Map<Chunk, Integer> kingTickets = new HashMap<>();
    private final Map<UUID, Map<String, Object>> live =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, Object> cachedPlaceholders(UUID id) {
        return live.getOrDefault(id, Map.of());
    }

    public final NamespacedKey generationKey, ownerKey, abilityKey, actionKey, kingKey, lifeKey, itemKey, kitKey;

    public Game(AbyssalDominionSiegeWarPlugin p) {
        this.p = p;
        generationKey = new NamespacedKey(p, "session");
        ownerKey = new NamespacedKey(p, "owner");
        abilityKey = new NamespacedKey(p, "ability");
        actionKey = new NamespacedKey(p, "menu_action");
        kingKey = new NamespacedKey(p, "king");
        lifeKey = new NamespacedKey(p, "life");
        itemKey = new NamespacedKey(p, "issued_item");
        kitKey = new NamespacedKey(p, "issued_kit");
    }

    public Collection<Session> sessions() {
        return List.copyOf(sessions.values());
    }

    public Session session(UUID id) {
        return members.get(id);
    }

    public Session arena(String id) {
        return sessions.get(id);
    }

    public Arena definition(String id) {
        Arena a = p.arenas().all().get(id);
        if (a == null) throw new IllegalArgumentException("Unknown arena: " + id);
        return a;
    }

    public static Point point(Location l) {
        return new Point(
                l.getWorld().getUID(),
                l.getWorld().getName(),
                l.getX(),
                l.getY(),
                l.getZ(),
                l.getYaw(),
                l.getPitch());
    }

    public static Location location(Point point) {
        if (point == null) throw new IllegalArgumentException("Spawn is not configured");
        World w = Bukkit.getWorld(point.world());
        if (w == null) throw new IllegalStateException("World is not loaded: " + point.worldName());
        return new Location(w, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
    }

    public boolean inside(Arena a, Location l) {
        return a.bounds() != null && l.getWorld() != null && a.bounds().contains(point(l));
    }

    public List<String> validate(String id) {
        Arena a = definition(id);
        List<String> errors = new ArrayList<>(p.arenas().validate(id));
        if (a.bounds() != null) {
            World w = Bukkit.getWorld(a.bounds().world());
            if (w == null) errors.add("Arena world is unloaded");
            else {
                if (!w.getPVP())
                    errors.add(
                            "World PvP is disabled; enable it in the world's Multiverse/server"
                                    + " settings");
                if (a.bounds().minY() < w.getMinHeight() || a.bounds().maxY() >= w.getMaxHeight())
                    errors.add("Bounds exceed world height");
            }
            if (a.bounds().volume() > p.settings().general.getLong("max-arena-blocks", 2000000))
                errors.add("Reset bounds exceed block budget");
        }
        if (!p.templates().exists(a))
            errors.add("Capture the map first: /siegewar arena capture " + id);
        if (p.templates().interrupted(a))
            errors.add("Interrupted reset: /siegewar arena reset " + id + " before enabling");
        if (p.settings().flag("require-worldguard", true) && !p.protection().regionExists(a))
            errors.add(
                    "Missing WorldGuard region; capture creates asw_" + id + " or use setregion");
        if (p.settings().flag("require-multiverse", false)
                && !p.getServer().getPluginManager().isPluginEnabled("Multiverse-Core"))
            errors.add("Required Multiverse-Core is unavailable");
        if (p.settings().flag("integrations.require-geyser", false)
                && !p.getServer().getPluginManager().isPluginEnabled("Geyser-Spigot")) errors.add("Required Geyser-Spigot is unavailable");
        if (p.settings().flag("integrations.require-floodgate", false)
                && !p.getServer().getPluginManager().isPluginEnabled("floodgate")) errors.add("Required floodgate is unavailable");
        return errors;
    }

    public void load() {
        for (Arena a : p.arenas().all().values())
            if (a.enabled()) {
                List<String> errors = validate(a.id());
                if (errors.isEmpty()) sessions.put(a.id(), new Session(a, p.settings()));
                else p.getLogger().warning(a.id() + " not enabled: " + String.join("; ", errors));
            }
    }

    public boolean busy(String id) {
        Session s = sessions.get(id);
        return editing.contains(id) || (s != null && (!s.all().isEmpty() || s.ending));
    }

    public boolean anyBusy() {
        return !members.isEmpty() || !editing.isEmpty()
                || sessions.values().stream().anyMatch(s -> s.ending);
    }

    public void ensureEditable(String id) {
        definition(id);
        if (busy(id))
            throw new IllegalStateException(
                    "Stop the arena and wait for restoration/reset before editing");
    }

    public void refresh(String id) {
        if (busy(id)) return;
        sessions.remove(id);
        Arena a = definition(id);
        if (a.enabled() && validate(id).isEmpty()) sessions.put(id, new Session(a, p.settings()));
    }

    public void enable(String id, boolean value) throws Exception {
        ensureEditable(id);
        Arena a = definition(id);
        if (value) {
            List<String> errors = validate(id);
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        }
        p.arenas().save(a.enabled(value));
        refresh(id);
    }

    public void join(Player player, String id, boolean spectate) {
        p.require(player, spectate ? "spectate" : "join");
        if (members.containsKey(player.getUniqueId())
                || p.snapshots().pending(player.getUniqueId()))
            throw new IllegalStateException("You already have an active or pending session");
        if (player.isDead() || player.isInsideVehicle())
            throw new IllegalStateException("Leave your vehicle and finish respawning first");
        for (Arena a : p.arenas().all().values())
            if (inside(a, player.getLocation()))
                throw new IllegalStateException(
                        "Join from the lobby outside the map's reset bounds");
        Session s = sessions.get(id);
        if (s == null || s.ending || editing.contains(id))
            throw new IllegalStateException("Arena is disabled or is resetting");
        if (spectate) {
            if (s.engine.state() != State.RUNNING
                    || s.spectators.size() >= p.settings().integer("spectator-limit", 0, 128, 16))
                throw new IllegalStateException("No spectator slot is available");
        } else if (s.engine.state() != State.WAITING && s.engine.state() != State.COUNTDOWN)
            throw new IllegalStateException("That match has already started");
        UUID uuid = player.getUniqueId();
        CompletableFuture<Escrow> capture = p.snapshots().prepare(player, id, s.generation);
        UUID request = p.snapshots().request(uuid);
        capture.whenComplete(
                        (escrow, error) ->
                                p.sync(
                                        () -> {
                                            if (error != null) {
                                                if (request != null && request.equals(p.snapshots().request(uuid))
                                                        && player.isOnline() && Bukkit.getPlayer(uuid) == player) {
                                                    p.snapshots().restore(player);
                                                    p.error(player, error);
                                                }
                                                return;
                                            }
                                            // A leave/recovery request supersedes an in-flight
                                            // capture before any inventory mutation.
                                            if (!p.snapshots().current(escrow)) return;
                                            boolean valid =
                                                    player.isOnline()
                                                            && Bukkit.getPlayer(uuid) == player
                                                            && sessions.get(id) == s
                                                            && !s.ending
                                                            && !members.containsKey(uuid)
                                                            && player.hasPermission(
                                                                    "abyssaldominionsiegewar."
                                                                            + (spectate
                                                                                    ? "spectate"
                                                                                    : "join"));
                                            valid =
                                                    valid
                                                            && (spectate
                                                                    ? s.engine.state() == State.RUNNING
                                                                            && s.spectators.size() < p.settings().integer("spectator-limit", 0, 128, 16)
                                                                    : s.engine.join(
                                                                            uuid, Team.AUTO));
                                            if (!valid) {
                                                p.snapshots().cancelUnchanged(escrow);
                                                if (player.isOnline())
                                                    p.tell(
                                                            player,
                                                            "<red>The arena changed or the teams"
                                                                    + " are full. Please try"
                                                                    + " again.</red>");
                                                return;
                                            }
                                            members.put(uuid, s);
                                            if (spectate) s.spectators.add(uuid);
                                            else s.players.add(uuid);
                                            s.previousBoards.put(uuid, player.getScoreboard());
                                            try {
                                                // The durable snapshot precedes the first mutation (teleport).
                                                // Players keep their own contents and attributes on join.
                                                if (spectate) player.setGameMode(GameMode.SPECTATOR);
                                                s.lives.put(uuid, UUID.randomUUID());
                                                p.items().warnInventory(player);
                                                if (!p.snapshots()
                                                        .teleport(
                                                                player,
                                                                location(
                                                                        definition(id)
                                                                                .spawns()
                                                                                .get(
                                                                                        spectate
                                                                                                ? "spectator"
                                                                                                : "waiting"))))
                                                    throw new IllegalStateException(
                                                            "Join teleport was cancelled");
                                                p.snapshots().prepared(uuid);
                                                if (!spectate) {
                                                    s.selected.put(
                                                            uuid,
                                                            defaultKit(s.engine.team(uuid)).id());
                                                    waitingItems(player, s);
                                                    p.say(
                                                            player,
                                                            "joined",
                                                            Map.of(
                                                                    "arena",
                                                                    id,
                                                                    "team",
                                                                    s.engine.team(uuid)));
                                                    maybeCountdown(s);
                                                } else {
                                                    if (s.bar != null) player.showBossBar(s.bar);
                                                    p.say(
                                                            player,
                                                            "spectating",
                                                            Map.of("arena", id));
                                                }
                                                p.views().main(player);
                                            } catch (Exception ex) {
                                                p.snapshots().prepared(uuid);
                                                leave(player);
                                                p.error(player, ex);
                                            }
                                        }));
    }

    public void leave(Player player) {
        if (!members.containsKey(player.getUniqueId())
                && !p.snapshots().pending(player.getUniqueId())) {
            p.say(player, "not-joined");
            return;
        }
        live.remove(player.getUniqueId());
        p.items().forget(player.getUniqueId());
        Session s = members.remove(player.getUniqueId());
        if (s != null) {
            cleanupOwned(s, player.getUniqueId());
            UUID id = player.getUniqueId();
            s.players.remove(id);
            s.spectators.remove(id);
            s.dead.remove(id);
            s.lives.remove(id);
            s.charges.remove(id);
            s.selected.remove(id);
            s.engine.leave(id, System.currentTimeMillis());
            restoreBoard(player, s);
            if (s.bar != null) player.hideBossBar(s.bar);
            maybeFinish(s);
        }
        p.snapshots().restore(player);
    }

    public void disconnect(Player player) {
        if (members.containsKey(player.getUniqueId())) leave(player);
        try {
            p.snapshots().restoreNow(player);
        } catch (Exception ex) {
            // One warning per disconnect; escrow remains authoritative for the next login.
            p.getLogger().warning("Recovery deferred for " + player.getUniqueId() + ": " + ex.getMessage());
        } finally {
            p.snapshots().disconnected(player);
        }
    }

    public void team(Player player, String team) {
        p.require(player, "use");
        Session s = require(player, false);
        Team target = Team.valueOf(team.toUpperCase(Locale.ROOT));
        if (!s.engine.chooseTeam(player.getUniqueId(), target))
            throw new IllegalArgumentException("Team is full, unbalanced, or already locked");
        s.selected.put(player.getUniqueId(), defaultKit(s.engine.team(player.getUniqueId())).id());
        p.say(player, "team-selected", Map.of("team", s.engine.team(player.getUniqueId())));
        maybeCountdown(s);
    }

    private void maybeCountdown(Session s) {
        if (s.engine.state() == State.WAITING && s.engine.eligible())
            s.engine.startCountdown(System.currentTimeMillis());
    }

    public KitDefinition defaultKit(Team team) {
        return p.settings().kits.values().stream()
                .filter(k -> k.defaultKit() && k.price() == 0 && k.team().equals(team.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No free kit for " + team));
    }

    public boolean hasKit(Player player, KitDefinition k) {
        return k.price() == 0
                || player.hasPermission("abyssaldominionsiegewar.kit." + k.id())
                || p.owned(player.getUniqueId()).contains(k.id());
    }

    public void select(Player player, String kit) {
        p.require(player, "use");
        Session s = require(player, false);
        if (s.engine.state() != State.WAITING && s.engine.state() != State.COUNTDOWN)
            throw new IllegalStateException("Select your kit before the match starts");
        KitDefinition k = p.settings().kits.get(kit);
        if (k == null) throw new IllegalArgumentException("Unknown kit: " + kit);
        if (!k.team().equals(s.engine.team(player.getUniqueId()).name()))
            throw new IllegalArgumentException("That kit belongs to the other team");
        if (!hasKit(player, k))
            throw new IllegalArgumentException("Unlock the kit with /siegewar buykit " + kit);
        s.selected.put(player.getUniqueId(), kit);
        p.say(player, "kit-selected", Map.of("kit", Text.of(k.displayName())));
    }

    public Session require(Player player, boolean running) {
        Session s = members.get(player.getUniqueId());
        if (s == null || !s.players.contains(player.getUniqueId()))
            throw new IllegalStateException("Join an arena first");
        if (p.snapshots().pending(player.getUniqueId()))
            throw new IllegalStateException("Your saved state is being processed");
        if (running && (s.engine.state() != State.RUNNING || s.dead.contains(player.getUniqueId())))
            throw new IllegalStateException("You are not alive in a running match");
        return s;
    }

    public void forceStart(String id) {
        Session s = sessions.get(id);
        if (s == null || s.ending || !s.engine.forceStart(System.currentTimeMillis()))
            throw new IllegalStateException("Both teams and enough players are required");
        start(s);
    }

    public void stop(String id) {
        Session s = sessions.get(id);
        if (s == null) throw new IllegalStateException("Arena is not enabled");
        s.engine.abort("administrator-stop");
        maybeFinish(s);
    }

    private void start(Session s) {
        Arena a = definition(s.id);
        try {
            java.nio.file.Files.writeString(
                    p.templates().file(a.id()).resolveSibling(a.id() + ".dirty"),
                    "Unfinished match: reset this arena before enabling after a restart.");
            s.began = System.currentTimeMillis();
            if (p.settings().flag("allow-block-edit", true)) p.protection().begin(a);
            Location kingLocation = location(a.spawns().get("king"));
            World world = kingLocation.getWorld();
            // A lobby may be in another Multiverse world, leaving this chunk unloaded.
            // Load entity storage before spawning and keep the objective present all round.
            s.kingChunk = kingLocation.getChunk();
            if (kingTickets.merge(s.kingChunk, 1, Integer::sum) == 1)
                s.kingChunk.addPluginChunkTicket(p);
            s.kingChunk.getEntities();
            EntityType type =
                    EntityType.valueOf(p.settings().general.getString("king-type", "ZOMBIE"));
            Entity entity = world.spawnEntity(kingLocation, type);
            if (!(entity instanceof LivingEntity king)) {
                entity.remove();
                throw new IllegalArgumentException("King must be a living entity");
            }
            if (!king.isValid()) {
                entity.remove();
                throw new IllegalStateException(
                        "King spawn was cancelled. Check WorldGuard mob-spawning and other spawn"
                                + " restrictions.");
            }
            s.king = king;
            king.getPersistentDataContainer().set(kingKey, PersistentDataType.STRING, s.id);
            king.getPersistentDataContainer()
                    .set(generationKey, PersistentDataType.STRING, s.generation.toString());
            king.setAI(false);
            king.setGravity(false);
            if (king.getAttribute(Attribute.KNOCKBACK_RESISTANCE) != null)
                king.getAttribute(Attribute.KNOCKBACK_RESISTANCE).setBaseValue(1);
            king.setRemoveWhenFarAway(false);
            king.setPersistent(false);
            king.setSilent(true);
            king.setCollidable(false);
            king.setCanPickupItems(false);
            king.customName(
                    Text.of(p.settings().general.getString("king-name", "<gold>King</gold>")));
            king.setCustomNameVisible(true);
            double hp =
                    Settings.finite(
                            p.settings().general.getDouble("king-health", 30),
                            1,
                            2048,
                            "king-health");
            king.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
            king.setHealth(hp);
            king.setFireTicks(0);
            s.bar =
                    BossBar.bossBar(
                            Text.of("<gold>Defend the King</gold>"),
                            1,
                            BossBar.Color.YELLOW,
                            BossBar.Overlay.PROGRESS);
            for (UUID id : s.players) {
                Player player = Bukkit.getPlayer(id);
                if (player == null) continue;
                player.closeInventory();
                spawn(player, s);
                player.showBossBar(s.bar);
                p.say(player, "match-started");
            }
            for (UUID id : s.spectators) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) player.showBossBar(s.bar);
            }
        } catch (Exception ex) {
            p.getLogger().log(java.util.logging.Level.SEVERE, "Could not start " + s.id, ex);
            s.engine.abort("start-failed");
            maybeFinish(s);
        }
    }

    private void spawn(Player player, Session s) {
        UUID id = player.getUniqueId();
        stripIssued(player, s);
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setInvulnerable(false);
        player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.setFireTicks(0);
        player.setFallDistance(0);
        if (!p.snapshots()
                .teleport(
                        player,
                        location(
                                definition(s.id)
                                        .spawns()
                                        .get(s.engine.team(id).name().toLowerCase(Locale.ROOT)))))
            throw new IllegalStateException("Team spawn teleport was cancelled");
        for (PotionEffect effect : player.getActivePotionEffects())
            if (p.settings().blacklistedEffects.contains(effect.getType())) player.removePotionEffect(effect.getType());
        s.lives.put(id, UUID.randomUUID());
        KitDefinition k = p.settings().kits.get(s.selected.get(id));
        if (k == null || !hasKit(player, k) || !k.team().equals(s.engine.team(id).name()))
            k = defaultKit(s.engine.team(id));
        s.selected.put(id, k.id());
        for (KitDefinition.Gear gear : k.items()) {
            if (p.items().blacklisted(gear.material())) continue;
            give(player, item(player, s, gear), gear.slot());
        }
        // Own armor always keeps its slot. Kit armor uses only empty slots.
        for (int i = 0; i < Math.min(4, k.armor().size()); i++) {
            var gear = k.armor().get(i);
            if (p.items().blacklisted(gear.material())) continue;
            ItemStack own = player.getInventory().getItem(36 + i);
            ItemStack issued = item(player, s, gear);
            if (empty(own)) player.getInventory().setItem(36 + i, issued);
            else give(player, issued, -1);
        }
        Map<String, Integer> charges = s.charges.computeIfAbsent(id, ignored -> new HashMap<>());
        for (KitDefinition.Gear gear : k.items())
            if (!gear.ability().isBlank())
                charges.putIfAbsent(gear.ability(), p.settings().abilities.get(gear.ability()).charges());
        s.charges.put(id, charges);
        s.dead.remove(id);
    }

    private ItemStack item(Player player, Session s, KitDefinition.Gear gear) {
        ItemStack item = new ItemStack(gear.material(), gear.amount());
        ItemMeta m = item.getItemMeta();
        m.getPersistentDataContainer()
                .set(generationKey, PersistentDataType.STRING, s.generation.toString());
        m.getPersistentDataContainer()
                .set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        m.getPersistentDataContainer()
                .set(lifeKey, PersistentDataType.STRING, s.lives.get(player.getUniqueId()).toString());
        if (!gear.ability().isBlank()) {
            m.getPersistentDataContainer()
                    .set(abilityKey, PersistentDataType.STRING, gear.ability());
            m.displayName(Text.of("<aqua>" + gear.ability().replace('_', ' ') + "</aqua>"));
            m.lore(
                    List.of(
                            Text.of(
                                    "<gray>Use item or /siegewar ability "
                                            + gear.ability()
                                            + "</gray>")));
        }
        String kit = s.selected.get(player.getUniqueId());
        m.getPersistentDataContainer().set(kitKey, PersistentDataType.STRING, kit);
        UUID issuedId = s.issued.issue(new IssuedRegistry.Binding(player.getUniqueId(),
                s.generation, s.lives.get(player.getUniqueId()), kit, gear.ability(), "", gear.material().name()));
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, issuedId.toString());
        item.setItemMeta(m);
        gear.enchantments()
                .forEach(
                        (id, level) -> {
                            var enchant =
                                    Registry.ENCHANTMENT.get(
                                            NamespacedKey.fromString(
                                                    id.contains(":") ? id : "minecraft:" + id));
                            if (enchant != null)
                                item.addUnsafeEnchantment(
                                        enchant, Math.min(level, enchant.getMaxLevel()));
                        });
        return item;
    }

    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir(); }

    private int freeStorage(Player player) {
        for (int i = 0; i < 36; i++) if (empty(player.getInventory().getItem(i))) return i;
        return -1;
    }

    private void give(Player player, ItemStack item, int preferred) {
        int slot = preferred >= 0 && empty(player.getInventory().getItem(preferred))
                ? preferred : freeStorage(player);
        if (slot < 0) {
            Session s = session(player.getUniqueId());
            if (s != null && s.noSpaceWarned.add(player.getUniqueId())) p.say(player, "kit-no-space");
            return; // Never overwrite own items or spill kit gear to the ground.
        }
        player.getInventory().setItem(slot, item);
    }

    private void stripIssued(Player player, Session s) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (tagged(item)) player.getInventory().setItem(i, null);
        }
        if (tagged(player.getItemOnCursor())) player.setItemOnCursor(null);
        s.issued.revoke(player.getUniqueId());
    }

    public boolean tagged(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(generationKey, PersistentDataType.STRING);
    }

    public boolean currentItem(Player player, ItemStack item) {
        Session s = session(player.getUniqueId());
        if (s == null || !tagged(item)) return false;
        var tags = item.getItemMeta().getPersistentDataContainer();
        try {
            return s.issued.accepts(UUID.fromString(tags.get(itemKey, PersistentDataType.STRING)),
                    new IssuedRegistry.Binding(player.getUniqueId(),
                            UUID.fromString(tags.get(generationKey, PersistentDataType.STRING)),
                            UUID.fromString(tags.get(lifeKey, PersistentDataType.STRING)),
                            tags.get(kitKey, PersistentDataType.STRING),
                            tags.getOrDefault(abilityKey, PersistentDataType.STRING, ""),
                            tags.getOrDefault(actionKey, PersistentDataType.STRING, ""), item.getType().name()))
                    && s.generation.toString().equals(tags.get(generationKey, PersistentDataType.STRING))
                    && player.getUniqueId().toString().equals(tags.get(ownerKey, PersistentDataType.STRING))
                    && s.lives.get(player.getUniqueId()).toString().equals(tags.get(lifeKey, PersistentDataType.STRING));
        } catch (IllegalArgumentException | NullPointerException ex) { return false; }
    }

    private void waitingItems(Player player, Session s) {
        int[] slots = {0, 4, 8};
        Material[] types = {Material.CLOCK, Material.NETHER_STAR, Material.RED_DYE};
        String[] actions = {"team", "kits", "leave"};
        for (int i = 0; i < slots.length; i++) {
            if (freeStorage(player) < 0) return;
            ItemStack item = new ItemStack(types[i]);
            ItemMeta m = item.getItemMeta();
            m.displayName(Text.of("<yellow>" + actions[i] + "</yellow>"));
            var tags = m.getPersistentDataContainer();
            tags.set(actionKey, PersistentDataType.STRING, actions[i]);
            tags.set(generationKey, PersistentDataType.STRING, s.generation.toString());
            tags.set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            tags.set(lifeKey, PersistentDataType.STRING, s.lives.get(player.getUniqueId()).toString());
            tags.set(kitKey, PersistentDataType.STRING, "waiting");
            UUID issued = s.issued.issue(new IssuedRegistry.Binding(player.getUniqueId(), s.generation,
                    s.lives.get(player.getUniqueId()), "waiting", "", actions[i], types[i].name()));
            tags.set(itemKey, PersistentDataType.STRING, issued.toString());
            item.setItemMeta(m);
            give(player, item, slots[i]);
        }
    }

    public boolean active(Player player) {
        Session s = members.get(player.getUniqueId());
        return s != null
                && s.players.contains(player.getUniqueId())
                && s.engine.accepts(s.generation, player.getUniqueId())
                && !s.dead.contains(player.getUniqueId())
                && !p.snapshots().pending(player.getUniqueId())
                && inside(definition(s.id), player.getLocation());
    }

    public boolean enemies(Player a, Player b) {
        Session s = members.get(a.getUniqueId());
        return active(a)
                && active(b)
                && s == members.get(b.getUniqueId())
                && s.engine.team(a.getUniqueId()) != s.engine.team(b.getUniqueId())
                && inside(definition(s.id), a.getLocation())
                && inside(definition(s.id), b.getLocation())
                && p.protection().pvp(a, a.getLocation())
                && p.protection().pvp(a, b.getLocation());
    }

    public boolean kingTarget(Player a, Entity target) {
        Session s = members.get(a.getUniqueId());
        return active(a)
                && s.king != null
                && s.king.getUniqueId().equals(target.getUniqueId())
                && s.engine.team(a.getUniqueId()) == Team.ATTACKERS
                && inside(definition(s.id), target.getLocation())
                && p.protection().pvp(a, target.getLocation());
    }

    public Session king(Entity entity) {
        for (Session s : sessions.values())
            if (s.king != null && s.king.getUniqueId().equals(entity.getUniqueId())) return s;
        return null;
    }

    public void kingDead(Session s) {
        s.engine.kingKilled(System.currentTimeMillis());
        maybeFinish(s);
    }

    public void death(Player victim, Player killer) {
        Session s = members.get(victim.getUniqueId());
        if (s == null
                || s.ending
                || !s.players.contains(victim.getUniqueId())
                || !s.dead.add(victim.getUniqueId())) return;
        cleanupOwned(s, victim.getUniqueId());
        UUID victimId = victim.getUniqueId(),
                killerId =
                        killer != null && enemiesBeforeDeath(killer, victim, s)
                                ? killer.getUniqueId()
                                : null,
                life = s.lives.getOrDefault(victimId, UUID.randomUUID());
        long now = System.currentTimeMillis(), coins = 0;
        if (killerId != null) {
            String key = killerId + ":" + victimId;
            long previous = s.farm.getOrDefault(key, 0L);
            s.farm.put(key, now);
            if (now - previous > p.settings().general.getLong("kill-reward-cooldown-seconds", 30) * 1000L) coins = p.settings().general.getLong("kill-coins", 1);
        }
        long reward = coins;
        p.io(
                        () -> {
                            p.store().recordDeath(s.generation, life, victimId, killerId, reward);
                            return null;
                        })
                .whenComplete(
                        (v, e) -> {
                            if (e != null)
                                p.getLogger()
                                        .log(
                                                java.util.logging.Level.SEVERE,
                                                "Death reward transaction failed",
                                                e);
                            p.refreshStats(victimId);
                            if (killerId != null) p.refreshStats(killerId);
                        });
        if (killerId != null) {
            broadcast(s, "kill", Map.of("killer", killer.getName(), "victim", victim.getName()));
            KitDefinition k = p.settings().kits.get(s.selected.get(killerId));
            if (k != null) effects(killer, k.killEffects());
        }
        s.engine.eliminate(victimId, now);
        victim.closeInventory();
        boolean actualDeath = victim.isDead();
        if (actualDeath) {
            Bukkit.getScheduler().runTask(p, () -> {
                if ((members.get(victimId) == s || (members.get(victimId) == null && p.snapshots().pending(victimId)))
                        && victim.isOnline() && Bukkit.getPlayer(victimId) == victim && victim.isDead())
                    victim.spigot().respawn();
            });
        } else {
            victim.setGameMode(GameMode.SPECTATOR);
            victim.setFireTicks(0);
            victim.setHealth(victim.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
        if (definition(s.id).mode().equals("HARDCORE")) {
            p.say(victim, "eliminated");
            if (!actualDeath) p.snapshots().teleport(victim, location(definition(s.id).spawns().get("spectator")));
            maybeFinish(s);
            return;
        }
        int delay = p.settings().integer("respawn-seconds", 1, 3600, 5);
        p.say(victim, "respawn", Map.of("seconds", delay));
        if (!actualDeath) scheduleRespawn(victim, s, delay);
    }

    public void afterActualRespawn(Player player) {
        Session s = members.get(player.getUniqueId());
        if (s == null || s.ending || !s.dead.contains(player.getUniqueId())) return;
        player.setGameMode(GameMode.SPECTATOR);
        if (definition(s.id).mode().equals("NORMAL"))
            scheduleRespawn(player, s, p.settings().integer("respawn-seconds", 1, 3600, 5));
    }

    private void scheduleRespawn(Player victim, Session s, int delay) {
        UUID victimId = victim.getUniqueId();
        Bukkit.getScheduler()
                .runTaskLater(
                        p,
                        () -> {
                            if (sessions.get(s.id) == s && members.get(victimId) == s
                                    && !s.ending
                                    && s.engine.state() == State.RUNNING
                                    && s.dead.contains(victimId)
                                    && victim.isOnline() && Bukkit.getPlayer(victimId) == victim
                                    && !victim.isDead()) {
                                try {
                                    spawn(victim, s);
                                } catch (Exception e) {
                                    p.error(victim, e);
                                    leave(victim);
                                }
                            }
                        },
                        delay * 20L);
    }

    private boolean enemiesBeforeDeath(Player a, Player b, Session s) {
        return members.get(a.getUniqueId()) == s
                && active(a)
                && s.engine.team(a.getUniqueId()) != s.engine.team(b.getUniqueId());
    }

    public static void effects(Player player, List<KitDefinition.Effect> effects) {
        for (var e : effects) {
            PotionEffectType t = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(e.type()));
            if (t != null)
                player.addPotionEffect(
                        new PotionEffect(t, e.ticks(), e.amplifier(), false, true, true));
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();
        for (Session s : List.copyOf(sessions.values()))
            try {
                s.entities.values().removeIf(entity -> !entity.isValid());
                State before = s.engine.state();
                s.engine.tick(now);
                if (before != State.RUNNING && s.engine.state() == State.RUNNING) start(s);
                if (s.engine.state() == State.COUNTDOWN) {
                    int sec =
                            (int)
                                    Math.max(
                                            0,
                                            (s.engine.countdownDeadlineMillis() - now + 999)
                                                    / 1000);
                    if (sec != s.announced && (sec <= 5 || sec % 5 == 0)) {
                        s.announced = sec;
                        broadcast(s, "countdown", Map.of("seconds", sec));
                    }
                }
                if (s.engine.state() == State.RUNNING && (s.king == null || !s.king.isValid())) {
                    s.engine.abort("king-unavailable");
                }
                if (s.king != null && !s.king.isDead() && s.bar != null) {
                    s.king.setFireTicks(0);
                    double max = s.king.getAttribute(Attribute.MAX_HEALTH).getValue();
                    s.bar.progress((float) Math.max(0, Math.min(1, s.king.getHealth() / max)));
                    s.bar.name(
                            Text.of(
                                    "<gold>King: <hp>/<max> HP</gold>",
                                    Map.of(
                                            "hp",
                                            Math.round(s.king.getHealth()),
                                            "max",
                                            Math.round(max))));
                }
                maybeFinish(s);
                for (UUID id : s.all()) {
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) {
                        live.put(id, Map.copyOf(placeholders(player)));
                        board(player, s);
                    }
                }
            } catch (Exception e) {
                p.getLogger().log(java.util.logging.Level.SEVERE, "Arena tick failed: " + s.id, e);
                s.engine.abort("runtime-error");
                maybeFinish(s);
            }
    }

    public void maybeFinish(Session s) {
        if (!s.ending && s.engine.outcome().isPresent()) finish(s, s.engine.outcome().get());
    }

    private void finish(Session s, Outcome outcome) {
        if (s.ending) return;
        s.ending = true;
        try { p.protection().end(definition(s.id)); }
        catch (RuntimeException ex) { p.getLogger().log(java.util.logging.Level.SEVERE, "WorldGuard flag recovery retained for " + s.id, ex); }
        cleanupEntities(s);
        List<UUID> ids = List.copyOf(s.all());
        List<Reward> rewards = new ArrayList<>();
        boolean eligible =
                !outcome.aborted()
                        && System.currentTimeMillis() - s.began
                                >= p.settings().general.getLong("reward-minimum-seconds", 10)
                                        * 1000L;
        for (UUID id : s.players) {
            boolean won = eligible && s.engine.team(id) == outcome.winner();
            rewards.add(
                    new Reward(
                            id,
                            won ? p.settings().general.getLong("win-coins", 3) : 0,
                            0,
                            0,
                            won ? 1 : 0));
        }
        broadcast(
                s,
                outcome.aborted() ? "result-aborted" : "result",
                Map.of("arena", s.id, "winner", outcome.winner(), "reason", outcome.reason()));
        p.io(
                        () -> {
                            p.store().recordResult(s.generation, s.id, outcome.reason(), rewards);
                            return null;
                        })
                .whenComplete(
                        (v, e) -> {
                            if (e != null)
                                p.getLogger()
                                        .log(
                                                java.util.logging.Level.SEVERE,
                                                "Match result transaction failed",
                                                e);
                            for (UUID id : ids) p.refreshStats(id);
                        });
        List<CompletableFuture<Void>> returns = new ArrayList<>();
        for (UUID id : ids) {
            members.remove(id);
            live.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                restoreBoard(player, s);
                returns.add(p.snapshots().restore(player));
            }
        }
        s.players.clear();
        s.spectators.clear();
        s.engine.beginReset();
        CompletableFuture.allOf(returns.toArray(CompletableFuture[]::new))
                .whenComplete(
                        (v, e) ->
                                p.sync(
                                        () -> {
                                            if (sessions.get(s.id) != s) return;
                                            if (e != null) {
                                                s.engine.fail(
                                                        "Player restoration pending; use admin"
                                                                + " reset after recovery");
                                                return;
                                            }
                                            if (!p.settings().flag("regenerate-after-match", true)) {
                                                s.engine.fail("Automatic regeneration is disabled; reset manually before reuse");
                                                return;
                                            }
                                            Bukkit.getScheduler()
                                                    .runTask(
                                                            p,
                                                            () -> {
                                                                if (sessions.get(s.id) != s || !s.ending) return;
                                                                try {
                                                                    reset(s.id, null);
                                                                } catch (Exception ex) {
                                                                    s.engine.fail(ex.getMessage());
                                                                    p.getLogger()
                                                                            .log(
                                                                                    java.util
                                                                                            .logging
                                                                                            .Level
                                                                                            .WARNING,
                                                                                    "Reset"
                                                                                        + " deferred:"
                                                                                        + " "
                                                                                            + s.id,
                                                                                    ex);
                                                                }
                                                            });
                                        }));
    }

    private void cleanupEntities(Session s) {
        if (s.bar != null)
            for (UUID id : s.all()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) player.hideBossBar(s.bar);
            }
        if (s.king != null) {
            s.king.remove();
            s.king = null;
        }
        if (s.kingChunk != null) {
            Chunk chunk = s.kingChunk;
            kingTickets.computeIfPresent(
                    chunk,
                    (key, count) -> {
                        if (count > 1) return count - 1;
                        key.removePluginChunkTicket(p);
                        return null;
                    });
            s.kingChunk = null;
        }
        for (Entity entity : s.entities.values()) if (entity.isValid()) entity.remove();
        s.entities.clear();
    }

    public void reset(String id, CommandSender sender) {
        Arena a = definition(id);
        Session old = sessions.get(id);
        if (old != null && !old.all().isEmpty())
            throw new IllegalStateException("Stop the occupied match first");
        World world = a.bounds() == null ? null : Bukkit.getWorld(a.bounds().world());
        if (world != null)
            for (Player player : world.getPlayers())
                if (inside(a, player.getLocation()))
                    throw new IllegalStateException("Move everyone outside the reset bounds first");
        if (!editing.add(id)) throw new IllegalStateException("Reset/capture already in progress");
        try {
            p.templates()
                    .reset(a)
                    .whenComplete(
                            (v, error) ->
                                    p.sync(
                                            () -> {
                                                editing.remove(id);
                                                if (error != null) {
                                                    if (old != null)
                                                        old.engine.fail("reset-failed");
                                                    if (sender != null) p.error(sender, error);
                                                    p.getLogger()
                                                            .log(
                                                                    java.util.logging.Level.SEVERE,
                                                                    "Arena reset failed: " + id,
                                                                    error);
                                                    return;
                                                }
                                                sessions.remove(id);
                                                if (a.enabled() && validate(id).isEmpty())
                                                    sessions.put(id, new Session(a, p.settings()));
                                                if (sender != null) p.say(sender, "reset-complete");
                                            }));
            if (sender != null) p.say(sender, "reset-started");
        } catch (RuntimeException ex) {
            editing.remove(id);
            if (old != null) old.engine.fail(ex.getMessage());
            throw ex;
        }
    }

    public void capture(String id, CommandSender sender) {
        ensureEditable(id);
        Arena a = definition(id);
        editing.add(id);
        try {
            p.templates()
                    .capture(a)
                    .whenComplete(
                            (v, error) ->
                                    p.sync(
                                            () -> {
                                                editing.remove(id);
                                                if (error != null) {
                                                    p.error(sender, error);
                                                    return;
                                                }
                                                p.tell(
                                                        sender,
                                                        "<green>Captured map template for <arena>."
                                                                + " Set spawns, validate, then"
                                                                + " enable.</green>",
                                                        Map.of("arena", id));
                                            }));
        } catch (RuntimeException e) {
            editing.remove(id);
            throw e;
        }
    }

    public Map<String, Object> placeholders(Player player) {
        Session s = members.get(player.getUniqueId());
        Stats stats = p.stats(player.getUniqueId());
        Map<String, Object> v = new HashMap<>();
        v.put("arena", s == null ? "None" : s.id);
        v.put("state", s == null ? "LOBBY" : s.engine.state());
        v.put(
                "team",
                s == null
                        ? "None"
                        : s.spectators.contains(player.getUniqueId())
                                ? "SPECTATOR"
                                : s.engine.team(player.getUniqueId()));
        v.put("kit", s == null ? "None" : s.selected.getOrDefault(player.getUniqueId(), "None"));
        v.put("players", s == null ? 0 : s.players.size());
        v.put("max_players", p.settings().integer("team-capacity", 1, 128, 8) * 2);
        v.put(
                "time_remaining",
                s == null
                        ? 0
                        : Math.max(
                                0,
                                (s.engine.deadlineMillis() - System.currentTimeMillis() + 999)
                                        / 1000));
        v.put("king_health", s == null || s.king == null ? 0 : Math.round(s.king.getHealth()));
        v.put("king_max_health", p.settings().general.getDouble("king-health", 30));
        v.put("coins", stats == null ? 0 : stats.coins());
        v.put("kills", stats == null ? 0 : stats.kills());
        v.put("deaths", stats == null ? 0 : stats.deaths());
        v.put("wins", stats == null ? 0 : stats.wins());
        v.put(
                "kdr",
                stats == null
                        ? 0
                        : String.format(
                                Locale.ROOT,
                                "%.2f",
                                stats.kills() / (double) Math.max(1, stats.deaths())));
        v.put("mode", s == null ? "None" : definition(s.id).mode());
        v.put(
                "attackers",
                s == null
                        ? 0
                        : s.engine.teams().values().stream()
                                .filter(t -> t == Team.ATTACKERS)
                                .count());
        v.put(
                "defenders",
                s == null
                        ? 0
                        : s.engine.teams().values().stream()
                                .filter(t -> t == Team.DEFENDERS)
                                .count());
        return v;
    }

    private void board(Player player, Session s) {
        if (!p.settings().flag("scoreboard-enabled", true)) return;
        Scoreboard b =
                s.boards.computeIfAbsent(
                        player.getUniqueId(),
                        id -> {
                            Scoreboard created = Bukkit.getScoreboardManager().getNewScoreboard();
                            created.registerNewObjective(
                                            "asw",
                                            Criteria.DUMMY,
                                            Text.of(
                                                    p.settings()
                                                            .scoreboard
                                                            .getString(
                                                                    "title",
                                                                    "<gold>Siege War</gold>")))
                                    .setDisplaySlot(DisplaySlot.SIDEBAR);
                            return created;
                        });
        Map<String, Object> values = placeholders(player);
        Objective objective = b.getObjective("asw");
        List<String> lines = p.settings().scoreboard.getStringList("lines");
        for (int i = 0; i < Math.min(15, lines.size()); i++) {
            String entry = ChatColor.values()[i].toString() + ChatColor.RESET;
            org.bukkit.scoreboard.Team line = b.getTeam("l" + i);
            if (line == null) {
                line = b.registerNewTeam("l" + i);
                line.addEntry(entry);
            }
            line.prefix(Text.of(lines.get(i), values));
            objective.getScore(entry).setScore(lines.size() - i);
        }
        if (player.getScoreboard() != b) player.setScoreboard(b);
    }

    private void restoreBoard(Player player, Session s) {
        Scoreboard owned = s.boards.remove(player.getUniqueId()),
                previous = s.previousBoards.remove(player.getUniqueId());
        if (owned != null && player.getScoreboard() == owned && previous != null)
            player.setScoreboard(previous);
    }

    private void broadcast(Session s, String key, Map<String, ?> args) {
        for (UUID id : s.all()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) p.say(player, key, args);
        }
    }


    /** Every configured map is an escrowed reset area, including disabled/failed arenas. */
    public Arena protectedArena(Location at) {
        if (at == null) return null;
        for (Arena a : p.arenas().all().values()) if (inside(a, at)) return a;
        return null;
    }

    public boolean mayEnter(Player player, Location at) {
        Arena a = protectedArena(at);
        if (a == null) return true;
        Session own = session(player.getUniqueId());
        if (own != null && own.id.equals(a.id()) && !own.ending) return true;
        return mayEdit(player, at);
    }

    public boolean mayEdit(Player player, Location at) {
        Arena a = protectedArena(at);
        return a != null && !busy(a.id()) && !p.templates().busy(a.bounds().world())
                && session(player.getUniqueId()) == null
                && !p.snapshots().pending(player.getUniqueId())
                && player.hasPermission("abyssaldominionsiegewar.admin.setup");
    }

    public boolean mayBreak(Player player, Location at) {
        Session s = session(player.getUniqueId());
        return s != null && active(player) && inside(definition(s.id), at)
                && p.settings().flag("allow-block-edit", true) && p.protection().build(player, at);
    }

    public boolean issued(Entity entity) {
        return entity.getPersistentDataContainer().has(generationKey, PersistentDataType.STRING);
    }

    public boolean currentIssued(Entity entity) {
        var tags = entity.getPersistentDataContainer();
        String owner = tags.get(ownerKey, PersistentDataType.STRING);
        if (owner == null) return false;
        try {
            UUID id = UUID.fromString(owner);
            Player player = Bukkit.getPlayer(id);
            Session s = session(id);
            UUID life = s == null ? null : s.lives.get(id);
            return player != null && active(player) && life != null
                    && s.entities.get(entity.getUniqueId()) == entity
                    && s.generation.toString().equals(tags.get(generationKey, PersistentDataType.STRING))
                    && life.toString().equals(tags.get(lifeKey, PersistentDataType.STRING))
                    && inside(definition(s.id), entity.getLocation());
        } catch (IllegalArgumentException ex) { return false; }
    }

    private void cleanupOwned(Session s, UUID owner) {
        Iterator<Entity> entities = s.entities.values().iterator();
        while (entities.hasNext()) {
            Entity entity = entities.next();
            if (owner.toString().equals(entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) {
                entity.remove(); entities.remove();
            }
        }
        s.issued.revoke(owner);
    }

    public void trackEntity(Session s, Entity entity, UUID owner) {
        s.entities.values().removeIf(e -> !e.isValid());
        int perPlayer = p.settings().integer("max-projectiles-per-player", 1, 128, 32);
        int perArena = p.settings().integer("max-entities-per-arena", 1, 2048, 512);
        long own = s.entities.values().stream().filter(e -> owner.toString().equals(
                e.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))).count();
        if (!s.entities.containsKey(entity.getUniqueId()) && (own >= perPlayer || s.entities.size() >= perArena)) {
            entity.remove();
            throw new IllegalStateException("Arena projectile budget reached");
        }
        s.entities.put(entity.getUniqueId(), entity);
    }

    public void shutdown() {
        for (Session s : sessions.values()) {
            try { p.protection().end(definition(s.id)); }
            catch (RuntimeException ex) { p.getLogger().warning("WorldGuard flags require recovery: " + s.id); }
            cleanupEntities(s);
            for (UUID id : s.all()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) restoreBoard(player, s);
            }
        }
        members.clear();
        sessions.clear();
        live.clear();
    }
}
