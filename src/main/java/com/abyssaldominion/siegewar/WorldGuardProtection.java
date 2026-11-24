package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.runtime.ArenaRepository.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.util.*;

public final class WorldGuardProtection implements Protection {
    private final Path journal;

    public WorldGuardProtection(Path dataFolder) { journal = dataFolder.resolve("protection-recovery"); }

    public void recover() {
        if (!Files.isDirectory(journal)) return;
        try (var files = Files.newDirectoryStream(journal, "*.yml")) {
            for (Path file : files) restore(file);
        } catch (Exception ex) { throw new IllegalStateException("WorldGuard flag recovery failed", ex); }
    }

    public void begin(Arena arena) {
        // Only manage the exact cuboid created by this plugin. Other regions retain their policies.
        if (!arena.region().equals("asw_" + arena.id())) return;
        RegionManager manager = manager(Bukkit.getWorld(arena.bounds().world()));
        var region = manager == null ? null : manager.getRegion(arena.region());
        if (!(region instanceof ProtectedCuboidRegion)
                || !region.getMinimumPoint().equals(BlockVector3.at(arena.bounds().minX(), arena.bounds().minY(), arena.bounds().minZ()))
                || !region.getMaximumPoint().equals(BlockVector3.at(arena.bounds().maxX(), arena.bounds().maxY(), arena.bounds().maxZ())))
            throw new IllegalStateException("Managed WorldGuard cuboid differs from arena bounds");
        Path file = journal.resolve(arena.id() + ".yml");
        if (Files.exists(file)) throw new IllegalStateException("WorldGuard flag recovery is pending");
        StateFlag.State previous = region.getFlag(Flags.BLOCK_BREAK);
        try {
            Files.createDirectories(journal);
            YamlConfiguration data = new YamlConfiguration();
            data.set("world", arena.bounds().world().toString());
            data.set("region", arena.region());
            data.set("block-break", previous == null ? "UNSET" : previous.name());
            Path temp = file.resolveSibling(arena.id() + ".pending");
            data.save(temp.toFile());
            try (var channel = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.ALLOW);
            manager.save();
        } catch (Exception ex) {
            region.setFlag(Flags.BLOCK_BREAK, previous);
            throw new IllegalStateException("Could not persist match block policy", ex);
        }
    }

    public void end(Arena arena) {
        Path file = journal.resolve(arena.id() + ".yml");
        if (Files.exists(file)) restore(file);
    }

    private void restore(Path file) {
        try {
            String id = file.getFileName().toString().replaceFirst("\\.yml$", "");
            Settings.validId(id);
            YamlConfiguration data = new YamlConfiguration(); data.load(file.toFile());
            String name = Objects.requireNonNull(data.getString("region"));
            if (!name.equals("asw_" + id)) throw new IllegalArgumentException("Unexpected managed region");
            RegionManager manager = manager(Bukkit.getWorld(UUID.fromString(data.getString("world"))));
            var region = manager == null ? null : manager.getRegion(name);
            if (region == null) throw new IllegalStateException("Load the recovery world and WorldGuard region first: " + name);
            String value = data.getString("block-break", "UNSET");
            region.setFlag(Flags.BLOCK_BREAK, value.equals("UNSET") ? null : StateFlag.State.valueOf(value));
            manager.save();
            Files.delete(file);
        } catch (Exception ex) { throw new IllegalStateException("Could not restore WorldGuard flags from " + file.getFileName(), ex); }
    }
    private RegionManager manager(World w) {
        return w == null
                ? null
                : WorldGuard.getInstance()
                        .getPlatform()
                        .getRegionContainer()
                        .get(BukkitAdapter.adapt(w));
    }

    public boolean regionExists(Arena a) {
        RegionManager m = a.bounds() == null ? null : manager(Bukkit.getWorld(a.bounds().world()));
        return m != null && !a.region().isBlank() && m.hasRegion(a.region());
    }

    public boolean pvp(Player p, Location l) {
        return check(p, l, Flags.PVP);
    }

    public boolean build(Player p, Location l) {
        return check(p, l, Flags.BLOCK_BREAK);
    }

    public boolean explosion(Player p, Location l) {
        return check(p, l, Flags.TNT);
    }

    private boolean check(Player p, Location l, StateFlag flag) {
        if (l == null || l.getWorld() == null) return false;
        // Match rules never inherit operator/WorldGuard bypass privileges.
        return WorldGuard.getInstance()
                .getPlatform()
                .getRegionContainer()
                .createQuery()
                .testState(BukkitAdapter.adapt(l), WorldGuardPlugin.inst().wrapPlayer(p), flag);
    }

    public void create(Arena a) {
        Bounds b = a.bounds();
        World w = Bukkit.getWorld(b.world());
        RegionManager m = manager(w);
        if (m == null) throw new IllegalStateException("WorldGuard region data is unavailable");
        if (m.hasRegion(a.region())) return;
        ProtectedCuboidRegion r =
                new ProtectedCuboidRegion(
                        a.region(),
                        BlockVector3.at(b.minX(), b.minY(), b.minZ()),
                        BlockVector3.at(b.maxX(), b.maxY(), b.maxZ()));
        r.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        r.setFlag(Flags.BUILD, StateFlag.State.DENY);
        r.setFlag(Flags.MOB_SPAWNING, StateFlag.State.ALLOW);
        r.setPriority(10);
        m.addRegion(r);
        try {
            m.save();
        } catch (Exception e) {
            m.removeRegion(r.getId());
            throw new IllegalStateException("Could not save WorldGuard region", e);
        }
    }
}
