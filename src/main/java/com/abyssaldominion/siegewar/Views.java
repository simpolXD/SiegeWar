package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.kit.KitDefinition;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.function.Consumer;

/** All actions use ordinary clicks, with a distinct purchase confirmation button. */
public final class Views {
    public static final class Menu implements InventoryHolder {
        final UUID owner;
        final UUID generation;
        final Settings rules;
        final long expires = System.currentTimeMillis() + 120000;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        Inventory inventory;
        boolean dispatched;

        Menu(Player player, AbyssalDominionSiegeWarPlugin plugin) {
            owner = player.getUniqueId();
            Game.Session session = plugin.game().session(owner);
            generation = session == null ? null : session.generation;
            rules = plugin.settings();
        }

        public Inventory getInventory() {
            return inventory;
        }
    }

    private final AbyssalDominionSiegeWarPlugin p;

    public Views(AbyssalDominionSiegeWarPlugin p) {
        this.p = p;
    }

    private Menu open(Player player, String key, int rows) {
        Menu m = new Menu(player, p);
        m.inventory =
                Bukkit.createInventory(
                        m,
                        rows * 9,
                        Text.of(p.settings().menus.getString(key, "<gold>Siege War</gold>")));
        return m;
    }

    private void button(
            Menu m,
            int slot,
            Material material,
            String title,
            List<String> lore,
            Consumer<Player> action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.of(title));
        meta.lore(lore.stream().map(Text::of).toList());
        item.setItemMeta(meta);
        m.inventory.setItem(slot, item);
        m.actions.put(slot, action);
    }

    public void main(Player player) {
        Menu m = open(player, "title", 3);
        button(
                m,
                10,
                Material.COMPASS,
                "<green>Choose Arena</green>",
                List.of("<gray>Join an available match</gray>"),
                this::arenas);
        button(m, 12, Material.CLOCK, "<yellow>Choose Team</yellow>", List.of(), this::teams);
        button(m, 14, Material.NETHER_STAR, "<aqua>Choose Kit</aqua>", List.of(), this::kits);
        button(
                m,
                16,
                Material.PAPER,
                "<gold>Your Statistics</gold>",
                List.of(),
                a -> p.statsMessage(a, a.getUniqueId()));
        button(
                m,
                22,
                Material.BARRIER,
                "<red>Leave safely</red>",
                List.of("<gray>Restores your saved state</gray>"),
                a -> p.game().leave(a));
        player.openInventory(m.inventory);
    }

    public void arenas(Player player) {
        Menu m = open(player, "arena-title", 6);
        int slot = 0;
        for (var a :
                p.arenas().all().values().stream()
                        .sorted(
                                Comparator.comparing(
                                        com.abyssaldominion.siegewar.runtime.ArenaRepository.Arena
                                                ::id))
                        .toList()) {
            if (slot >= 45) break;
            Game.Session s = p.game().arena(a.id());
            String state = s == null ? "DISABLED" : s.engine.state().name();
            button(
                    m,
                    slot++,
                    s == null ? Material.RED_STAINED_GLASS_PANE : Material.GREEN_STAINED_GLASS_PANE,
                    "<gold>" + a.id() + "</gold>",
                    List.of(
                            "<gray>Status: " + state,
                            "<gray>Mode: " + a.mode(),
                            "<gray>Players: " + (s == null ? 0 : s.players.size()),
                            "<yellow>Click/tap to join"),
                    who -> {
                        p.require(who, "join");
                        p.game().join(who, a.id(), false);
                    });
        }
        if (slot == 0)
            button(
                    m,
                    22,
                    Material.PAPER,
                    "<yellow>No arenas configured</yellow>",
                    List.of("<gray>An administrator must create and enable an arena.</gray>"),
                    a -> {});
        button(m, 49, Material.ARROW, "<white>Back</white>", List.of(), this::main);
        player.openInventory(m.inventory);
    }

    public void teams(Player player) {
        p.game().require(player, false);
        Menu m = open(player, "team-title", 3);
        button(
                m,
                11,
                Material.RED_BANNER,
                "<red>Attackers</red>",
                List.of("<gray>Defeat the King before time expires"),
                a -> p.game().team(a, "attackers"));
        button(
                m,
                15,
                Material.BLUE_BANNER,
                "<blue>Defenders</blue>",
                List.of("<gray>Protect the King until time expires"),
                a -> p.game().team(a, "defenders"));
        button(
                m,
                22,
                Material.COMPASS,
                "<yellow>Auto Balance</yellow>",
                List.of(),
                a -> p.game().team(a, "auto"));
        player.openInventory(m.inventory);
    }

    public void editor(Player player, String id) {
        p.require(player, "admin.setup");
        Menu m = open(player, "title", 6);
        int slot = 10;
        for (String spawn :
                List.of("waiting", "attackers", "defenders", "king", "spectator", "exit")) {
            button(
                    m,
                    slot++,
                    Material.ENDER_PEARL,
                    "<yellow>Set " + spawn + " spawn</yellow>",
                    List.of("<gray>Uses your current position and facing"),
                    a -> a.performCommand("siegewar arena setspawn " + id + " " + spawn));
        }
        String[] actions = {"capture", "validate", "enable", "disable", "start", "stop", "reset"};
        slot = 28;
        for (String action : actions)
            button(
                    m,
                    slot++,
                    Material.PAPER,
                    "<gold>" + action + "</gold>",
                    List.of("<gray>Arena: " + id),
                    a -> a.performCommand("siegewar arena " + action + " " + id));
        player.openInventory(m.inventory);
    }

    public void kits(Player player) {
        Game.Session s = p.game().require(player, false);
        Menu m = open(player, "kit-title", 4);
        int slot = 10;
        for (KitDefinition k : p.settings().kits.values())
            if (k.team().equals(s.engine.team(player.getUniqueId()).name())) {
                boolean owned = p.game().hasKit(player, k);
                button(
                        m,
                        slot++,
                        k.icon(),
                        k.displayName(),
                        List.of(
                                "<gray>Price: " + k.price() + " coins",
                                owned
                                        ? "<green>Click/tap to select"
                                        : "<gold>Click/tap to purchase"),
                        a -> {
                            if (p.game().hasKit(a, k)) p.game().select(a, k.id());
                            else confirm(a, k.id());
                        });
            }
        button(m, 31, Material.ARROW, "<white>Back</white>", List.of(), this::main);
        player.openInventory(m.inventory);
    }

    public void confirm(Player player, String id) {
        KitDefinition k = p.settings().kits.get(id);
        if (k == null) throw new IllegalArgumentException("Unknown kit: " + id);
        Menu m = open(player, "confirm-title", 3);
        UUID request = UUID.randomUUID();
        boolean[] submitted = {false};
        button(
                m,
                11,
                Material.LIME_STAINED_GLASS_PANE,
                "<green>Confirm purchase</green>",
                List.of("<gold>" + id, "<gray>Cost: " + k.price() + " coins"),
                who -> {
                    if (submitted[0]) return;
                    submitted[0] = true;
                    KitDefinition current = p.settings().kits.get(id);
                    if (current == null || current.price() != k.price())
                        throw new IllegalStateException(
                                "The price changed. Reopen the purchase menu.");
                    p.require(who, "use");
                    UUID uuid = who.getUniqueId();
                    String name = who.getName();
                    p.io(
                                    () -> {
                                        p.store().ensurePlayer(uuid, name);
                                        var result =
                                                p.store()
                                                        .purchase(
                                                                request, uuid, id, current.price());
                                        p.updateCachedOnIo(uuid);
                                        return result;
                                    })
                            .whenComplete(
                                    (result, error) ->
                                            p.sync(
                                                    () -> {
                                                        if (!who.isOnline() || Bukkit.getPlayer(uuid) != who) return;
                                                        if (error != null) {
                                                            p.error(who, error);
                                                            return;
                                                        }
                                                        switch (result.status()) {
                                                            case PURCHASED ->
                                                                    p.say(
                                                                            who,
                                                                            "purchase-success",
                                                                            Map.of(
                                                                                    "kit",
                                                                                    Text.of(
                                                                                            current
                                                                                                    .displayName()),
                                                                                    "coins",
                                                                                    result
                                                                                            .balance()));
                                                            case ALREADY_OWNED ->
                                                                    p.say(who, "purchase-owned");
                                                            case INSUFFICIENT_FUNDS ->
                                                                    p.say(
                                                                            who,
                                                                            "purchase-insufficient");
                                                        }
                                                    }));
                });
        button(m, 15, Material.RED_STAINED_GLASS_PANE, "<red>Cancel</red>", List.of(), who -> {});
        player.openInventory(m.inventory);
    }

    public void click(Player player, Menu m, int slot) {
        Game.Session session = p.game().session(player.getUniqueId());
        UUID generation = session == null ? null : session.generation;
        if (!m.owner.equals(player.getUniqueId()) || !Objects.equals(m.generation, generation)
                || m.rules != p.settings() || System.currentTimeMillis() > m.expires)
            throw new IllegalStateException("This menu has expired; reopen /siegewar");
        Consumer<Player> action = m.actions.get(slot);
        if (action == null || m.dispatched) return;
        m.dispatched = true;
        Bukkit.getScheduler()
                .runTask(
                        p,
                        () -> {
                            if (player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                                    && m.rules == p.settings()
                                    && Objects.equals(m.generation, p.game().session(player.getUniqueId()) == null
                                            ? null : p.game().session(player.getUniqueId()).generation)
                                    && player.getOpenInventory().getTopInventory().getHolder() == m
                                    && System.currentTimeMillis() <= m.expires)
                                try {
                                    player.closeInventory();
                                    action.accept(player);
                                } catch (Exception ex) {
                                    p.error(player, ex);
                                }
                        });
    }
}
