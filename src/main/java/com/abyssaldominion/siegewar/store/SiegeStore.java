/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  com.zaxxer.hikari.HikariConfig
 *  com.zaxxer.hikari.HikariDataSource
 */
package com.abyssaldominion.siegewar.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SiegeStore implements AutoCloseable {
    public static final long MAX_COINS = 9000000000000000L;
    public static final int MAX_SNAPSHOT_BYTES = 0x1000000;
    private final HikariDataSource pool;
    private final java.util.concurrent.locks.ReentrantReadWriteLock maintenance = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final Path base;
    private final Path backupRoot;

    public SiegeStore(Path databaseBase, int maxConnections) {
        this(
                databaseBase,
                maxConnections,
                databaseBase == null
                        ? null
                        : databaseBase.toAbsolutePath().normalize().getParent());
    }

    public SiegeStore(Path databaseBase, int maxConnections, Path backupRoot) {
        if (maxConnections < 1 || maxConnections > 64) {
            throw new IllegalArgumentException("connections must be 1..64");
        }
        this.base = Objects.requireNonNull(databaseBase).toAbsolutePath().normalize();
        Path path =
                this.backupRoot =
                        backupRoot == null
                                ? this.base.getParent()
                                : backupRoot.toAbsolutePath().normalize();
        if (this.base.toString().contains(";")
                || this.base.toString().contains("\n")
                || this.base.toString().contains("\r")) {
            throw new IllegalArgumentException("Unsafe database path");
        }
        try {
            Files.createDirectories(this.base.getParent(), new FileAttribute[0]);
        } catch (IOException e) {
            throw new StoreException(e);
        }
        LegacyDatabaseMigration.prepare(this.base, this.backupRoot);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(SiegeStore.class.getClassLoader());
            org.h2.Driver.load();
            HikariConfig config = new HikariConfig();
            // Class literals and their names relocate together in the release JAR.
            config.setDriverClassName(org.h2.Driver.class.getName());
            config.setJdbcUrl(
                "jdbc:h2:file:"
                        + String.valueOf(this.base)
                        + ";DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=30000;WRITE_DELAY=0");
        config.setMaximumPoolSize(maxConnections);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(30000L);
        config.setPoolName("siege-store");
            this.pool = new HikariDataSource(config);
        } finally {
            thread.setContextClassLoader(previous);
        }
        try {
            this.tx(
                    c -> {
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS schema_migrations(version INT PRIMARY"
                                    + " KEY, applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS players(player UUID PRIMARY KEY, name"
                                    + " VARCHAR(1024) NOT NULL, coins BIGINT NOT NULL DEFAULT 0"
                                    + " CHECK(coins BETWEEN 0 AND 9000000000000000), kills BIGINT"
                                    + " NOT NULL DEFAULT 0 CHECK(kills BETWEEN 0 AND"
                                    + " 9000000000000000), deaths BIGINT NOT NULL DEFAULT 0"
                                    + " CHECK(deaths BETWEEN 0 AND 9000000000000000), wins BIGINT"
                                    + " NOT NULL DEFAULT 0 CHECK(wins BETWEEN 0 AND"
                                    + " 9000000000000000))",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS write_gate(id INT PRIMARY KEY)",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "INSERT INTO write_gate SELECT 1 WHERE NOT EXISTS(SELECT 1 FROM"
                                    + " write_gate WHERE id=1)",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS event_ledger(kind VARCHAR(16),"
                                    + " event_key VARCHAR(128), fingerprint VARCHAR(64) NOT NULL,"
                                    + " actor UUID, subject UUID, action VARCHAR(32), amount"
                                    + " BIGINT, balance BIGINT, display_name VARCHAR(1024), kills"
                                    + " BIGINT, deaths BIGINT, wins BIGINT, status VARCHAR(32),"
                                    + " PRIMARY KEY(kind,event_key))",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS ownership(player UUID REFERENCES"
                                    + " players(player), kit VARCHAR(32), PRIMARY KEY(player,kit))",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS match_results(match UUID PRIMARY KEY,"
                                    + " arena VARCHAR(32) NOT NULL, outcome VARCHAR(1024) NOT"
                                    + " NULL)",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS reward_events(match UUID REFERENCES"
                                    + " match_results(match), player UUID REFERENCES"
                                    + " players(player), coins BIGINT NOT NULL, kills BIGINT NOT"
                                    + " NULL, deaths BIGINT NOT NULL, wins BIGINT NOT NULL, PRIMARY"
                                    + " KEY(match,player))",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS death_events(match UUID, life UUID,"
                                    + " victim UUID REFERENCES players(player), killer UUID"
                                    + " REFERENCES players(player), coins BIGINT NOT NULL, PRIMARY"
                                    + " KEY(match,victim,life), UNIQUE(match,life))",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS escrows(session UUID PRIMARY KEY,"
                                    + " player UUID NOT NULL REFERENCES players(player), generation"
                                    + " UUID NOT NULL, arena VARCHAR(32) NOT NULL, phase"
                                    + " VARCHAR(16) NOT NULL CHECK(phase IN"
                                    + " ('CAPTURED','ACTIVE','RESTORING','RESTORED')), schema INT"
                                    + " NOT NULL CHECK(schema=1), snapshot BLOB NOT NULL"
                                    + " CHECK(OCTET_LENGTH(snapshot) BETWEEN 1 AND 16777216))",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "CREATE TABLE IF NOT EXISTS escrow_heads(player UUID PRIMARY KEY"
                                    + " REFERENCES players(player), session UUID NOT NULL UNIQUE"
                                    + " REFERENCES escrows(session))",
                                new Object[0]);
                        SiegeStore.execute(
                                c,
                                "INSERT INTO schema_migrations(version) SELECT 1 WHERE NOT EXISTS"
                                    + " (SELECT 1 FROM schema_migrations WHERE version=1)",
                                new Object[0]);
                        validateSchema(c);
                        return null;
                    });
            LegacyDatabaseMigration.mark(this.base);
        } catch (RuntimeException e) {
            this.pool.close();
            throw e;
        }
    }

    public static String driverVersion() { return org.h2.engine.Constants.VERSION; }
    public static String driverClass() { return org.h2.Driver.class.getName(); }
    public static void unloadDriver() { org.h2.Driver.unload(); }

    private static void validateSchema(Connection c) throws SQLException {
        try (Statement statement = c.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version FROM schema_migrations")) {
            boolean found = false;
            while (rows.next()) {
                if (rows.getInt(1) != 1) throw new SQLException("Unsupported database schema version");
                found = true;
            }
            if (!found) throw new SQLException("Missing database schema migration");
        }
        // Validate the columns actually used by storage, rather than merely trusting a version row.
        for (String query : List.of(
                "SELECT player,name,coins,kills,deaths,wins FROM players WHERE 1=0",
                "SELECT kind,event_key,fingerprint FROM event_ledger WHERE 1=0",
                "SELECT player,kit FROM ownership WHERE 1=0",
                "SELECT match,arena,outcome FROM match_results WHERE 1=0",
                "SELECT match,player,coins,kills,deaths,wins FROM reward_events WHERE 1=0",
                "SELECT match,life,victim,killer,coins FROM death_events WHERE 1=0",
                "SELECT session,player,generation,arena,phase,schema,snapshot FROM escrows WHERE 1=0",
                "SELECT player,session FROM escrow_heads WHERE 1=0")) {
            try (Statement s = c.createStatement(); ResultSet ignored = s.executeQuery(query)) {}
        }
    }

    private <T> T write(Work<T> work) {
        return (T)
                this.tx(
                        c -> {
                            try (PreparedStatement p =
                                            c.prepareStatement(
                                                    "SELECT id FROM write_gate WHERE id=1 FOR"
                                                        + " UPDATE");
                                    ResultSet r = p.executeQuery(); ) {
                                if (!r.next()) {
                                    throw new SQLException("Missing write gate");
                                }
                            }
                            return work.run(c);
                        });
    }

    /*
     * Enabled aggressive exception aggregation
     */
    private <T> T tx(Work<T> work) {
        maintenance.readLock().lock();
        try {
        try (Connection c = this.pool.getConnection(); ) {
            T t;
            c.setAutoCommit(false);
            try {
                T value = work.run(c);
                c.commit();
                t = value;
            } catch (RuntimeException | SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
            return t;
        } catch (SQLException e) {
            throw new StoreException(e);
        }

        } finally { maintenance.readLock().unlock(); }
    }

    private static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql); ) {
            SiegeStore.bind(p, args);
            int n = p.executeUpdate();
            return n;
        }
    }

    private static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i = 0; i < args.length; ++i) {
            p.setObject(i + 1, args[i]);
        }
    }

    private static Stats readStats(ResultSet r) throws SQLException {
        return new Stats(
                r.getObject("player", UUID.class),
                r.getString("name"),
                r.getLong("coins"),
                r.getLong("kills"),
                r.getLong("deaths"),
                r.getLong("wins"));
    }

    private static Optional<Stats> find(Connection c, UUID player, boolean lock)
            throws SQLException {
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT * FROM players WHERE player=?" + (lock ? " FOR UPDATE" : "")); ) {
            Optional<Stats> optional;
            block12:
            {
                p.setObject(1, Objects.requireNonNull(player));
                ResultSet r = p.executeQuery();
                try {
                    Optional<Stats> optional2 =
                            optional =
                                    r.next()
                                            ? Optional.of(SiegeStore.readStats(r))
                                            : Optional.empty();
                    if (r == null) break block12;
                } catch (Throwable throwable) {
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                r.close();
            }
            return optional;
        }
    }

    private static Stats require(Connection c, UUID player) throws SQLException {
        return SiegeStore.find(c, player, true)
                .orElseThrow(() -> new IllegalArgumentException("Unknown player"));
    }

    private static void text(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > max
                || value.length() > 1024) {
            throw new IllegalArgumentException("Invalid text length");
        }
        for (int i = 0; i < value.length(); ++i) {
            char ch = value.charAt(i);
            if (!(Character.isHighSurrogate(ch)
                    ? ++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))
                    : Character.isLowSurrogate(ch) || Character.isISOControl(ch))) continue;
            throw new IllegalArgumentException("Invalid Unicode");
        }
    }

    public Stats ensurePlayer(UUID player, String name) {
        Objects.requireNonNull(player);
        SiegeStore.text(name, 256);
        return this.write(
                c -> {
                    SiegeStore.execute(
                            c,
                            "MERGE INTO players(player,name) KEY(player) VALUES(?,?)",
                            player,
                            name);
                    return SiegeStore.find(c, player, false).orElseThrow();
                });
    }

    public Optional<Stats> stats(UUID player) {
        return this.tx(c -> SiegeStore.find(c, player, false));
    }

    public long balance(UUID player) {
        Objects.requireNonNull(player);
        return this.stats(player).map(Stats::coins).orElse(0L);
    }

    public long statsCount() {
        return this.tx(
                c -> {
                    try (PreparedStatement p =
                            c.prepareStatement("SELECT COUNT(*) FROM players"); ) {
                        Long l;
                        block12:
                        {
                            ResultSet r = p.executeQuery();
                            try {
                                l = r.next() ? r.getLong(1) : 0L;
                                if (r == null) break block12;
                            } catch (Throwable t$) {
                                if (r != null) {
                                    try {
                                        r.close();
                                    } catch (Throwable x2) {
                                        t$.addSuppressed(x2);
                                    }
                                }
                                throw t$;
                            }
                            r.close();
                        }
                        return l;
                    }
                });
    }

    public List<Stats> top(String metric, int limit) {
        if (!Set.of("coins", "kills", "deaths", "wins").contains(metric)
                || limit < 1
                || limit > 100) {
            throw new IllegalArgumentException("Invalid leaderboard request");
        }
        return this.tx(
                c -> {
                    ArrayList<Stats> result = new ArrayList<Stats>();
                    try (PreparedStatement p =
                            c.prepareStatement(
                                    "SELECT * FROM players ORDER BY "
                                            + metric
                                            + " DESC, player LIMIT ?"); ) {
                        p.setInt(1, limit);
                        try (ResultSet r = p.executeQuery(); ) {
                            while (r.next()) {
                                result.add(SiegeStore.readStats(r));
                            }
                        }
                    }
                    return List.copyOf(result);
                });
    }

    public Set<String> owned(UUID player) {
        Objects.requireNonNull(player);
        return this.tx(
                c -> {
                    HashSet<String> result = new HashSet<String>();
                    try (PreparedStatement p =
                            c.prepareStatement("SELECT kit FROM ownership WHERE player=?"); ) {
                        SiegeStore.bind(p, player);
                        try (ResultSet r = p.executeQuery(); ) {
                            while (r.next()) {
                                result.add(r.getString(1));
                            }
                        }
                    }
                    return Set.copyOf(result);
                });
    }

    private static void id(String value) {
        if (value == null || !value.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid ID");
        }
    }

    public PurchaseResult purchase(UUID request, UUID player, String kit, long price) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(player);
        SiegeStore.id(kit);
        SiegeStore.bounded(price);
        String fp = SiegeStore.fingerprint(player, kit, price);
        return this.write(
                c -> {
                    PurchaseStatus status;
                    boolean owns;
                    Optional<Event> replay =
                            SiegeStore.event(c, "PURCHASE", request.toString(), fp);
                    if (replay.isPresent()) {
                        return new PurchaseResult(
                                PurchaseStatus.valueOf(replay.get().status()),
                                replay.get().stats().coins());
                    }
                    Stats before = SiegeStore.require(c, player);
                    try (PreparedStatement p =
                            c.prepareStatement(
                                    "SELECT 1 FROM ownership WHERE player=? AND kit=?"); ) {
                        SiegeStore.bind(p, player, kit);
                        try (ResultSet r = p.executeQuery(); ) {
                            owns = r.next();
                        }
                    }
                    if (owns) {
                        status = PurchaseStatus.ALREADY_OWNED;
                    } else if (SiegeStore.execute(
                                    c,
                                    "UPDATE players SET coins=coins-? WHERE player=? AND coins>=?",
                                    price,
                                    player,
                                    price)
                            == 0) {
                        status = PurchaseStatus.INSUFFICIENT_FUNDS;
                    } else {
                        SiegeStore.execute(
                                c, "INSERT INTO ownership(player,kit) VALUES(?,?)", player, kit);
                        status = PurchaseStatus.PURCHASED;
                    }
                    Stats after = SiegeStore.find(c, player, false).orElseThrow();
                    SiegeStore.remember(
                            c,
                            "PURCHASE",
                            request.toString(),
                            fp,
                            player,
                            after,
                            kit,
                            price,
                            status.name());
                    return new PurchaseResult(status, after.coins());
                });
    }

    private static void bounded(long value) {
        if (value < 0L || value > 9000000000000000L) {
            throw new IllegalArgumentException("Value must be 0..MAX_COINS");
        }
    }

    private static long add(long a, long b) {
        SiegeStore.bounded(b);
        if (a > 9000000000000000L - b) {
            throw new IllegalArgumentException("Counter overflow");
        }
        return a + b;
    }

    private static String fingerprint(Object... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes); ) {
                for (Object value : values) {
                    if (value == null) {
                        out.writeInt(-1);
                        continue;
                    }
                    byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
            }
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Optional<Event> event(Connection c, String kind, String key, String fingerprint)
            throws SQLException {
        try (PreparedStatement p =
                c.prepareStatement("SELECT * FROM event_ledger WHERE kind=? AND event_key=?"); ) {
            Optional<Event> optional;
            block17:
            {
                ResultSet r;
                block15:
                {
                    Optional<Event> optional2;
                    block16:
                    {
                        SiegeStore.bind(p, kind, key);
                        r = p.executeQuery();
                        try {
                            if (r.next()) break block15;
                            optional2 = Optional.empty();
                            if (r == null) break block16;
                        } catch (Throwable throwable) {
                            if (r != null) {
                                try {
                                    r.close();
                                } catch (Throwable throwable2) {
                                    throwable.addSuppressed(throwable2);
                                }
                            }
                            throw throwable;
                        }
                        r.close();
                    }
                    return optional2;
                }
                if (!fingerprint.equals(r.getString("fingerprint"))) {
                    throw new IllegalArgumentException(
                            "Idempotency key reused with different parameters");
                }
                UUID subject = r.getObject("subject", UUID.class);
                Stats s =
                        subject == null
                                ? null
                                : new Stats(
                                        subject,
                                        r.getString("display_name"),
                                        r.getLong("balance"),
                                        r.getLong("kills"),
                                        r.getLong("deaths"),
                                        r.getLong("wins"));
                optional = Optional.of(new Event(s, r.getString("status")));
                if (r == null) break block17;
                r.close();
            }
            return optional;
        }
    }

    private static void remember(
            Connection c,
            String kind,
            String key,
            String fp,
            UUID actor,
            Stats s,
            String action,
            long amount,
            String status)
            throws SQLException {
        SiegeStore.execute(
                c,
                "INSERT INTO"
                    + " event_ledger(kind,event_key,fingerprint,actor,subject,action,amount,balance,display_name,kills,deaths,wins,status)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                kind,
                key,
                fp,
                actor,
                s == null ? null : s.player(),
                action,
                amount,
                s == null ? null : Long.valueOf(s.coins()),
                s == null ? null : s.name(),
                s == null ? null : Long.valueOf(s.kills()),
                s == null ? null : Long.valueOf(s.deaths()),
                s == null ? null : Long.valueOf(s.wins()),
                status);
    }

    public Stats coins(UUID operation, UUID actor, UUID player, CoinAction action, long amount) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(player);
        Objects.requireNonNull(action);
        SiegeStore.bounded(amount);
        String fp = SiegeStore.fingerprint(new Object[] {actor, player, action, amount});
        return this.write(
                c -> {
                    Optional<Event> replay = SiegeStore.event(c, "COINS", operation.toString(), fp);
                    if (replay.isPresent()) {
                        return replay.get().stats();
                    }
                    Stats before = SiegeStore.require(c, player);
                    long balance =
                            switch (action.ordinal()) {
                                default -> throw new MatchException(null, null);
                                case 0 -> SiegeStore.add(before.coins(), amount);
                                case 2 -> amount;
                                case 1 -> {
                                    if (before.coins() < amount) {
                                        throw new IllegalArgumentException("Insufficient funds");
                                    }
                                    yield before.coins() - amount;
                                }
                            };
                    SiegeStore.execute(
                            c, "UPDATE players SET coins=? WHERE player=?", balance, player);
                    Stats after = SiegeStore.find(c, player, false).orElseThrow();
                    SiegeStore.remember(
                            c,
                            "COINS",
                            operation.toString(),
                            fp,
                            actor,
                            after,
                            action.name(),
                            amount,
                            "APPLIED");
                    return after;
                });
    }

    private static void delta(Connection c, Reward reward) throws SQLException {
        Stats s = SiegeStore.require(c, reward.player());
        SiegeStore.execute(
                c,
                "UPDATE players SET coins=?,kills=?,deaths=?,wins=? WHERE player=?",
                SiegeStore.add(s.coins(), reward.coins()),
                SiegeStore.add(s.kills(), reward.kills()),
                SiegeStore.add(s.deaths(), reward.deaths()),
                SiegeStore.add(s.wins(), reward.wins()),
                reward.player());
    }

    public boolean recordResult(UUID match, String arena, String outcome, List<Reward> rewards) {
        Objects.requireNonNull(match);
        SiegeStore.id(arena);
        SiegeStore.text(outcome, 256);
        if (rewards == null || rewards.size() > 1024) {
            throw new IllegalArgumentException("Invalid reward list");
        }
        ArrayList<Reward> ordered = new ArrayList<Reward>(List.copyOf(rewards));
        HashSet<UUID> seen = new HashSet<UUID>();
        for (Reward reward : ordered) {
            Objects.requireNonNull(reward.player());
            if (!seen.add(reward.player())) {
                throw new IllegalArgumentException("Duplicate reward player");
            }
            SiegeStore.bounded(reward.coins());
            SiegeStore.bounded(reward.kills());
            SiegeStore.bounded(reward.deaths());
            SiegeStore.bounded(reward.wins());
        }
        ordered.sort(Comparator.comparing(r -> r.player().toString()));
        ArrayList<Object> parameters =
                new ArrayList<Object>(List.of(arena, outcome, Integer.valueOf(ordered.size())));
        for (Reward r3 : ordered) {
            parameters.addAll(
                    List.of(
                            r3.player(),
                            Long.valueOf(r3.coins()),
                            Long.valueOf(r3.kills()),
                            Long.valueOf(r3.deaths()),
                            Long.valueOf(r3.wins())));
        }
        String fp = SiegeStore.fingerprint(parameters.toArray());
        return this.write(
                c -> {
                    if (SiegeStore.event(c, "RESULT", match.toString(), fp).isPresent()) {
                        return false;
                    }
                    SiegeStore.execute(
                            c,
                            "INSERT INTO match_results(match,arena,outcome) VALUES(?,?,?)",
                            match,
                            arena,
                            outcome);
                    for (Reward r : ordered) {
                        SiegeStore.delta(c, r);
                        SiegeStore.execute(
                                c,
                                "INSERT INTO reward_events(match,player,coins,kills,deaths,wins)"
                                    + " VALUES(?,?,?,?,?,?)",
                                match,
                                r.player(),
                                r.coins(),
                                r.kills(),
                                r.deaths(),
                                r.wins());
                    }
                    SiegeStore.remember(
                            c, "RESULT", match.toString(), fp, null, null, null, 0L, "APPLIED");
                    return true;
                });
    }

    public boolean recordDeath(UUID match, UUID death, UUID victim, UUID killer, long coins) {
        Objects.requireNonNull(match);
        Objects.requireNonNull(death);
        Objects.requireNonNull(victim);
        SiegeStore.bounded(coins);
        if (victim.equals(killer) || killer == null && coins != 0L) {
            throw new IllegalArgumentException("Invalid kill attribution");
        }
        String key = String.valueOf(match) + ":" + String.valueOf(death);
        String fp = SiegeStore.fingerprint(match, death, victim, killer, coins);
        return this.write(
                c -> {
                    if (SiegeStore.event(c, "DEATH", key, fp).isPresent()) {
                        return false;
                    }
                    SiegeStore.execute(
                            c,
                            "INSERT INTO death_events(match,life,victim,killer,coins)"
                                + " VALUES(?,?,?,?,?)",
                            match,
                            death,
                            victim,
                            killer,
                            coins);
                    SiegeStore.delta(c, new Reward(victim, 0L, 0L, 1L, 0L));
                    if (killer != null) {
                        SiegeStore.delta(c, new Reward(killer, coins, 1L, 0L, 0L));
                    }
                    SiegeStore.remember(c, "DEATH", key, fp, killer, null, null, coins, "APPLIED");
                    return true;
                });
    }

    private static Escrow readEscrow(ResultSet r) throws SQLException {
        return new Escrow(
                r.getObject("player", UUID.class),
                r.getObject("session", UUID.class),
                r.getObject("generation", UUID.class),
                r.getString("arena"),
                Phase.valueOf(r.getString("phase")),
                r.getInt("schema"),
                r.getBytes("snapshot"));
    }

    private static Optional<Escrow> currentEscrow(Connection c, UUID player) throws SQLException {
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT e.* FROM escrows e JOIN escrow_heads h ON h.session=e.session AND"
                            + " h.player=e.player WHERE h.player=?"); ) {
            Optional<Escrow> optional;
            block12:
            {
                SiegeStore.bind(p, player);
                ResultSet r = p.executeQuery();
                try {
                    Optional<Escrow> optional2 =
                            optional =
                                    r.next()
                                            ? Optional.of(SiegeStore.readEscrow(r))
                                            : Optional.empty();
                    if (r == null) break block12;
                } catch (Throwable throwable) {
                    if (r != null) {
                        try {
                            r.close();
                        } catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    }
                    throw throwable;
                }
                r.close();
            }
            return optional;
        }
    }

    public void capture(Escrow escrow) {
        Objects.requireNonNull(escrow);
        if (escrow.phase() != Phase.CAPTURED) {
            throw new IllegalArgumentException("Capture requires CAPTURED");
        }
        this.write(
                c -> {
                    SiegeStore.require(c, escrow.player());
                    Optional<Escrow> previous = SiegeStore.currentEscrow(c, escrow.player());
                    if (previous.isPresent() && previous.get().phase() != Phase.RESTORED) {
                        throw new IllegalStateException("Unsettled escrow already exists");
                    }
                    try (PreparedStatement p =
                            c.prepareStatement("SELECT 1 FROM escrows WHERE session=?"); ) {
                        SiegeStore.bind(p, escrow.session());
                        try (ResultSet r = p.executeQuery(); ) {
                            if (r.next()) {
                                throw new IllegalStateException("Session token already used");
                            }
                        }
                    }
                    SiegeStore.execute(
                            c,
                            "INSERT INTO"
                                + " escrows(session,player,generation,arena,phase,schema,snapshot)"
                                + " VALUES(?,?,?,?,?,?,?)",
                            escrow.session(),
                            escrow.player(),
                            escrow.generation(),
                            escrow.arena(),
                            escrow.phase().name(),
                            escrow.schema(),
                            escrow.snapshot());
                    SiegeStore.execute(
                            c,
                            "MERGE INTO escrow_heads(player,session) KEY(player) VALUES(?,?)",
                            escrow.player(),
                            escrow.session());
                    return null;
                });
    }

    public Optional<Escrow> escrow(UUID player) {
        Objects.requireNonNull(player);
        return this.tx(c -> SiegeStore.currentEscrow(c, player));
    }

    public List<Escrow> pending() {
        return this.tx(
                c -> {
                    ArrayList<Escrow> result = new ArrayList<Escrow>();
                    try (PreparedStatement p =
                                    c.prepareStatement(
                                            "SELECT e.* FROM escrows e JOIN escrow_heads h ON"
                                                + " h.session=e.session AND h.player=e.player WHERE"
                                                + " e.phase <> 'RESTORED' ORDER BY e.player");
                            ResultSet r = p.executeQuery(); ) {
                        while (r.next()) {
                            result.add(SiegeStore.readEscrow(r));
                        }
                    }
                    return List.copyOf(result);
                });
    }

    public boolean phase(UUID player, UUID session, Phase expected, Phase target) {
        boolean allowed;
        Objects.requireNonNull(player);
        Objects.requireNonNull(session);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(target);
        boolean bl =
                allowed =
                        expected == Phase.CAPTURED
                                        && (target == Phase.ACTIVE || target == Phase.RESTORING)
                                || expected == Phase.ACTIVE && target == Phase.RESTORING
                                || expected == Phase.RESTORING && target == Phase.RESTORED;
        if (!allowed) {
            throw new IllegalArgumentException("Invalid escrow transition");
        }
        return this.write(
                c ->
                        SiegeStore.execute(
                                        c,
                                        "UPDATE escrows SET phase=? WHERE player=? AND session=?"
                                            + " AND phase=? AND EXISTS(SELECT 1 FROM escrow_heads h"
                                            + " WHERE h.player=? AND h.session=?)",
                                        target.name(),
                                        player,
                                        session,
                                        expected.name(),
                                        player,
                                        session)
                                == 1);
    }

    public void backup(Path destination) {
        maintenance.writeLock().lock();
        try {
        String name;
        Objects.requireNonNull(destination);
        rejectTraversal(destination);
        Path target = destination.toAbsolutePath().normalize();
        this.requireInsideBackupRoot(target);
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, new LinkOption[0])) {
            throw new IllegalArgumentException("Backup destination directory does not exist");
        }
        String string = name = target.getFileName() == null ? "" : target.getFileName().toString();
        if (name.isBlank() || !name.matches("[A-Za-z0-9._-]{1,120}") || !name.endsWith(".sql")) {
            throw new IllegalArgumentException("Backup file name must be a plain .sql name");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Backup destination already exists; choose a new name");
        try (Connection c = this.pool.getConnection();
                Statement statement = c.createStatement(); ) {
            statement.execute("SCRIPT TO '" + target.toString().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new StoreException(e);
        }

        } finally { maintenance.writeLock().unlock(); }
    }

    public void restore(Path source) {
        maintenance.writeLock().lock();
        try {
        Objects.requireNonNull(source);
        rejectTraversal(source);
        Path target = source.toAbsolutePath().normalize();
        this.requireInsideBackupRoot(target);
        Path parent = target.getParent();
        if (parent == null
                || !Files.isDirectory(parent, new LinkOption[0])
                || !Files.isRegularFile(target, new LinkOption[0])) {
            throw new IllegalArgumentException("Restore source is not a readable local file");
        }
        String name = target.getFileName().toString();
        if (!name.matches("[A-Za-z0-9._-]{1,120}") || !name.endsWith(".sql")) {
            throw new IllegalArgumentException("Restore source must be a plain .sql file");
        }
        if (!pending().isEmpty()) throw new IllegalStateException("Recover pending inventories before importing another database");
        validateRestore(target);
        Path safety = backupRoot.resolve("before-restore-" + UUID.randomUUID() + ".sql");
        backup(safety);
        try (Connection c = this.pool.getConnection(); Statement statement = c.createStatement()) {
            try {
                statement.execute("DROP ALL OBJECTS");
                statement.execute("RUNSCRIPT FROM '" + target.toString().replace("'", "''") + "'");
                validateSchema(c);
            } catch (SQLException failure) {
                try {
                    statement.execute("DROP ALL OBJECTS");
                    statement.execute("RUNSCRIPT FROM '" + safety.toString().replace("'", "''") + "'");
                    validateSchema(c);
                } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException e) { throw new StoreException(e); }

        } finally { maintenance.writeLock().unlock(); }
    }

    private void validateRestore(Path script) {
        Path directory = null;
        try {
            if (Files.size(script) > 1073741824L) throw new IllegalArgumentException("SQL import exceeds 1 GiB budget");
            directory = Files.createTempDirectory(base.getParent(), "restore-check-");
            String url = "jdbc:h2:file:" + directory.resolve("check") + ";DB_CLOSE_ON_EXIT=FALSE";
            try (Connection connection = new org.h2.Driver().connect(url, new java.util.Properties());
                 Statement statement = connection.createStatement()) {
                statement.execute("RUNSCRIPT FROM '" + script.toString().replace("'", "''") + "'");
                validateSchema(connection);
            }
        } catch (IOException | SQLException ex) { throw new StoreException(ex); }
        finally {
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException ignored) { /* Isolated preflight data contains no active state. */ }
            }
        }
    }

    public boolean importPlayer(
            UUID player, String name, long coins, long kills, long deaths, long wins) {
        Objects.requireNonNull(player);
        SiegeStore.text(name, 256);
        SiegeStore.bounded(coins);
        SiegeStore.bounded(kills);
        SiegeStore.bounded(deaths);
        SiegeStore.bounded(wins);
        return this.write(
                c -> {
                    if (SiegeStore.find(c, player, false).isPresent()) {
                        return false;
                    }
                    SiegeStore.execute(
                            c,
                            "INSERT INTO players(player,name,coins,kills,deaths,wins)"
                                + " VALUES(?,?,?,?,?,?)",
                            player,
                            name,
                            coins,
                            kills,
                            deaths,
                            wins);
                    return true;
                });
    }

    public boolean importOwnership(UUID player, String kit) {
        Objects.requireNonNull(player);
        SiegeStore.id(kit);
        return this.write(
                c -> {
                    SiegeStore.require(c, player);
                    try {
                        SiegeStore.execute(
                                c, "INSERT INTO ownership(player,kit) VALUES(?,?)", player, kit);
                        return true;
                    } catch (SQLException e) {
                        if ("23505".equals(e.getSQLState())) {
                            return false;
                        }
                        throw e;
                    }
                });
    }

    private void requireInsideBackupRoot(Path target) {
        if (this.backupRoot == null || !target.startsWith(this.backupRoot)) {
            throw new IllegalArgumentException(
                    "Backup path escapes the configured backup directory");
        }
        for (Path path = target; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path))
                throw new IllegalArgumentException("Symbolic links are not allowed in backup paths");
        }
    }

    private static void rejectTraversal(Path path) {
        for (Path part : path) if (part.toString().equals(".."))
            throw new IllegalArgumentException("Parent traversal is not allowed in backup paths");
    }

    @Override
    public void close() {
        this.pool.close();
    }

    public static final class StoreException extends RuntimeException {
        StoreException(Throwable cause) {
            super("Storage operation failed", cause);
        }
    }

    @FunctionalInterface
    private static interface Work<T> {
        public T run(Connection var1) throws SQLException;
    }

    public record Stats(UUID player, String name, long coins, long kills, long deaths, long wins) {
        public double kdr() {
            return this.deaths == 0L
                    ? (double) this.kills
                    : (double) this.kills / (double) this.deaths;
        }
    }

    public record PurchaseResult(PurchaseStatus status, long balance) {}

    private record Event(Stats stats, String status) {}

    public static enum CoinAction {
        GIVE,
        TAKE,
        SET;
    }

    public record Reward(UUID player, long coins, long kills, long deaths, long wins) {}

    public record Escrow(
            UUID player,
            UUID session,
            UUID generation,
            String arena,
            Phase phase,
            int schema,
            byte[] snapshot) {

        public Escrow {
            Objects.requireNonNull(player);
            Objects.requireNonNull(session);
            Objects.requireNonNull(generation);
            Objects.requireNonNull(phase);
            SiegeStore.id(arena);
            if (schema != 1
                    || snapshot == null
                    || snapshot.length == 0
                    || snapshot.length > 0x1000000) {
                throw new IllegalArgumentException("Unsupported snapshot schema or payload size");
            }
            snapshot = (byte[]) snapshot.clone();
        }

        public byte[] snapshot() {
            return (byte[]) this.snapshot.clone();
        }
    }

    public static enum Phase {
        CAPTURED,
        ACTIVE,
        RESTORING,
        RESTORED;
    }

    public static enum PurchaseStatus {
        PURCHASED,
        ALREADY_OWNED,
        INSUFFICIENT_FUNDS;
    }
}
