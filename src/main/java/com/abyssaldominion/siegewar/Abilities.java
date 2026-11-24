package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.kit.AbilityDefinition;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.util.*;

public final class Abilities {
    private final AbyssalDominionSiegeWarPlugin p;

    public Abilities(AbyssalDominionSiegeWarPlugin p) {
        this.p = p;
    }

    public String id(ItemStack item) {
        return item == null || !item.hasItemMeta()
                ? null
                : item.getItemMeta()
                        .getPersistentDataContainer()
                        .get(p.game().abilityKey, PersistentDataType.STRING);
    }

    public void use(Player player, String id) {
        p.require(player, "use");
        Game.Session s = p.game().require(player, true);
        AbilityDefinition a = p.settings().abilities.get(id);
        if (a == null) throw new IllegalArgumentException("Unknown ability");
        boolean inKit =
                p.settings().kits.get(s.selected.get(player.getUniqueId())).items().stream()
                        .anyMatch(g -> g.ability().equals(id));
        if (!inKit)
            throw new IllegalStateException("Your selected kit does not provide that ability");
        if (!p.protection().pvp(player, player.getLocation()))
            throw new IllegalStateException("Abilities are blocked in this protected location");
        int slot = -1;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (id.equals(id(item)) && p.game().currentItem(player, item)
                    && !p.items().blocked(player, item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) throw new IllegalStateException("Your issued ability item is missing");
        UUID owner = player.getUniqueId();
        long now = System.currentTimeMillis();
        Map<String, Long> cooldown = s.cooldowns.computeIfAbsent(owner, k -> new HashMap<>());
        long before = cooldown.getOrDefault(id, 0L);
        if (before > now) {
            p.say(player, "ability-cooldown", Map.of("seconds", (before - now + 999) / 1000));
            return;
        }
        Map<String, Integer> charges = s.charges.computeIfAbsent(owner, k -> new HashMap<>());
        int remaining = charges.getOrDefault(id, a.charges());
        if (remaining == 0) throw new IllegalStateException("No charges remain");
        cooldown.put(id, now + a.cooldownSeconds() * 1000L);
        if (remaining > 0) charges.put(id, remaining - 1);
        try {
            switch (a.kind()) {
                case "BUFF" -> Game.effects(player, a.effects());
                case "HEAL" -> {
                    int hits = 0;
                    for (UUID ally : s.players) {
                        Player target = Bukkit.getPlayer(ally);
                        if (target == null
                                || !p.game().active(target)
                                || s.engine.team(ally) != s.engine.team(owner)
                                || !target.getWorld().equals(player.getWorld())
                                || target.getLocation().distanceSquared(player.getLocation())
                                        > a.radius() * a.radius()
                                || !player.hasLineOfSight(target)) continue;
                        heal(target, a.damage());
                        if (++hits >= a.maxTargets()) break;
                    }
                }
                case "STEW" -> {
                    if (a.damage() > 0) heal(player, a.damage());
                    Game.effects(player, a.effects());
                }
                case "ATTACK" -> {
                    RayTraceResult hit =
                            player.getWorld()
                                    .rayTraceEntities(
                                            player.getEyeLocation(),
                                            player.getEyeLocation().getDirection(),
                                            a.radius(),
                                            0.5,
                                            e ->
                                                    e instanceof Player t
                                                            && p.game().enemies(player, t));
                    if (hit == null
                            || !(hit.getHitEntity() instanceof Player target)
                            || !player.hasLineOfSight(target))
                        throw new IllegalStateException("Aim at an enemy within range");
                    target.damage(a.damage(), player);
                    target.getWorld()
                            .spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 8);
                }
                case "AXE" -> {
                    Snowball axe =
                            player.launchProjectile(
                                    Snowball.class,
                                    player.getEyeLocation().getDirection().multiply(1.7));
                    axe.setItem(new ItemStack(a.material()));
                    tag(axe, s, player, id);
                    Bukkit.getScheduler()
                            .runTaskLater(p, axe::remove, Math.max(20, a.durationTicks()));
                }
                case "TNT" -> {
                    TNTPrimed tnt =
                            player.getWorld().spawn(player.getEyeLocation(), TNTPrimed.class);
                    tag(tnt, s, player, id);
                    tnt.setSource(player);
                    tnt.setVelocity(player.getEyeLocation().getDirection().multiply(0.9));
                    tnt.setYield(0);
                    tnt.setFuseTicks(Math.max(1, a.fuseTicks()) + 4);
                    Bukkit.getScheduler()
                            .runTaskLater(
                                    p,
                                    () -> {
                                        if (!tnt.isValid() || !p.game().currentIssued(tnt)) {
                                            tnt.remove();
                                            return;
                                        }
                                        Location at = tnt.getLocation();
                                        tnt.remove();
                                        if (p.game().session(owner) != s
                                                || !p.game().active(player)
                                                || !p.game().inside(p.game().definition(s.id), at))
                                            return;
                                        blast(player, s, at, a);
                                    },
                                    Math.max(1, a.fuseTicks()));
                }
                default ->
                        throw new IllegalArgumentException("Unsupported ability kind " + a.kind());
            }
            if (remaining > 0) {
                ItemStack used = player.getInventory().getItem(slot);
                if (used != null) {
                    used.setAmount(used.getAmount() - 1);
                    player.getInventory().setItem(slot, used.getAmount() > 0 ? used : null);
                }
            }
            p.tell(player, "<aqua>Used <ability>.</aqua>", Map.of("ability", id.replace('_', ' ')));
        } catch (RuntimeException error) {
            if (before == 0) cooldown.remove(id);
            else cooldown.put(id, before);
            charges.put(id, remaining);
            throw error;
        }
    }

    private void tag(Entity e, Game.Session s, Player player, String ability) {
        e.getPersistentDataContainer()
                .set(p.game().generationKey, PersistentDataType.STRING, s.generation.toString());
        e.getPersistentDataContainer()
                .set(p.game().ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
        e.getPersistentDataContainer().set(p.game().abilityKey, PersistentDataType.STRING, ability);
        e.getPersistentDataContainer().set(p.game().lifeKey, PersistentDataType.STRING,
                s.lives.get(player.getUniqueId()).toString());
        p.game().trackEntity(s, e, player.getUniqueId());
        e.setPersistent(false);
    }

    private void blast(Player owner, Game.Session s, Location at, AbilityDefinition a) {
        if (!p.protection().explosion(owner, at)) return;
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 1);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1, 1);
        int count = 0;
        for (UUID id : s.players) {
            Player target = Bukkit.getPlayer(id);
            if (target != null
                    && p.game().enemies(owner, target)
                    && target.getWorld().equals(at.getWorld())
                    && target.getLocation().distanceSquared(at) <= a.radius() * a.radius()
                    && owner.hasLineOfSight(target)) {
                target.damage(a.damage(), owner);
                if (++count >= a.maxTargets()) break;
            }
        }
        if (a.terrainDamage() && p.settings().flag("allow-block-edit", true)) {
            int r = (int) Math.min(4, Math.ceil(a.radius())), changed = 0;
            outer:
            for (int x = -r; x <= r; x++)
                for (int y = -r; y <= r; y++)
                    for (int z = -r; z <= r; z++) {
                        Location l = at.clone().add(x, y, z);
                        if (x * x + y * y + z * z > r * r
                                || !p.game().inside(p.game().definition(s.id), l)
                                || !p.protection().build(owner, l)) continue;
                        if (!l.getBlock().getType().isAir()
                                && l.getBlock().getType() != Material.BEDROCK) {
                            if (owner.breakBlock(l.getBlock()) && ++changed >= 256) break outer;
                        }
                    }
        }
    }

    private void heal(Player target, double amount) {
        target.heal(amount);
        target.getWorld().spawnParticle(Particle.HEART, target.getLocation().add(0, 1, 0), 4);
    }

    public void projectileHit(Projectile projectile, Entity hit) {
        String ability =
                projectile
                        .getPersistentDataContainer()
                        .get(p.game().abilityKey, PersistentDataType.STRING);
        if (ability == null || !(projectile.getShooter() instanceof Player owner)) return;
        AbilityDefinition a = p.settings().abilities.get(ability);
        Game.Session s = p.game().session(owner.getUniqueId());
        if (a != null
                && a.kind().equals("AXE")
                && p.game().currentIssued(projectile)
                && s != null
                && s.generation
                        .toString()
                        .equals(
                                projectile
                                        .getPersistentDataContainer()
                                        .get(p.game().generationKey, PersistentDataType.STRING))
                && hit instanceof Player target
                && p.game().enemies(owner, target)) target.damage(a.damage(), owner);
        projectile.remove();
    }
}
