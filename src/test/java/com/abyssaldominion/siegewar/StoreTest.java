package com.abyssaldominion.siegewar;

import static org.junit.jupiter.api.Assertions.*;

import com.abyssaldominion.siegewar.store.SiegeStore;
import com.abyssaldominion.siegewar.store.SiegeStore.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class StoreTest {
    @TempDir Path temp;

    @Test
    void parallelPurchasesCannotDoubleChargeOrOverspend() throws Exception {
        try (var db = new SiegeStore(temp.resolve("db"), 4)) {
            UUID id = UUID.randomUUID();
            db.ensurePlayer(id, ".Bedrock_Player_With_Long_Name");
            db.coins(UUID.randomUUID(), id, id, CoinAction.GIVE, 50);
            try (var executor = Executors.newFixedThreadPool(6)) {
                List<Future<PurchaseResult>> futures = new ArrayList<>();
                for (int i = 0; i < 12; i++)
                    futures.add(
                            executor.submit(
                                    () -> db.purchase(UUID.randomUUID(), id, "wizard", 45)));
                int bought = 0;
                for (var f : futures) if (f.get().status() == PurchaseStatus.PURCHASED) bought++;
                assertEquals(1, bought);
            }
            assertEquals(5, db.balance(id));
            assertEquals(Set.of("wizard"), db.owned(id));
            assertEquals(
                    PurchaseStatus.INSUFFICIENT_FUNDS,
                    db.purchase(UUID.randomUUID(), id, "knight", 90).status());
            db.ensurePlayer(id, "Renamed_日本語");
            assertEquals(5, db.balance(id));
        }
    }

    @Test
    void rewardReplayAndRestartAreIdempotent() {
        UUID id = UUID.randomUUID(), match = UUID.randomUUID(), operation = UUID.randomUUID();
        try (var db = new SiegeStore(temp.resolve("db"), 2)) {
            db.ensurePlayer(id, "Tester");
            var rewards = List.of(new Reward(id, 3, 0, 0, 1));
            assertTrue(db.recordResult(match, "arena", "timeout", rewards));
            assertFalse(db.recordResult(match, "arena", "timeout", rewards));
            assertEquals(3, db.balance(id));
            assertEquals(1, db.stats(id).orElseThrow().wins());
            db.coins(operation, id, id, CoinAction.GIVE, 7);
            db.coins(operation, id, id, CoinAction.GIVE, 7);
            assertEquals(10, db.balance(id));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> db.coins(operation, id, id, CoinAction.GIVE, 8));
            assertEquals(10, db.balance(id));
        }
        try (var db = new SiegeStore(temp.resolve("db"), 2)) {
            assertEquals(10, db.balance(id));
            assertEquals(1, db.stats(id).orElseThrow().wins());
        }
    }

    @Test
    void duplicateLifeDeathAndInvalidCoinInput() {
        try (var db = new SiegeStore(temp.resolve("db"), 2)) {
            UUID a = UUID.randomUUID(),
                    b = UUID.randomUUID(),
                    m = UUID.randomUUID(),
                    life = UUID.randomUUID();
            db.ensurePlayer(a, "A");
            db.ensurePlayer(b, "B");
            assertTrue(db.recordDeath(m, life, b, a, 1));
            assertFalse(db.recordDeath(m, life, b, a, 1));
            assertEquals(1, db.stats(a).orElseThrow().kills());
            assertEquals(1, db.stats(b).orElseThrow().deaths());
            assertEquals(1, db.balance(a));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> db.coins(UUID.randomUUID(), a, a, CoinAction.GIVE, -1));
        }
    }

    @Test
    void pendingInventoryEscrowSurvivesRestart() {
        UUID id = UUID.randomUUID(), session = UUID.randomUUID();
        try (var db = new SiegeStore(temp.resolve("db"), 2)) {
            db.ensurePlayer(id, "Escrow");
            db.capture(
                    new Escrow(
                            id,
                            session,
                            UUID.randomUUID(),
                            "arena",
                            Phase.CAPTURED,
                            1,
                            new byte[] {1, 2, 3}));
            assertTrue(db.phase(id, session, Phase.CAPTURED, Phase.ACTIVE));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            db.capture(
                                    new Escrow(
                                            id,
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            "arena",
                                            Phase.CAPTURED,
                                            1,
                                            new byte[] {4})));
        }
        try (var db = new SiegeStore(temp.resolve("db"), 2)) {
            assertEquals(1, db.pending().size());
            assertEquals(Phase.ACTIVE, db.escrow(id).orElseThrow().phase());
            assertTrue(db.phase(id, session, Phase.ACTIVE, Phase.RESTORING));
            assertTrue(db.phase(id, session, Phase.RESTORING, Phase.RESTORED));
            assertTrue(db.pending().isEmpty());
            assertFalse(db.phase(id, session, Phase.RESTORING, Phase.RESTORED));
        }
    }
    @Test
    void staleRecoveryCannotConsumeTheNextSession() {
        UUID id = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        try (var db = new SiegeStore(temp.resolve("db"), 2)) {
            db.ensurePlayer(id, "Relog");
            db.capture(new Escrow(id, first, UUID.randomUUID(), "arena", Phase.CAPTURED, 1, new byte[]{1}));
            assertTrue(db.phase(id, first, Phase.CAPTURED, Phase.RESTORING));
            assertTrue(db.phase(id, first, Phase.RESTORING, Phase.RESTORED));
            db.capture(new Escrow(id, second, UUID.randomUUID(), "arena", Phase.CAPTURED, 1, new byte[]{2}));
            assertFalse(db.phase(id, first, Phase.RESTORING, Phase.RESTORED));
            assertFalse(db.phase(id, first, Phase.CAPTURED, Phase.ACTIVE));
            assertEquals(second, db.escrow(id).orElseThrow().session());
            assertEquals(Phase.CAPTURED, db.escrow(id).orElseThrow().phase());
        }
    }

    @Test
    void snapshotCommitsFlushSynchronously() throws Exception {
        Path base = temp.resolve("durable");
        try (var db = new SiegeStore(base, 2);
             var connection = java.sql.DriverManager.getConnection("jdbc:h2:file:" + base.toAbsolutePath());
             var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='WRITE_DELAY'")) {
            assertTrue(row.next());
            assertEquals("0", row.getString(1));
        }
    }

    @Test
    void parallelCapturesAllowOnlyOneInventoryEscrow() throws Exception {
        try (var db = new SiegeStore(temp.resolve("db"), 4);
             var executor = Executors.newFixedThreadPool(4)) {
            UUID id = UUID.randomUUID();
            db.ensurePlayer(id, "CaptureRace");
            List<Future<Boolean>> captures = new ArrayList<>();
            for (int i = 0; i < 8; i++) captures.add(executor.submit(() -> {
                try {
                    db.capture(new Escrow(id, UUID.randomUUID(), UUID.randomUUID(), "arena", Phase.CAPTURED, 1, new byte[]{1}));
                    return true;
                } catch (IllegalStateException expected) { return false; }
            }));
            int accepted = 0;
            for (var future : captures) if (future.get()) accepted++;
            assertEquals(1, accepted);
            assertEquals(1, db.pending().size());
        }
    }

    @Test
    void failedResultRollsBackAllRewardsAndCanBeRetried() {
        try (var db = new SiegeStore(temp.resolve("db"), 2)) {
            UUID first = UUID.randomUUID(), missing = UUID.randomUUID(), match = UUID.randomUUID();
            db.ensurePlayer(first, "First");
            var rewards = List.of(new Reward(first, 10, 0, 0, 1), new Reward(missing, 10, 0, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> db.recordResult(match, "arena", "timeout", rewards));
            assertEquals(0, db.balance(first));
            assertEquals(0, db.stats(first).orElseThrow().wins());
            db.ensurePlayer(missing, "Second");
            assertTrue(db.recordResult(match, "arena", "timeout", rewards));
            assertEquals(10, db.balance(first));
            assertEquals(10, db.balance(missing));
        }
    }

}
