package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.runtime.ArenaRepository.*;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;

import org.bukkit.*;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Each job uses a fresh FAWE session, closes/flushes it, then completes its future. */
public final class Templates implements AutoCloseable {
    private final AbyssalDominionSiegeWarPlugin plugin;
    private final ExecutorService worker;
    private final Map<UUID, Integer> leased = new ConcurrentHashMap<>();

    public Templates(AbyssalDominionSiegeWarPlugin plugin) {
        this.plugin = plugin;
        int limit = plugin.settings().integer("max-concurrent-resets", 1, 16, 1);
        worker = new ThreadPoolExecutor(limit, limit, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), Thread.ofPlatform().name("siege-fawe-").factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void acquire(World world) {
        int limit = plugin.settings().integer("max-concurrent-resets", 1, 16, 1);
        if (leased.size() >= limit || leased.putIfAbsent(world.getUID(), 1) != null)
            throw new IllegalStateException("Template/reset budget is busy; try again shortly");
    }

    private CompletableFuture<Void> submit(World world, Runnable job) {
        try { return CompletableFuture.runAsync(job, worker); }
        catch (RejectedExecutionException ex) {
            release(world.getUID());
            throw new IllegalStateException("Template worker is busy or stopping", ex);
        }
    }

    public Path file(String id) {
        Settings.validId(id);
        return plugin.getDataFolder().toPath().resolve("schematics").resolve(id + ".schem");
    }

    public boolean busy(UUID world) {
        return leased.containsKey(world);
    }

    private void release(UUID world) {
        leased.computeIfPresent(world, (id, count) -> count <= 1 ? null : count - 1);
    }

    public boolean exists(Arena a) {
        return Files.isRegularFile(file(a.id()));
    }

    public Bounds selection(Player p) throws Exception {
        Region r;
        try {
            r = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(p))
                    .getSelection(BukkitAdapter.adapt(p.getWorld()));
        } catch (com.sk89q.worldedit.IncompleteRegionException ex) {
            throw new IllegalArgumentException("Select both FAWE corners first, or use /siegewar arena setbounds <id> x1 y1 z1 x2 y2 z2");
        }
        BlockVector3 n = r.getMinimumPoint(), x = r.getMaximumPoint();
        return new Bounds(
                p.getWorld().getUID(),
                p.getWorld().getName(),
                n.x(),
                n.y(),
                n.z(),
                x.x(),
                x.y(),
                x.z());
    }

    private CuboidRegion region(Bounds b, World w) {
        return new CuboidRegion(
                BukkitAdapter.adapt(w),
                BlockVector3.at(b.minX(), b.minY(), b.minZ()),
                BlockVector3.at(b.maxX(), b.maxY(), b.maxZ()));
    }

    private World validate(Arena a) {
        Bounds b =
                Objects.requireNonNull(a.bounds(), "Set bounds or select a cuboid with FAWE first");
        long cap = plugin.settings().general.getLong("max-arena-blocks", 2000000);
        if (b.volume() < 1 || b.volume() > cap)
            throw new IllegalArgumentException("Arena exceeds " + cap + " blocks");
        World w = Bukkit.getWorld(b.world());
        if (w == null) throw new IllegalStateException("Arena world is not loaded");
        if (b.minY() < w.getMinHeight() || b.maxY() >= w.getMaxHeight())
            throw new IllegalArgumentException("Bounds exceed world build height");
        return w;
    }

    public CompletableFuture<Void> capture(Arena a) {
        World w = validate(a);
        acquire(w);
        com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(w);
        CuboidRegion r = region(a.bounds(), w);
        Path target = file(a.id());
        return submit(w,
                () -> {
                    Path tmp = target.resolveSibling(a.id() + ".pending.schem");
                    try {
                        Files.createDirectories(target.getParent());
                        BlockArrayClipboard clip = new BlockArrayClipboard(r);
                        clip.setOrigin(r.getMinimumPoint());
                        try (EditSession edit = WorldEdit.getInstance().newEditSession(world)) {
                            ForwardExtentCopy copy =
                                    new ForwardExtentCopy(edit, r, clip, r.getMinimumPoint());
                            copy.setCopyingEntities(false);
                            copy.setCopyingBiomes(false);
                            Operations.complete(copy);
                        }
                        try (OutputStream out = Files.newOutputStream(tmp);
                                ClipboardWriter writer =
                                        BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(out)) {
                            writer.write(clip);
                        }
                        if (Files.size(tmp) > plugin.settings().general.getLong("max-schematic-bytes", 67108864L))
                            throw new IOException("Captured schematic exceeds byte budget");
                        Files.move(
                                tmp,
                                target,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    } finally {
                        release(w.getUID());
                        try {
                            Files.deleteIfExists(tmp);
                        } catch (IOException ignored) {
                        }
                    }
                });
    }

    public CompletableFuture<Void> reset(Arena a) {
        World w = validate(a);
        Path target = file(a.id());
        if (!exists(a)) throw new IllegalStateException("Capture an arena template first");
        acquire(w);
        com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(w);
        Bounds b = a.bounds();
        Path marker = target.resolveSibling(a.id() + ".resetting");
        return submit(w,
                () -> {
                    try {
                        Files.writeString(
                                marker,
                                "Reset in progress; complete another reset before enabling.");
                        if (Files.size(target)
                                > plugin.settings()
                                        .general
                                        .getLong("max-schematic-bytes", 67108864L))
                            throw new IOException("Schematic exceeds byte budget");
                        var format = ClipboardFormats.findByFile(target.toFile());
                        if (format == null) throw new IOException("Unsupported schematic format");
                        Clipboard clip;
                        try (InputStream in = Files.newInputStream(target);
                                ClipboardReader reader = format.getReader(in)) {
                            clip = reader.read();
                        }
                        BlockVector3 dims = clip.getDimensions();
                        if (dims.x() != b.maxX() - b.minX() + 1
                                || dims.y() != b.maxY() - b.minY() + 1
                                || dims.z() != b.maxZ() - b.minZ() + 1)
                            throw new IOException(
                                    "Template dimensions differ from saved reset bounds; recapture"
                                        + " required");
                        // Normalize the origin so a manipulated clipboard offset cannot write
                        // outside the cuboid.
                        clip.setOrigin(clip.getRegion().getMinimumPoint());
                        try (EditSession edit = WorldEdit.getInstance().newEditSession(world);
                                ClipboardHolder holder = new ClipboardHolder(clip)) {
                            Operations.complete(
                                    holder.createPaste(edit)
                                            .to(BlockVector3.at(b.minX(), b.minY(), b.minZ()))
                                            .ignoreAirBlocks(false)
                                            .copyEntities(false)
                                            .copyBiomes(false)
                                            .build());
                        }
                        Files.deleteIfExists(marker);
                        Files.deleteIfExists(target.resolveSibling(a.id() + ".dirty"));
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    } finally {
                        release(w.getUID());
                    }
                });
    }

    public boolean interrupted(Arena a) {
        return Files.exists(file(a.id()).resolveSibling(a.id() + ".resetting"))
                || Files.exists(file(a.id()).resolveSibling(a.id() + ".dirty"));
    }

    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(60, TimeUnit.SECONDS))
                plugin.getLogger()
                        .warning("FAWE job still active; reset marker retained for recovery");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
