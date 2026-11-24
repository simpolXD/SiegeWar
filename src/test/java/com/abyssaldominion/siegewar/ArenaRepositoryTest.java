package com.abyssaldominion.siegewar;

import static org.junit.jupiter.api.Assertions.*;

import com.abyssaldominion.siegewar.runtime.ArenaRepository;
import com.abyssaldominion.siegewar.runtime.ArenaRepository.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class ArenaRepositoryTest {
    @TempDir Path temp;

    @Test
    void completeDisabledArenaCanBeValidatedAndReopened() throws Exception {
        Path path = temp.resolve("arenas.yml");
        ArenaRepository repo = new ArenaRepository(path);
        UUID world = UUID.randomUUID();
        repo.create("castle");
        Arena a = repo.all().get("castle");
        Point inner = new Point(world, "world", 5, 65, 5, 0, 0),
                exit = new Point(world, "world", 30, 65, 30, 0, 0);
        for (String key : List.of("waiting", "attackers", "defenders", "king", "spectator"))
            a = a.spawn(key, inner);
        a = a.spawn("exit", exit).bounds(new Bounds(world, "world", 0, 60, 0, 20, 80, 20));
        repo.save(a);
        assertTrue(repo.validate("castle").isEmpty());
        ArenaRepository loaded = new ArenaRepository(path);
        assertEquals(inner, loaded.all().get("castle").spawns().get("king"));
        assertFalse(loaded.all().get("castle").enabled());
        Arena valid = a;
        assertThrows(IllegalArgumentException.class, () -> repo.save(valid.spawn("exit", inner)));
        repo.create("overlap");
        assertThrows(
                IllegalArgumentException.class,
                () -> repo.save(repo.all().get("overlap").bounds(valid.bounds())));
    }

    @Test
    void rejectsUnsafeIdentifiersAndNonFiniteCoordinates() throws Exception {
        ArenaRepository repo = new ArenaRepository(temp.resolve("a.yml"));
        assertThrows(IllegalArgumentException.class, () -> repo.create("../escape"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Point(UUID.randomUUID(), "world", Double.NaN, 64, 0, 0, 0));
    }
    @Test
    void waitingLobbyCannotOccupyAnotherArenasResetBounds() throws Exception {
        ArenaRepository repo = new ArenaRepository(temp.resolve("a.yml"));
        UUID world = UUID.randomUUID();
        repo.create("first"); repo.create("second");
        repo.save(repo.all().get("first").bounds(new Bounds(world, "world", 0, 60, 0, 20, 80, 20)));
        Arena second = repo.all().get("second").bounds(new Bounds(world, "world", 30, 60, 0, 50, 80, 20));
        repo.save(second);
        assertThrows(IllegalArgumentException.class, () -> repo.save(second.spawn("waiting", new Point(world, "world", 5, 65, 5, 0, 0))));
        assertFalse(repo.all().get("second").spawns().containsKey("waiting"));
    }

}
