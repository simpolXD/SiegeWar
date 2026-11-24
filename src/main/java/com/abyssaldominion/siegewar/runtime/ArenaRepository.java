/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.yaml.snakeyaml.LoaderOptions
 *  org.yaml.snakeyaml.Yaml
 *  org.yaml.snakeyaml.constructor.BaseConstructor
 *  org.yaml.snakeyaml.constructor.SafeConstructor
 */
package com.abyssaldominion.siegewar.runtime;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.BaseConstructor;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public final class ArenaRepository {
    private final Path file;
    private Map<String, Arena> arenas = Map.of();
    private Point lobby;

    public ArenaRepository(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (Files.exists(file, new LinkOption[0])) {
            this.read();
        }
    }

    public Map<String, Arena> all() {
        return this.arenas;
    }

    public Optional<Point> lobby() {
        return Optional.ofNullable(this.lobby);
    }

    public void setLobby(Point p) throws IOException {
        this.persist(this.arenas, p);
        this.lobby = p;
    }

    public void create(String id) throws IOException {
        if (this.arenas.containsKey(id)) {
            throw new IllegalArgumentException("Arena exists");
        }
        this.save(new Arena(id, false, "NORMAL", 15, 300, null, Map.of(), "", "", ""));
    }

    public List<String> validate(String id) {
        Arena a = this.arenas.get(id);
        if (a == null) {
            return List.of("Arena '" + id + "' does not exist");
        }
        ArrayList<String> errors = new ArrayList<String>();
        if (a.bounds == null) {
            errors.add("No reset bounds configured");
        }
        for (String key :
                List.of("waiting", "attackers", "defenders", "king", "spectator", "exit")) {
            if (a.spawns.containsKey(key)) continue;
            errors.add("Missing spawn: " + key);
        }
        if (a.bounds != null) {
            try {
                a.bounds.volume();
            } catch (ArithmeticException e) {
                errors.add("Reset bounds volume overflows");
            }
        }
        return List.copyOf(errors);
    }

    public void save(Arena a) throws IOException {
        TreeMap<String, Arena> next = new TreeMap<String, Arena>(this.arenas);
        next.put(a.id, a);
        ArenaRepository.validateGeometry(next.values());
        this.persist(next, this.lobby);
        this.arenas = Map.copyOf(next);
    }

    public static void validateGeometry(Collection<Arena> all) {
        for (Arena a : all) {
            if (a.bounds == null) continue;
            a.bounds.volume();
            for (Arena b : all) {
                if (a.id.equals(b.id) || b.bounds == null || !a.bounds.overlaps(b.bounds)) continue;
                throw new IllegalArgumentException(
                        "Overlapping reset bounds: " + a.id + " / " + b.id);
            }
            for (String key : List.of("attackers", "defenders", "king", "spectator")) {
                Point p = a.spawns.get(key);
                if (p == null || a.bounds.contains(p)) continue;
                throw new IllegalArgumentException(
                        a.id + " spawn " + key + " must be inside bounds");
            }
        }
        for (Arena a : all) {
            Point waiting = a.spawns.get("waiting");
            if (waiting != null) for (Arena b : all) {
                if (!a.id.equals(b.id) && b.bounds != null && b.bounds.contains(waiting))
                    throw new IllegalArgumentException(a.id + " waiting spawn lies inside reset bounds " + b.id);
            }
            Point p = a.spawns.get("exit");
            if (p == null) continue;
            for (Arena b : all) {
                if (b.bounds == null || !b.bounds.contains(p)) continue;
                throw new IllegalArgumentException(a.id + " exit lies inside reset bounds " + b.id);
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void persist(Map<String, Arena> next, Point lobby) throws IOException {
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        TreeMap data = new TreeMap();
        root.put("schema", 1);
        if (lobby != null) {
            root.put("lobby", ArenaRepository.point(lobby));
        }
        root.put("arenas", data);
        for (Arena a : next.values()) {
            LinkedHashMap<String, Object> n = new LinkedHashMap<String, Object>();
            n.put("enabled", a.enabled);
            n.put("mode", a.mode);
            n.put("countdown", a.countdown);
            n.put("round", a.round);
            n.put("region", a.region);
            n.put("build-region", a.buildRegion);
            n.put("spectator-region", a.spectatorRegion);
            if (a.bounds != null) {
                Bounds b = a.bounds;
                n.put(
                        "bounds",
                        List.of(
                                b.world.toString(),
                                b.worldName,
                                Integer.valueOf(b.minX),
                                Integer.valueOf(b.minY),
                                Integer.valueOf(b.minZ),
                                Integer.valueOf(b.maxX),
                                Integer.valueOf(b.maxY),
                                Integer.valueOf(b.maxZ)));
            }
            TreeMap s = new TreeMap();
            a.spawns.forEach((k, v) -> s.put(k, ArenaRepository.point(v)));
            n.put("spawns", s);
            data.put(a.id, n);
        }
        Files.createDirectories(this.file.getParent(), new FileAttribute[0]);
        Path tmp =
                Files.createTempFile(
                        this.file.getParent(), "arena-", ".pending", new FileAttribute[0]);
        try {
            Files.writeString(tmp, (CharSequence) new Yaml().dump(root), new OpenOption[0]);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE); ) {
                ch.force(true);
            }
            Files.move(
                    tmp,
                    this.file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir =
                    FileChannel.open(this.file.getParent(), StandardOpenOption.READ); ) {
                dir.force(true);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static List<Object> point(Point p) {
        return List.of(
                p.world.toString(),
                p.worldName,
                Double.valueOf(p.x),
                Double.valueOf(p.y),
                Double.valueOf(p.z),
                Float.valueOf(p.yaw),
                Float.valueOf(p.pitch));
    }

    private static Point point(Object raw) {
        List l = (List) raw;
        if (l.size() != 7) {
            throw new IllegalArgumentException("Invalid point schema");
        }
        return new Point(
                UUID.fromString((String) l.get(0)),
                (String) l.get(1),
                ArenaRepository.num(l, 2).doubleValue(),
                ArenaRepository.num(l, 3).doubleValue(),
                ArenaRepository.num(l, 4).doubleValue(),
                ArenaRepository.num(l, 5).floatValue(),
                ArenaRepository.num(l, 6).floatValue());
    }

    private static Number num(List<?> l, int n) {
        return (Number) l.get(n);
    }

    private void read() throws IOException {
        LoaderOptions o = new LoaderOptions();
        o.setAllowDuplicateKeys(false);
        o.setMaxAliasesForCollections(0);
        o.setCodePointLimit(1000000);
        try (BufferedReader r = Files.newBufferedReader(this.file); ) {
            Object v2;
            Map root = (Map) new Yaml((BaseConstructor) new SafeConstructor(o)).load((Reader) r);
            if (root == null || !((v2 = root.get("arenas")) instanceof Map)) {
                throw new IllegalArgumentException("arenas.yml: missing arenas mapping");
            }
            Map<?, ?> data = (Map<?, ?>) v2;
            TreeMap<String, Arena> next = new TreeMap<String, Arena>();
            for (Map.Entry<?, ?> entry : data.entrySet()) {
                String id = (String) entry.getKey();
                Map n = (Map) entry.getValue();
                Bounds b = null;
                Object v3 = n.get("bounds");
                if (v3 instanceof List) {
                    List l = (List) v3;
                    b =
                            new Bounds(
                                    UUID.fromString((String) l.get(0)),
                                    (String) l.get(1),
                                    ArenaRepository.num(l, 2).intValue(),
                                    ArenaRepository.num(l, 3).intValue(),
                                    ArenaRepository.num(l, 4).intValue(),
                                    ArenaRepository.num(l, 5).intValue(),
                                    ArenaRepository.num(l, 6).intValue(),
                                    ArenaRepository.num(l, 7).intValue());
                }
                HashMap<String, Point> s = new HashMap<String, Point>();
                Object v4 = n.get("spawns");
                if (v4 instanceof Map) {
                    Map sm = (Map) v4;
                    sm.forEach((k, v) -> s.put((String) k, ArenaRepository.point(v)));
                }
                Arena a =
                        new Arena(
                                id,
                                Boolean.TRUE.equals(n.get("enabled")),
                                (String) n.get("mode"),
                                ((Number) n.get("countdown")).intValue(),
                                ((Number) n.get("round")).intValue(),
                                b,
                                s,
                                (String) n.get("region"),
                                (String) n.get("build-region"),
                                (String) n.get("spectator-region"));
                next.put(id, a);
            }
            ArenaRepository.validateGeometry(next.values());
            this.arenas = Map.copyOf(next);
            if (root.containsKey("lobby")) {
                this.lobby = ArenaRepository.point(root.get("lobby"));
            }
        } catch (RuntimeException e) {
            throw new IOException("arenas.yml: " + e.getMessage(), e);
        }
    }

    public record Point(
            UUID world, String worldName, double x, double y, double z, float yaw, float pitch) {
        public Point {
            Objects.requireNonNull(world);
            Objects.requireNonNull(worldName);
            if (!(Double.isFinite(x)
                    && Double.isFinite(y)
                    && Double.isFinite(z)
                    && Float.isFinite(yaw)
                    && Float.isFinite(pitch)
                    && !(Math.abs(x) > 3.0E7)
                    && !(Math.abs(z) > 3.0E7))) {
                throw new IllegalArgumentException("Invalid coordinates");
            }
        }
    }

    public record Arena(
            String id,
            boolean enabled,
            String mode,
            int countdown,
            int round,
            Bounds bounds,
            Map<String, Point> spawns,
            String region,
            String buildRegion,
            String spectatorRegion) {
        public Arena {
            if (id == null || !id.matches("[a-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("Invalid arena id");
            }
            if (!Set.of("NORMAL", "HARDCORE").contains(mode)
                    || countdown < 0
                    || countdown > 600
                    || round < 1
                    || round > 86400) {
                throw new IllegalArgumentException("Invalid mode/timers");
            }
            spawns = Map.copyOf(spawns);
        }

        public Arena enabled(boolean value) {
            return new Arena(
                    this.id,
                    value,
                    this.mode,
                    this.countdown,
                    this.round,
                    this.bounds,
                    this.spawns,
                    this.region,
                    this.buildRegion,
                    this.spectatorRegion);
        }

        public Arena bounds(Bounds value) {
            return new Arena(
                    this.id,
                    false,
                    this.mode,
                    this.countdown,
                    this.round,
                    value,
                    this.spawns,
                    this.region,
                    this.buildRegion,
                    this.spectatorRegion);
        }

        public Arena spawn(String key, Point value) {
            if (!Set.of("waiting", "attackers", "defenders", "king", "spectator", "exit")
                    .contains(key)) {
                throw new IllegalArgumentException("Unknown spawn");
            }
            HashMap<String, Point> s = new HashMap<String, Point>(this.spawns);
            s.put(key, value);
            return new Arena(
                    this.id,
                    false,
                    this.mode,
                    this.countdown,
                    this.round,
                    this.bounds,
                    s,
                    this.region,
                    this.buildRegion,
                    this.spectatorRegion);
        }

        public Arena regions(String a, String b, String s) {
            for (String r : List.of(a, b, s)) {
                if (r.isEmpty() || r.matches("[A-Za-z0-9_,-]{1,128}")) continue;
                throw new IllegalArgumentException("Invalid region id");
            }
            return new Arena(
                    this.id,
                    false,
                    this.mode,
                    this.countdown,
                    this.round,
                    this.bounds,
                    this.spawns,
                    a,
                    b,
                    s);
        }

        public Arena rules(String m, int c, int r) {
            return new Arena(
                    this.id,
                    false,
                    m,
                    c,
                    r,
                    this.bounds,
                    this.spawns,
                    this.region,
                    this.buildRegion,
                    this.spectatorRegion);
        }
    }

    public record Bounds(
            UUID world,
            String worldName,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ) {
        public Bounds {
            Objects.requireNonNull(world);
            Objects.requireNonNull(worldName);
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Reversed bounds");
            }
        }

        public long volume() {
            return Math.multiplyExact(
                    Math.multiplyExact(
                            (long) this.maxX - (long) this.minX + 1L,
                            (long) this.maxY - (long) this.minY + 1L),
                    (long) this.maxZ - (long) this.minZ + 1L);
        }

        public boolean contains(Point p) {
            return p.world.equals(this.world)
                    && p.x >= (double) this.minX
                    && p.x < (double) ((long) this.maxX + 1L)
                    && p.y >= (double) this.minY
                    && p.y < (double) ((long) this.maxY + 1L)
                    && p.z >= (double) this.minZ
                    && p.z < (double) ((long) this.maxZ + 1L);
        }

        public boolean overlaps(Bounds b) {
            return this.world.equals(b.world)
                    && this.minX <= b.maxX
                    && this.maxX >= b.minX
                    && this.minY <= b.maxY
                    && this.maxY >= b.minY
                    && this.minZ <= b.maxZ
                    && this.maxZ >= b.minZ;
        }
    }
}
