package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.core.MatchEngine.State;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public final class Events implements Listener {
    private final AbyssalDominionSiegeWarPlugin p;

    public Events(AbyssalDominionSiegeWarPlugin p) {
        this.p = p;
    }

    private boolean controlled(Player player) {
        return p.game().session(player.getUniqueId()) != null
                || p.snapshots().pending(player.getUniqueId());
    }

    private Player attacker(Entity e) {
        if (e instanceof Player player) return player;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        p.ensure(e.getPlayer());
        p.snapshots().restore(e.getPlayer());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        p.game().disconnect(e.getPlayer());
        p.items().forget(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getView().getTopInventory().getHolder() instanceof Views.Menu menu) {
            boolean denied = e.isCancelled();
            e.setCancelled(true);
            if (denied) return;
            if (e.getRawSlot() < 0 || e.getRawSlot() >= e.getView().getTopInventory().getSize())
                return;
            try {
                p.views().click(player, menu, e.getRawSlot());
            } catch (Exception ex) {
                p.error(player, ex);
            }
            return;
        }
        if (controlled(player)) {
            p.items().blocked(player, e.getCurrentItem());
            boolean internal = !p.snapshots().pending(player.getUniqueId())
                    && !(e instanceof InventoryCreativeEvent)
                    && e.getView().getTopInventory().getType() == InventoryType.CRAFTING
                    && e.getClickedInventory() == player.getInventory()
                    && e.getAction() != InventoryAction.CLONE_STACK
                    && !e.getAction().name().startsWith("DROP_")
                    && e.getAction() != InventoryAction.UNKNOWN
                    && !p.items().blocked(player, e.getCursor())
                    && !p.items().blocked(player, e.getCurrentItem());
            if (e.getHotbarButton() >= 0 && p.items().blocked(player,
                    player.getInventory().getItem(e.getHotbarButton()))) internal = false;
            if (!internal) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getView().getTopInventory().getHolder() instanceof Views.Menu) { e.setCancelled(true); return; }
        if (controlled(player) && (p.snapshots().pending(player.getUniqueId())
                || e.getView().getTopInventory().getType() != InventoryType.CRAFTING
                || e.getRawSlots().stream().anyMatch(slot -> slot < e.getView().getTopInventory().getSize())
                || p.items().blocked(player, e.getOldCursor()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drop(PlayerDropItemEvent e) {
        if (controlled(e.getPlayer()) || p.game().protectedArena(e.getItemDrop().getLocation()) != null
                || e.getItemDrop()
                        .getItemStack()
                        .getItemMeta()
                        .getPersistentDataContainer()
                        .has(p.game().generationKey, PersistentDataType.STRING))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void pickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player player && controlled(player))
            p.items().blocked(player, e.getItem().getItemStack());
        if (e.getEntity() instanceof Player player
                && (controlled(player)
                        || e.getItem()
                                .getItemStack()
                                .getItemMeta()
                                .getPersistentDataContainer()
                                .has(p.game().generationKey, PersistentDataType.STRING)))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void pickupArrow(PlayerPickupArrowEvent e) {
        if (controlled(e.getPlayer()) || p.game().issued(e.getArrow())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void swap(PlayerSwapHandItemsEvent e) {
        if (controlled(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void held(PlayerItemHeldEvent e) {
        if (controlled(e.getPlayer()))
            p.items().blocked(e.getPlayer(), e.getPlayer().getInventory().getItem(e.getNewSlot()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mode(PlayerGameModeChangeEvent e) {
        Game.Session s = p.game().session(e.getPlayer().getUniqueId());
        if (s != null && s.engine.state() == State.RUNNING
                && s.players.contains(e.getPlayer().getUniqueId())
                && !s.dead.contains(e.getPlayer().getUniqueId())
                && !p.snapshots().pending(e.getPlayer().getUniqueId())
                && e.getNewGameMode() != GameMode.SURVIVAL) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void item(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        if (!controlled(player)) return;
        if (p.snapshots().pending(player.getUniqueId()) || p.items().blocked(player, e.getItem())) {
            e.setCancelled(true);
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND) {
            if (!p.game().active(player)) e.setCancelled(true);
            if (e.getClickedBlock() != null) e.setUseInteractedBlock(Event.Result.DENY);
            return;
        }
        Game.Session s = p.game().session(player.getUniqueId());
        ItemStack item = e.getItem();
        if (e.getAction().isRightClick() && item != null && item.hasItemMeta()) {
            var tags = item.getItemMeta().getPersistentDataContainer();
            String generation = tags.get(p.game().generationKey, PersistentDataType.STRING);
            if (s != null && p.game().currentItem(player, item) && s.generation.toString().equals(generation)) {
                String action = tags.get(p.game().actionKey, PersistentDataType.STRING),
                        ability = p.abilities().id(item);
                if (action != null || ability != null) {
                    if (e.useItemInHand() == Event.Result.DENY) return;
                    e.setCancelled(true);
                    Bukkit.getScheduler()
                            .runTask(
                                    p,
                                    () -> {
                                        if (!player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player
                                                || p.game().session(player.getUniqueId()) != s
                                                || s.ending || p.snapshots().pending(player.getUniqueId())) return;
                                        try {
                                            if (action != null)
                                                switch (action) {
                                                    case "team" -> p.views().teams(player);
                                                    case "kits" -> p.views().kits(player);
                                                    case "leave" -> p.game().leave(player);
                                                    default -> {}
                                                }
                                            else p.abilities().use(player, ability);
                                        } catch (Exception ex) {
                                            p.error(player, ex);
                                        }
                                    });
                    return;
                }
            }
        }
        // Session players may use gear; containers remain sealed against snapshot replay.
        if (e.getClickedBlock() != null && !(e.getAction() == Action.LEFT_CLICK_BLOCK
                && p.game().mayBreak(player, e.getClickedBlock().getLocation())))
            e.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void consume(PlayerItemConsumeEvent e) {
        if (controlled(e.getPlayer()) && (!p.game().active(e.getPlayer())
                || !p.game().currentItem(e.getPlayer(), e.getItem())
                || p.items().blocked(e.getPlayer(), e.getItem()) || p.abilities().id(e.getItem()) != null))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void directDamage(EntityDamageByEntityEvent e) {
        Player source = attacker(e.getDamager());
        if (e.getDamager() instanceof Projectile projectile
                && ((p.game().issued(projectile) && !p.game().currentIssued(projectile))
                    || (source != null && controlled(source) && !p.game().issued(projectile)))) {
            e.setCancelled(true);
            projectile.remove();
            return;
        }
        Game.Session king = p.game().king(e.getEntity());
        if (king != null) {
            if (source == null
                    || !p.game().kingTarget(source, e.getEntity())
                    || e.getDamager() instanceof TNTPrimed) e.setCancelled(true);
            return;
        }
        if (e.getEntity() instanceof Player victim) {
            if (controlled(victim) || (source != null && controlled(source))) {
                if (source == null || !p.game().enemies(source, victim)) e.setCancelled(true);
            }
        } else if (source != null && controlled(source)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        Game.Session king = p.game().king(e.getEntity());
        if (king != null && !(e instanceof EntityDamageByEntityEvent)) {
            e.setCancelled(true);
            return;
        }
        if (!(e.getEntity() instanceof Player player) || !controlled(player)) return;
        if (!p.game().active(player)) {
            e.setCancelled(true);
            return;
        }
        // Vanilla applies armor, attributes, absorption, cancellation and lethal damage.
        // Real death events preserve attribution for lifesteal and other death listeners.
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void actualDeath(PlayerDeathEvent e) {
        if (controlled(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            e.setKeepInventory(true);
            e.setKeepLevel(true);
            p.game().death(e.getEntity(), e.getEntity().getKiller());
        }
    }

    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        Game.Session s = p.game().session(e.getPlayer().getUniqueId());
        if (s != null) {
            var a = p.game().definition(s.id);
            e.setRespawnLocation(Game.location(a.spawns().get("spectator")));
            Bukkit.getScheduler().runTask(p, () -> {
                if (e.getPlayer().isOnline() && Bukkit.getPlayer(e.getPlayer().getUniqueId()) == e.getPlayer()
                        && p.game().session(e.getPlayer().getUniqueId()) == s && !s.ending)
                    p.game().afterActualRespawn(e.getPlayer());
            });
        } else if (p.snapshots().pending(e.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(p, () -> p.snapshots().restore(e.getPlayer()));
        }
    }

    @EventHandler
    public void kingDeath(EntityDeathEvent e) {
        Game.Session s = p.game().king(e.getEntity());
        if (s != null) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            p.game().kingDead(s);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void combust(EntityCombustEvent e) {
        if (p.game().king(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void transform(EntityTransformEvent e) {
        if (p.game().king(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void projectile(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player who) {
            if (p.snapshots().pending(who.getUniqueId())) {
                e.setCancelled(true);
                return;
            }
            Game.Session s = p.game().session(who.getUniqueId());
            if (s != null) {
                if (!p.game().active(who)) {
                    e.setCancelled(true);
                    return;
                }
                e.getEntity()
                        .getPersistentDataContainer()
                        .set(
                                p.game().generationKey,
                                PersistentDataType.STRING,
                                s.generation.toString());
                e.getEntity()
                        .getPersistentDataContainer()
                        .set(
                                p.game().ownerKey,
                                PersistentDataType.STRING,
                                who.getUniqueId().toString());
                e.getEntity().getPersistentDataContainer().set(p.game().lifeKey,
                        PersistentDataType.STRING, s.lives.get(who.getUniqueId()).toString());
                try { p.game().trackEntity(s, e.getEntity(), who.getUniqueId()); }
                catch (IllegalStateException ex) { e.setCancelled(true); return; }
                e.getEntity().setPersistent(false);
                Bukkit.getScheduler().runTaskLater(p, e.getEntity()::remove, 1200L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hit(ProjectileHitEvent e) {
        p.abilities().projectileHit(e.getEntity(), e.getHitEntity());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void explosion(EntityExplodeEvent e) {
        if (e.getEntity()
                .getPersistentDataContainer()
                .has(p.game().generationKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
            return;
        }
        e.blockList()
                .removeIf(
                        b -> p.game().protectedArena(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockExplosion(BlockExplodeEvent e) {
        e.blockList()
                .removeIf(
                        b -> p.game().protectedArena(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void breakBlock(BlockBreakEvent e) {
        Game.Session s = p.game().session(e.getPlayer().getUniqueId());
        boolean protectedBlock = p.game().protectedArena(e.getBlock().getLocation()) != null;
        if (p.game().mayEdit(e.getPlayer(), e.getBlock().getLocation())) return;
        if (controlled(e.getPlayer()) || protectedBlock) {
            if (!p.game().mayBreak(e.getPlayer(), e.getBlock().getLocation()))
                e.setCancelled(true);
            else {
                e.setDropItems(false);
                e.setExpToDrop(0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void place(BlockPlaceEvent e) {
        if (p.game().mayEdit(e.getPlayer(), e.getBlock().getLocation())) return;
        if (controlled(e.getPlayer()) || p.game().protectedArena(e.getBlock().getLocation()) != null)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void wear(PlayerItemDamageEvent e) {
        if (controlled(e.getPlayer()) && !p.game().currentItem(e.getPlayer(), e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void shoot(EntityShootBowEvent e) {
        if (e.getEntity() instanceof Player player && controlled(player)) {
            if (!p.game().active(player) || p.items().blocked(player, e.getBow())) e.setCancelled(true);
            else if (!p.game().currentItem(player, e.getConsumable())) e.setConsumeItem(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void glide(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player player && controlled(player)
                && p.items().blacklisted(Material.ELYTRA)) {
            e.setCancelled(true); p.items().warn(player, Material.ELYTRA, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flight(PlayerToggleFlightEvent e) {
        Game.Session s = p.game().session(e.getPlayer().getUniqueId());
        if (e.isFlying() && s != null && s.engine.state() == State.RUNNING
                && !s.dead.contains(e.getPlayer().getUniqueId())
                && !s.spectators.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void effect(EntityPotionEffectEvent e) {
        if (e.getEntity() instanceof Player player && p.game().active(player) && e.getNewEffect() != null
                && p.settings().blacklistedEffects.contains(e.getNewEffect().getType())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void experience(PlayerExpChangeEvent e) {
        if (controlled(e.getPlayer())) e.setAmount(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void move(PlayerMoveEvent e) {
        if (p.snapshots().teleporting(e.getPlayer().getUniqueId())) return;
        if (p.snapshots().pending(e.getPlayer().getUniqueId())) {
            e.setTo(e.getFrom());
            return;
        }
        Game.Session s = p.game().session(e.getPlayer().getUniqueId());
        if (!p.game().mayEnter(e.getPlayer(), e.getTo())) {
            e.setTo(e.getFrom());
            return;
        }
        if (s != null
                && s.engine.state() == State.RUNNING
                && e.getTo() != null
                && !p.game().inside(p.game().definition(s.id), e.getTo())) e.setTo(e.getFrom());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void teleport(PlayerTeleportEvent e) {
        if (p.snapshots().teleporting(e.getPlayer().getUniqueId())) return;
        if (p.snapshots().pending(e.getPlayer().getUniqueId()) || !p.game().mayEnter(e.getPlayer(), e.getTo())) {
            e.setCancelled(true);
            return;
        }
        if (controlled(e.getPlayer())) {
            Game.Session s = p.game().session(e.getPlayer().getUniqueId());
            if (s == null
                    || e.getTo() == null
                    || !p.game().inside(p.game().definition(s.id), e.getTo())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void command(PlayerCommandPreprocessEvent e) {
        if (!controlled(e.getPlayer())) return;
        String command = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        org.bukkit.command.PluginCommand resolved = Bukkit.getPluginCommand(command);
        String plain = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        String[] parts = e.getMessage().substring(1).trim().split("\\s+", 3);
        boolean recovery = parts.length > 1 && Set.of("leave", "recover", "help").contains(parts[1].toLowerCase(Locale.ROOT));
        if (resolved == null || resolved.getPlugin() != p
                || (!recovery && !p.settings().general.getStringList("allowed-commands").contains(plain))) {
            e.setCancelled(true);
            p.tell(
                    e.getPlayer(),
                    "<yellow>Use /siegewar leave to return safely before running other"
                        + " commands.</yellow>");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void unload(WorldUnloadEvent e) {
        if (p.templates().busy(e.getWorld().getUID())
                || p.game().sessions().stream()
                        .anyMatch(
                                s ->
                                        (!s.all().isEmpty() || s.ending)
                                                && p.game()
                                                        .definition(s.id)
                                                        .bounds()
                                                        .world()
                                                        .equals(e.getWorld().getUID())))
            e.setCancelled(true);
    }
}
