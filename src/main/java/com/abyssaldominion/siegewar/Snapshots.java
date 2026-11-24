package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.runtime.recovery.*;
import com.abyssaldominion.siegewar.store.SiegeStore.*;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

/** All gates are main-thread owned. Durable escrow precedes all match inventory changes. */
public final class Snapshots {
    private final AbyssalDominionSiegeWarPlugin plugin;
    private final Map<UUID, UUID> pending = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();
    private final Map<UUID, Recovery> restoring = new HashMap<>();
    private final Map<UUID, String> reported = new HashMap<>();
    private record Recovery(Player player, UUID request, CompletableFuture<Void> done) {}
    private record Restoration(Escrow escrow, PlayerSnapshot snapshot) {}

    public Snapshots(AbyssalDominionSiegeWarPlugin p) { plugin = p; }
    public boolean pending(UUID id) { return pending.containsKey(id); }
    public UUID request(UUID id) { return pending.get(id); }
    public boolean hasPending() { return !pending.isEmpty(); }
    public int pendingCount() { return pending.size(); }
    public boolean teleporting(UUID id) { return teleporting.contains(id); }
    public boolean current(Escrow e) { return e.session().equals(pending.get(e.player())); }
    private boolean connection(Player p) {
        return p.isOnline() && Bukkit.getPlayer(p.getUniqueId()) == p;
    }
    private boolean current(Recovery r) {
        return restoring.get(r.player().getUniqueId()) == r
                && r.request().equals(pending.get(r.player().getUniqueId()));
    }
    public PlayerSnapshot capture(Player p, UUID session) {
        p.closeInventory();
        Location l = p.getLocation();
        List<PlayerSnapshot.Effect> effects =
                p.getActivePotionEffects().stream()
                        .map(
                                e ->
                                        new PlayerSnapshot.Effect(
                                                e.getType().getKey().toString(),
                                                e.getDuration(),
                                                e.getAmplifier(),
                                                e.isAmbient(),
                                                e.hasParticles(),
                                                e.hasIcon()))
                        .toList();
        return new PlayerSnapshot(
                p.getUniqueId(),
                session,
                l.getWorld().getUID(),
                l.getWorld().getName(),
                l.getX(),
                l.getY(),
                l.getZ(),
                l.getYaw(),
                l.getPitch(),
                ItemStack.serializeItemsAsBytes(p.getInventory().getContents()),
                ItemStack.serializeItemsAsBytes(new ItemStack[] {p.getItemOnCursor()}),
                p.getInventory().getHeldItemSlot(),
                p.getGameMode().name(),
                p.getLevel(),
                p.getExp(),
                p.getTotalExperience(),
                p.getHealth(),
                p.getAbsorptionAmount(),
                p.getFoodLevel(),
                p.getSaturation(),
                p.getExhaustion(),
                p.getAllowFlight(),
                p.isFlying(),
                p.getFlySpeed(),
                p.getWalkSpeed(),
                p.getFireTicks(),
                p.getFreezeTicks(),
                p.getRemainingAir(),
                p.getFallDistance(),
                p.isInvulnerable(),
                p.isCollidable(),
                effects);
    }


    public CompletableFuture<Escrow> prepare(Player player, String arena, UUID generation) {
        UUID request = UUID.randomUUID(), id = player.getUniqueId();
        if (pending.putIfAbsent(id, request) != null)
            throw new IllegalStateException("Your previous action is still pending");
        PlayerSnapshot snapshot;
        try { snapshot = capture(player, request); }
        catch (RuntimeException ex) { pending.remove(id, request); throw ex; }
        String name = player.getName();
        return plugin.io(() -> {
            plugin.store().ensurePlayer(id, name);
            Escrow e = new Escrow(id, request, generation, arena, Phase.CAPTURED,
                    1, SnapshotCodec.encode(snapshot));
            plugin.store().capture(e);
            if (!plugin.store().phase(id, request, Phase.CAPTURED, Phase.ACTIVE))
                throw new IllegalStateException("Snapshot activation was not committed");
            return new Escrow(id, request, generation, arena, Phase.ACTIVE, 1, e.snapshot());
        });
    }

    public void prepared(UUID id) { pending.remove(id); }

    /** No items changed; keep the gate until the captured snapshot is durably consumed. */
    public CompletableFuture<Void> cancelUnchanged(Escrow e) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        plugin.io(() -> {
            if (!plugin.store().phase(e.player(), e.session(), e.phase(), Phase.RESTORING))
                throw new IllegalStateException("Snapshot cancellation was superseded");
            completeEscrow(e);
            return null;
        }).whenComplete((v, error) -> plugin.sync(() -> {
            if (error == null) {
                pending.remove(e.player(), e.session());
                done.complete(null);
            } else {
                // A failed cancellation must never unlock an unconsumed snapshot.
                Player online = Bukkit.getPlayer(e.player());
                if (online != null && current(e)) restore(online);
                done.completeExceptionally(error);
            }
        }));
        return done;
    }

    public boolean teleport(Player player, Location at) {
        teleporting.add(player.getUniqueId());
        try { return player.teleport(at); }
        finally { teleporting.remove(player.getUniqueId()); }
    }

    public CompletableFuture<Void> restore(Player player) {
        UUID id = player.getUniqueId();
        Recovery previous = restoring.get(id);
        if (previous != null && previous.player() == player) return previous.done();
        if (!connection(player))
            return CompletableFuture.failedFuture(new IllegalStateException("Recovery deferred until login"));
        Recovery r = new Recovery(player, UUID.randomUUID(), new CompletableFuture<>());
        restoring.put(id, r);
        pending.put(id, r.request());
        if (previous != null) previous.done().completeExceptionally(
                new IllegalStateException("Recovery superseded by a new connection"));
        plugin.io(() -> {
            Optional<Escrow> optional = plugin.store().escrow(id);
            if (optional.isEmpty() || optional.get().phase() == Phase.RESTORED) return null;
            Escrow e = optional.get();
            PlayerSnapshot s = checked(e);
            if (e.phase() != Phase.RESTORING
                    && !plugin.store().phase(id, e.session(), e.phase(), Phase.RESTORING))
                throw new IllegalStateException("Snapshot transition was superseded");
            return new Restoration(e, s);
        }).whenComplete((result, error) -> plugin.sync(() -> {
            if (!current(r)) { r.done().completeExceptionally(new IllegalStateException("Superseded recovery")); return; }
            if (!connection(player)) { finish(r, new IllegalStateException("Recovery deferred until login"), false); return; }
            if (error != null) { fail(r, error); return; }
            if (result == null) { finish(r, null, true); return; }
            try {
                if (player.isDead()) throw new IllegalStateException("Recovery will resume after respawn");
                World world = Bukkit.getWorld(result.snapshot().world());
                if (world == null) throw new IllegalStateException("Return world is not loaded: " + result.snapshot().worldName());
                if (!teleport(player, new Location(world, result.snapshot().x(), result.snapshot().y(),
                        result.snapshot().z(), result.snapshot().yaw(), result.snapshot().pitch())))
                    throw new IllegalStateException("Return teleport was cancelled");
                // World managers can defer gamemode/inventory changes until after teleport.
                // The player remains quarantined until their source state is saved and consumed.
                Bukkit.getScheduler().runTaskLater(plugin, () -> finishReturn(r, result), 3L);
            } catch (Exception ex) { fail(r, ex); }
        }));
        return r.done();
    }


    private void finishReturn(Recovery r, Restoration result) {
        Player player = r.player();
        if (!current(r)) { r.done().completeExceptionally(new IllegalStateException("Superseded recovery")); return; }
        if (!connection(player)) { finish(r, new IllegalStateException("Recovery deferred until login"), false); return; }
        try {
            if (player.isDead() || !player.getWorld().getUID().equals(result.snapshot().world()))
                throw new IllegalStateException("Return destination changed before recovery completed");
            applyContents(player, result.snapshot());
            player.saveData();
            Path root = Bukkit.getWorlds().getFirst().getWorldFolder().toPath().toAbsolutePath();
            plugin.io(() -> {
                forcePlayerData(root, player.getUniqueId());
                completeEscrow(result.escrow());
                return null;
            }).whenComplete((v, error) -> plugin.sync(() -> {
                if (!current(r)) { r.done().completeExceptionally(new IllegalStateException("Superseded recovery")); return; }
                if (error != null) { fail(r, error); return; }
                finish(r, null, true);
                if (connection(player)) plugin.say(player, "left");
            }));
        } catch (Exception ex) { fail(r, ex); }
    }

    /** Paper 26.x stores player data under players/data; older layouts used playerdata. */
    private void forcePlayerData(Path root, UUID id) throws IOException {
        Path folder = root;
        for (int depth = 0; folder != null && depth < 6; depth++, folder = folder.getParent()) {
            for (String sub : List.of("players/data", "playerdata")) {
                Path data = folder.resolve(sub).resolve(id + ".dat");
                if (!Files.isRegularFile(data)) continue;
                try (FileChannel channel = FileChannel.open(data, StandardOpenOption.WRITE)) { channel.force(true); }
                return;
            }
        }
        throw new IOException("Saved player data was not found; recovery retained");
    }

    private PlayerSnapshot checked(Escrow e) throws IOException {
        PlayerSnapshot s = SnapshotCodec.decode(e.snapshot());
        if (!s.player().equals(e.player()) || !s.session().equals(e.session()))
            throw new IOException("Snapshot identity mismatch");
        return s;
    }

    private void completeEscrow(Escrow e) {
        if (plugin.store().phase(e.player(), e.session(), Phase.RESTORING, Phase.RESTORED)) return;
        // A synchronous quit/shutdown recovery may have committed the same head first.
        Escrow current = plugin.store().escrow(e.player()).orElseThrow();
        if (!current.session().equals(e.session()) || current.phase() != Phase.RESTORED)
            throw new IllegalStateException("Recovery completion was not committed");
    }

    private void finish(Recovery r, Throwable error, boolean unlock) {
        UUID id = r.player().getUniqueId();
        if (current(r)) {
            restoring.remove(id);
            if (unlock || !connection(r.player())) pending.remove(id, r.request());
        }
        if (error == null) { reported.remove(id); r.done().complete(null); }
        else r.done().completeExceptionally(error);
    }

    private void fail(Recovery r, Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (current(r) && connection(r.player())) {
            String signature = cause.getClass().getName() + ":" + cause.getMessage();
            if (!signature.equals(reported.put(r.player().getUniqueId(), signature))) {
                plugin.getLogger().log(cause instanceof IllegalStateException ? Level.WARNING : Level.SEVERE,
                        "Recovery retained for " + r.player().getUniqueId() + ": " + cause.getMessage(),
                        cause instanceof IllegalStateException ? null : cause);
                plugin.say(r.player(), "recovery-failed");
            }
        }
        finish(r, error, false);
    }

    /** Prevent callbacks from an old connection from applying state to a new player instance. */
    public void disconnected(Player player) {
        UUID id = player.getUniqueId();
        Recovery r = restoring.remove(id);
        pending.remove(id);
        if (r != null) r.done().completeExceptionally(new IllegalStateException("Recovery deferred until login"));
    }
    private void apply(Player p, PlayerSnapshot s, World w) {
        if (!teleport(p, new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch())))
            throw new IllegalStateException("Return teleport was cancelled");
        applyContents(p, s);
    }

    private void applyContents(Player p, PlayerSnapshot s) {
        ItemStack[] inventory = ItemStack.deserializeItemsFromBytes(s.inventory());
        ItemStack[] cursor = ItemStack.deserializeItemsFromBytes(s.cursor());
        p.closeInventory();
        p.getInventory().clear();
        p.getInventory().setContents(inventory);
        p.setItemOnCursor(cursor.length == 0 ? null : cursor[0]);
        p.getInventory().setHeldItemSlot(s.heldSlot());
        p.setGameMode(GameMode.valueOf(s.gameMode()));
        p.setTotalExperience(s.totalExp());
        p.setLevel(s.level());
        p.setExp(s.exp());
        p.setFoodLevel(s.food());
        p.setSaturation(s.saturation());
        p.setExhaustion(s.exhaustion());
        p.setAllowFlight(s.allowFlight());
        p.setFlying(s.allowFlight() && s.flying());
        p.setFlySpeed(s.flySpeed());
        p.setWalkSpeed(s.walkSpeed());
        p.setFireTicks(s.fireTicks());
        p.setFreezeTicks(s.freezeTicks());
        p.setRemainingAir(s.remainingAir());
        p.setFallDistance(s.fallDistance());
        p.setInvulnerable(s.invulnerable());
        p.setCollidable(s.collidable());
        for (PotionEffect effect : p.getActivePotionEffects())
            p.removePotionEffect(effect.getType());
        for (PlayerSnapshot.Effect e : s.effects()) {
            PotionEffectType type = Registry.MOB_EFFECT.get(NamespacedKey.fromString(e.type()));
            if (type != null)
                p.addPotionEffect(
                        new PotionEffect(
                                type,
                                e.ticks(),
                                e.amplifier(),
                                e.ambient(),
                                e.particles(),
                                e.icon()));
        }
        p.setHealth(
                Math.min(
                        p.getAttribute(Attribute.MAX_HEALTH).getValue(),
                        Math.max(0.1, s.health())));
        p.setAbsorptionAmount(s.absorption());

    }


    /** Quit/shutdown cannot depend on a future main-thread scheduler callback. */
    public void restoreNow(Player player) throws Exception {
        Optional<Escrow> optional = plugin.store().escrow(player.getUniqueId());
        if (optional.isEmpty() || optional.get().phase() == Phase.RESTORED) return;
        Escrow e = optional.get();
        PlayerSnapshot s = checked(e);
        World world = Bukkit.getWorld(s.world());
        if (world == null) throw new IllegalStateException("Return world is not loaded");
        if (player.isDead()) throw new IllegalStateException("Recovery deferred until respawn");
        if (e.phase() != Phase.RESTORING
                && !plugin.store().phase(e.player(), e.session(), e.phase(), Phase.RESTORING))
            throw new IllegalStateException("Recovery was superseded");
        apply(player, s, world);
        player.saveData();
        forcePlayerData(Bukkit.getWorlds().getFirst().getWorldFolder().toPath().toAbsolutePath(), player.getUniqueId());
        completeEscrow(e);
        pending.remove(player.getUniqueId());
    }
}
