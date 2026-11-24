package com.abyssaldominion.siegewar;

import static org.junit.jupiter.api.Assertions.*;

import com.abyssaldominion.siegewar.runtime.recovery.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

class SnapshotTest {
    private PlayerSnapshot snapshot() {
        return new PlayerSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "world",
                1,
                64,
                2,
                30,
                10,
                new byte[] {1, 2, 3},
                new byte[] {4},
                2,
                "SURVIVAL",
                12,
                0.3f,
                150,
                17,
                2,
                18,
                4,
                0,
                false,
                false,
                0.1f,
                0.2f,
                0,
                0,
                300,
                0,
                false,
                true,
                List.of(new PlayerSnapshot.Effect("minecraft:speed", 100, 1, false, true, true)));
    }

    @Test
    void roundTripsEveryRelevantStateAndRejectsTampering() throws Exception {
        var s = snapshot();
        byte[] data = SnapshotCodec.encode(s);
        var r = SnapshotCodec.decode(data);
        assertEquals(s.player(), r.player());
        assertEquals(s.session(), r.session());
        assertArrayEquals(s.inventory(), r.inventory());
        assertEquals(s.level(), r.level());
        assertEquals(s.effects(), r.effects());
        assertEquals(s.health(), r.health());
        data[20] ^= 1;
        assertThrows(IOException.class, () -> SnapshotCodec.decode(data));
    }

    @Test
    void snapshotItemArraysCannotBeMutatedThroughAccessors() {
        var s = snapshot();
        byte[] changed = s.inventory();
        changed[0] = 99;
        assertEquals(1, s.inventory()[0]);
    }

    @Test
    void rejectsTruncatedPayload() {
        assertThrows(IOException.class, () -> SnapshotCodec.decode(new byte[32]));
    }
}
