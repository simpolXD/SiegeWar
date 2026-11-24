package com.abyssaldominion.siegewar.store;

import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** SQL migration is necessary because MVStore persists implementation class names. */
final class LegacyDatabaseMigration {
    static void prepare(Path base, Path backups) {
        rejectLinks(base); rejectLinks(backups);
        Path data = Path.of(base + ".mv.db"), marker = Path.of(base + ".h2-namespace");
        rejectLinks(data); rejectLinks(marker);
        try {
            finishPending(base, backups);
            if (!Files.exists(data)) return;
            if (Files.isRegularFile(marker) && Files.readString(marker).equals(namespace())) return;
            // Pre-1.0.3 files have no namespace marker. Never probe them with the relocated reader.
            migrate(base, backups);
        } catch (Exception failure) {
            throw new IllegalStateException("Database namespace migration failed; original data and recovery files retained. Stop the server before inspecting migration backups.", failure);
        }
    }
    private static String namespace() { return org.h2.Driver.class.getName(); }
    static void mark(Path base) {
        try { atomicText(Path.of(base + ".h2-namespace"), namespace()); }
        catch (IOException ex) { throw new IllegalStateException("Could not persist database namespace marker", ex); }
    }
    private static void migrate(Path base, Path backups) throws Exception {
        Files.createDirectories(backups);
        String token = UUID.randomUUID().toString();
        Path script = backups.resolve("legacy-before-shading-" + token + ".sql");
        Path driver = base.resolveSibling("legacy-reader-" + token + ".jar");
        Path staged = base.resolveSibling("migrated-" + token);
        try {
            try (InputStream input = LegacyDatabaseMigration.class.getResourceAsStream("/migration/legacy-h2.jar")) {
                if (input == null) throw new IOException("Missing offline legacy H2 reader");
                Files.copy(input, driver);
            }
            // No plugin classes are inherited. Parent plugins cannot supply an org.h2 version.
            try (URLClassLoader loader = new URLClassLoader(new URL[]{driver.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                ClassLoader previous = Thread.currentThread().getContextClassLoader();
                Class<?> driverClass = loader.loadClass(String.join(".", "org", "h2", "Driver"));
                try {
                    Thread.currentThread().setContextClassLoader(loader);
                    Driver reader = (Driver) driverClass.getConstructor().newInstance();
                    try (Connection connection = reader.connect("jdbc:h2:file:" + base
                            + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_ON_EXIT=FALSE", new Properties());
                         Statement statement = connection.createStatement()) {
                        statement.execute("SCRIPT TO '" + script.toString().replace("'", "''") + "'");
                    }
                } finally {
                    try { driverClass.getMethod("unload").invoke(null); }
                    finally { Thread.currentThread().setContextClassLoader(previous); }
                }
            }
            try (SiegeStore replacement = new SiegeStore(staged, 1, backups)) {
                replacement.restore(script);
            }
            force(Path.of(staged + ".mv.db")); force(script);
            Path binary = backups.resolve("legacy-original-" + token + ".mv.db");
            Files.copy(Path.of(base + ".mv.db"), binary); force(binary);
            atomicText(Path.of(base + ".migration.pending"), token);
            finishPending(base, backups);
        } finally {
            Files.deleteIfExists(driver);
            // A pending journal must keep the validated staged file available for crash recovery.
            if (!Files.exists(Path.of(base + ".migration.pending")))
                for (String suffix : List.of(".mv.db", ".trace.db", ".lock.db", ".h2-namespace"))
                    Files.deleteIfExists(Path.of(staged + suffix));
        }
    }
    private static void finishPending(Path base, Path backups) throws IOException {
        Path journal = Path.of(base + ".migration.pending");
        rejectLinks(journal);
        if (!Files.exists(journal)) return;
        String token = Files.readString(journal);
        if (!UUID.fromString(token).toString().equals(token)) throw new IOException("Invalid migration token");
        Path converted = base.resolveSibling("migrated-" + token + ".mv.db");
        rejectLinks(converted);
        if (!Files.isRegularFile(backups.resolve("legacy-original-" + token + ".mv.db")))
            throw new IOException("Original migration backup is missing");
        if (Files.exists(converted))
            Files.move(converted, Path.of(base + ".mv.db"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (!Files.isRegularFile(Path.of(base + ".mv.db"))) throw new IOException("Migrated database is missing");
        mark(base);
        Files.delete(journal);
        Files.deleteIfExists(base.resolveSibling("migrated-" + token + ".h2-namespace"));
    }
    private static void atomicText(Path target, String text) throws IOException {
        rejectLinks(target);
        Path pending = target.resolveSibling(target.getFileName() + ".pending-" + UUID.randomUUID());
        try {
            Files.writeString(pending, text, StandardOpenOption.CREATE_NEW); force(pending);
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }
    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    private static void rejectLinks(Path path) {
        if (path == null) throw new IllegalArgumentException("Missing database backup root");
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IllegalArgumentException("Database migration paths cannot contain symbolic links");
    }
    private LegacyDatabaseMigration() {}
}
