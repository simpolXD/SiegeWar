package com.abyssaldominion.siegewar;

import static org.junit.jupiter.api.Assertions.*;

import com.abyssaldominion.siegewar.core.MatchEngine;
import com.abyssaldominion.siegewar.core.MatchEngine.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MatchEngineTest {
    private MatchEngine game(Mode mode) {
        return new MatchEngine(UUID.randomUUID(), mode, 2, 1, 2, 1000, 10000);
    }

    @Test
    void balancingAndCountdownCancellation() {
        var g = game(Mode.NORMAL);
        UUID a = UUID.randomUUID(), d = UUID.randomUUID();
        assertTrue(g.join(a, Team.ATTACKERS));
        assertFalse(g.join(d, Team.ATTACKERS));
        assertTrue(g.join(d, Team.AUTO));
        assertTrue(g.startCountdown(100));
        g.leave(d, 500);
        assertEquals(State.WAITING, g.state());
        assertFalse(g.eligible());
        assertTrue(g.join(d, Team.DEFENDERS));
        assertTrue(g.startCountdown(1000));
        g.tick(2000);
        assertEquals(State.RUNNING, g.state());
        assertFalse(g.chooseTeam(a, Team.DEFENDERS));
    }

    @Test
    void kingDeathAndDeadlineHaveOneDeterministicResult() {
        var g = game(Mode.NORMAL);
        g.join(UUID.randomUUID(), Team.AUTO);
        g.join(UUID.randomUUID(), Team.AUTO);
        assertTrue(g.forceStart(0));
        assertEquals(Team.ATTACKERS, g.kingKilled(9999).orElseThrow().winner());
        assertTrue(g.kingKilled(10000).isEmpty());
        assertTrue(g.tick(20000).isEmpty());
        assertEquals(Team.ATTACKERS, g.outcome().orElseThrow().winner());
        assertTrue(g.beginReset());
        assertTrue(g.resetSucceeded());
        assertEquals(State.DISABLED, g.state());
    }

    @Test
    void timeoutWinsForDefendersAtExactDeadline() {
        var g = game(Mode.NORMAL);
        g.join(UUID.randomUUID(), Team.AUTO);
        g.join(UUID.randomUUID(), Team.AUTO);
        g.forceStart(0);
        assertEquals(Team.DEFENDERS, g.kingKilled(10000).orElseThrow().winner());
    }

    @Test
    void hardcoreEliminationAndStaleGeneration() {
        var g = game(Mode.HARDCORE);
        UUID a = UUID.randomUUID(), d = UUID.randomUUID();
        g.join(a, Team.ATTACKERS);
        g.join(d, Team.DEFENDERS);
        g.forceStart(0);
        assertFalse(g.accepts(UUID.randomUUID(), a));
        assertTrue(g.accepts(g.generation(), a));
        assertEquals(Team.DEFENDERS, g.eliminate(a, 10).orElseThrow().winner());
        assertFalse(g.accepts(g.generation(), a));
        assertTrue(g.eliminate(a, 11).isEmpty());
    }

    @Test
    void normalDeathDoesNotEliminateTheTeam() {
        var g = game(Mode.NORMAL);
        UUID a = UUID.randomUUID(), d = UUID.randomUUID();
        g.join(a, Team.AUTO);
        g.join(d, Team.AUTO);
        g.forceStart(0);
        assertTrue(g.eliminate(a, 1).isEmpty());
        assertEquals(State.RUNNING, g.state());
        assertTrue(g.eliminated().isEmpty());
    }
}
