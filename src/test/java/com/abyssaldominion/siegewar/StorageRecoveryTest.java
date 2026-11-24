package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.store.SiegeStore;
import com.abyssaldominion.siegewar.store.SiegeStore.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StorageRecoveryTest {
    @TempDir Path temp;

    @Test void contextLoaderIsBoundForPoolCreationAndAlwaysRestored() {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            try (var db = new SiegeStore(temp.resolve("context"), 1)) {
                assertEquals(ClassLoader.getPlatformClassLoader(), thread.getContextClassLoader());
                assertNotNull(db.ensurePlayer(UUID.randomUUID(), "Context"));
            }
        } finally { thread.setContextClassLoader(previous); }
    }

    @Test void sqlBackupRestoresBalancesOwnershipAndPendingRecoveryAcrossRestart() throws Exception {
        Path backup = Files.createDirectories(temp.resolve("backups")), script = backup.resolve("snapshot.sql");
        UUID player = UUID.randomUUID(), session = UUID.randomUUID();
        try (var db = new SiegeStore(temp.resolve("original"), 2, backup)) {
            db.ensurePlayer(player, ".Bedrock_Tester");
            db.coins(UUID.randomUUID(), player, player, CoinAction.GIVE, 50);
            db.purchase(UUID.randomUUID(), player, "wizard", 20);
            db.capture(new Escrow(player, session, UUID.randomUUID(), "castle", Phase.CAPTURED, 1, new byte[]{1,2,3}));
            db.backup(script);
            assertThrows(IllegalArgumentException.class, () -> db.backup(script));
        }
        try (var restored = new SiegeStore(temp.resolve("restored"), 1, backup)) {
            restored.restore(script);
            assertEquals(30, restored.balance(player));
            assertTrue(restored.owned(player).contains("wizard"));
            assertEquals(session, restored.escrow(player).orElseThrow().session());
        }
        try (var reopened = new SiegeStore(temp.resolve("restored"), 1, backup)) {
            assertEquals(30, reopened.balance(player));
            assertEquals(1, reopened.pending().size());
        }
    }

    @Test void backupRejectsTraversalUnsafeTypesAndSymlinkEscapes() throws Exception {
        Path backups = Files.createDirectories(temp.resolve("backups")), outside = Files.createDirectories(temp.resolve("outside"));
        try (var db = new SiegeStore(temp.resolve("paths"), 1, backups)) {
            assertThrows(IllegalArgumentException.class, () -> db.backup(outside.resolve("escape.sql")));
            assertThrows(IllegalArgumentException.class, () -> db.backup(backups.resolve("../backups/traversal.sql")));
            assertThrows(IllegalArgumentException.class, () -> db.backup(backups.resolve("data.mv.db")));
            Files.createSymbolicLink(backups.resolve("link"), outside);
            assertThrows(IllegalArgumentException.class, () -> db.backup(backups.resolve("link/escape.sql")));
            Files.writeString(outside.resolve("input.sql"), "DROP ALL OBJECTS;");
            Files.createSymbolicLink(backups.resolve("input.sql"), outside.resolve("input.sql"));
            assertThrows(IllegalArgumentException.class, () -> db.restore(backups.resolve("input.sql")));
        }
    }

    @Test void unsupportedSchemaFailsBeforePlayersCanJoin() throws Exception {
        Path base = temp.resolve("schema");
        try (var db = new SiegeStore(base, 1);
             var connection = DriverManager.getConnection("jdbc:h2:file:" + base.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE schema_migrations SET version=999");
        }
        assertThrows(SiegeStore.StoreException.class, () -> new SiegeStore(base, 1));
    }

    @Test void corruptImportLeavesExistingBalanceIntact() throws Exception {
        Path backup = Files.createDirectories(temp.resolve("backups")), script = backup.resolve("invalid.sql");
        Files.writeString(script, "CREATE TABLE incompatible(x INT); INVALID SQL;");
        UUID player = UUID.randomUUID();
        try (var db = new SiegeStore(temp.resolve("original"), 1, backup)) {
            db.ensurePlayer(player, "Protected");
            db.coins(UUID.randomUUID(), player, player, CoinAction.GIVE, 25);
            assertThrows(SiegeStore.StoreException.class, () -> db.restore(script));
            assertEquals(25, db.balance(player));
        }
    }

    @Test void importCannotDiscardAnActiveInventoryEscrow() throws Exception {
        Path backup = Files.createDirectories(temp.resolve("backups")), script = backup.resolve("valid.sql");
        UUID player = UUID.randomUUID(), session = UUID.randomUUID();
        try (var db = new SiegeStore(temp.resolve("original"), 1, backup)) {
            db.ensurePlayer(player, "Protected");
            db.backup(script);
            db.capture(new Escrow(player, session, UUID.randomUUID(), "castle", Phase.CAPTURED, 1, new byte[]{1}));
            assertThrows(IllegalStateException.class, () -> db.restore(script));
            assertEquals(session, db.escrow(player).orElseThrow().session());
        }
    }
}
