package com.abyssaldominion.siegewar;

import com.abyssaldominion.siegewar.store.SiegeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MigrationJournalTest {
    @TempDir Path root;
    private void populate(Path path, Path backups, UUID player, long coins) {
        try (var store = new SiegeStore(path, 1, backups)) {
            store.importPlayer(player,"Migration",coins,0,0,0);
        }
    }
    @Test void interruptedBeforeAtomicInstallResumesValidatedReplacement() throws Exception {
        resume(false);
    }
    @Test void interruptedAfterAtomicInstallResumesNamespaceCommit() throws Exception {
        resume(true);
    }
    private void resume(boolean installed) throws Exception {
        Path base=root.resolve("db"), backups=Files.createDirectories(root.resolve("backups"));
        UUID player=UUID.randomUUID(); String token=UUID.randomUUID().toString();
        Path staged=root.resolve("migrated-"+token);
        populate(base,backups,player,10); populate(staged,backups,player,20);
        Path binary=backups.resolve("legacy-original-"+token+".mv.db");
        Files.copy(Path.of(base+".mv.db"),binary);
        byte[] original=Files.readAllBytes(binary);
        Files.writeString(Path.of(base+".migration.pending"),token);
        if (installed) Files.move(Path.of(staged+".mv.db"),Path.of(base+".mv.db"),StandardCopyOption.REPLACE_EXISTING);
        try (var store=new SiegeStore(base,1,backups)) { assertEquals(20,store.balance(player)); }
        assertFalse(Files.exists(Path.of(base+".migration.pending")));
        assertTrue(Files.isRegularFile(Path.of(base+".h2-namespace")));
        assertArrayEquals(original,Files.readAllBytes(binary));
    }
    @Test void missingSafetyBackupFailsWithoutChangingOriginalDatabase() throws Exception {
        Path base=root.resolve("db"), backups=Files.createDirectories(root.resolve("backups"));
        populate(base,backups,UUID.randomUUID(),10);
        byte[] original=Files.readAllBytes(Path.of(base+".mv.db"));
        Files.writeString(Path.of(base+".migration.pending"),UUID.randomUUID().toString());
        assertThrows(IllegalStateException.class,()->new SiegeStore(base,1,backups));
        assertArrayEquals(original,Files.readAllBytes(Path.of(base+".mv.db")));
        assertTrue(Files.exists(Path.of(base+".migration.pending")));
    }
}
