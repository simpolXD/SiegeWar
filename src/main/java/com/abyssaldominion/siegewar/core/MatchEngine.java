/*
 * Decompiled with CFR 0.152.
 */
package com.abyssaldominion.siegewar.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MatchEngine {
    private final UUID generation;
    private final Mode mode;
    private final int teamCapacity;
    private final int maxTeamDifference;
    private final int minPlayers;
    private final long countdownMillis;
    private final long roundMillis;
    private final Map<UUID, Team> teams = new HashMap<UUID, Team>();
    private final Set<UUID> eliminated = new HashSet<UUID>();
    private State state = State.WAITING;
    private long deadlineMillis;
    private long countdownDeadlineMillis;
    private Outcome outcome;
    private String failureReason;

    public MatchEngine(
            UUID generation,
            Mode mode,
            int teamCapacity,
            int maxTeamDifference,
            int minPlayers,
            long countdownMillis,
            long roundMillis) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (teamCapacity < 1
                || maxTeamDifference < 0
                || minPlayers < 2
                || (long) minPlayers > 2L * (long) teamCapacity
                || countdownMillis < 0L
                || roundMillis <= 0L) {
            throw new IllegalArgumentException("Invalid match limits/durations");
        }
        this.teamCapacity = teamCapacity;
        this.maxTeamDifference = maxTeamDifference;
        this.minPlayers = minPlayers;
        this.countdownMillis = countdownMillis;
        this.roundMillis = roundMillis;
    }

    public synchronized State state() {
        return this.state;
    }

    public synchronized UUID generation() {
        return this.generation;
    }

    public synchronized long deadlineMillis() {
        return this.deadlineMillis;
    }

    public synchronized long countdownDeadlineMillis() {
        return this.countdownDeadlineMillis;
    }

    public synchronized Map<UUID, Team> teams() {
        return Map.copyOf(this.teams);
    }

    public synchronized Set<UUID> eliminated() {
        return Set.copyOf(this.eliminated);
    }

    public synchronized Optional<Outcome> outcome() {
        return Optional.ofNullable(this.outcome);
    }

    public synchronized Optional<String> failureReason() {
        return Optional.ofNullable(this.failureReason);
    }

    public synchronized Team team(UUID player) {
        return this.teams.getOrDefault(Objects.requireNonNull(player), Team.NONE);
    }

    public synchronized boolean eligible() {
        long a = this.count(Team.ATTACKERS);
        long d = this.count(Team.DEFENDERS);
        return (this.state == State.WAITING || this.state == State.COUNTDOWN)
                && this.teams.size() >= this.minPlayers
                && a > 0L
                && d > 0L
                && a <= (long) this.teamCapacity
                && d <= (long) this.teamCapacity
                && Math.abs(a - d) <= (long) this.maxTeamDifference;
    }

    public synchronized boolean startCountdown(long nowMillis) {
        long due;
        MatchEngine.checkTime(nowMillis);
        if (this.state != State.WAITING || !this.eligible()) {
            return false;
        }
        this.countdownDeadlineMillis = due = Math.addExact(nowMillis, this.countdownMillis);
        this.state = State.COUNTDOWN;
        return true;
    }

    public synchronized boolean cancelCountdown() {
        if (this.state != State.COUNTDOWN) {
            return false;
        }
        this.state = State.WAITING;
        this.countdownDeadlineMillis = 0L;
        return true;
    }

    public synchronized boolean forceStart(long nowMillis) {
        long due;
        MatchEngine.checkTime(nowMillis);
        if (!this.eligible()) {
            return false;
        }
        this.deadlineMillis = due = Math.addExact(nowMillis, this.roundMillis);
        this.countdownDeadlineMillis = 0L;
        this.state = State.RUNNING;
        return true;
    }

    public synchronized Optional<Outcome> tick(long nowMillis) {
        MatchEngine.checkTime(nowMillis);
        if (this.state == State.COUNTDOWN) {
            if (!this.eligible()) {
                this.cancelCountdown();
            } else if (nowMillis >= this.countdownDeadlineMillis) {
                this.forceStart(nowMillis);
            }
        }
        if (this.state == State.RUNNING && nowMillis >= this.deadlineMillis) {
            return this.finish(Team.DEFENDERS, false, "timeout");
        }
        return Optional.empty();
    }

    public synchronized Optional<Outcome> kingKilled(long nowMillis) {
        MatchEngine.checkTime(nowMillis);
        if (this.state != State.RUNNING) {
            return Optional.empty();
        }
        return nowMillis < this.deadlineMillis
                ? this.finish(Team.ATTACKERS, false, "king-killed")
                : this.finish(Team.DEFENDERS, false, "timeout");
    }

    public synchronized Optional<Outcome> leave(UUID player, long nowMillis) {
        Objects.requireNonNull(player);
        MatchEngine.checkTime(nowMillis);
        if (this.teams.remove(player) == null) {
            return Optional.empty();
        }
        this.eliminated.remove(player);
        if (this.outcome != null
                || this.state != State.WAITING
                        && this.state != State.COUNTDOWN
                        && this.state != State.RUNNING) {
            return Optional.empty();
        }
        if (this.teams.isEmpty()) {
            return this.finish(Team.NONE, true, "all-players-left");
        }
        if (this.state == State.COUNTDOWN && !this.eligible()) {
            this.cancelCountdown();
        }
        if (this.state == State.RUNNING) {
            if (nowMillis >= this.deadlineMillis) {
                return this.finish(Team.DEFENDERS, false, "timeout");
            }
            if (this.activeCount(Team.ATTACKERS) == 0L) {
                return this.finish(Team.DEFENDERS, false, "forfeit");
            }
            if (this.activeCount(Team.DEFENDERS) == 0L) {
                return this.finish(Team.ATTACKERS, false, "forfeit");
            }
        }
        return Optional.empty();
    }

    public synchronized Optional<Outcome> eliminate(UUID player, long nowMillis) {
        Objects.requireNonNull(player);
        MatchEngine.checkTime(nowMillis);
        if (this.state != State.RUNNING
                || !this.teams.containsKey(player)
                || this.eliminated.contains(player)) {
            return Optional.empty();
        }
        if (nowMillis >= this.deadlineMillis) {
            return this.finish(Team.DEFENDERS, false, "timeout");
        }
        if (this.mode == Mode.NORMAL) {
            return Optional.empty();
        }
        this.eliminated.add(player);
        if (this.activeCount(Team.ATTACKERS) == 0L) {
            return this.finish(Team.DEFENDERS, false, "team-eliminated");
        }
        if (this.activeCount(Team.DEFENDERS) == 0L) {
            return this.finish(Team.ATTACKERS, false, "team-eliminated");
        }
        return Optional.empty();
    }

    private long activeCount(Team team) {
        return this.teams.entrySet().stream()
                .filter(e -> e.getValue() == team && !this.eliminated.contains(e.getKey()))
                .count();
    }

    public synchronized boolean accepts(UUID generation, UUID player) {
        return this.state == State.RUNNING
                && this.generation.equals(generation)
                && this.teams.containsKey(player)
                && !this.eliminated.contains(player);
    }

    public synchronized Optional<Outcome> abort(String reason) {
        MatchEngine.requireReason(reason);
        if (this.state != State.WAITING
                && this.state != State.COUNTDOWN
                && this.state != State.RUNNING) {
            return Optional.empty();
        }
        return this.finish(Team.NONE, true, reason);
    }

    public synchronized boolean beginReset() {
        if (this.state != State.ENDING) {
            return false;
        }
        this.state = State.RESETTING;
        return true;
    }

    public synchronized boolean resetSucceeded() {
        if (this.state != State.RESETTING) {
            return false;
        }
        this.state = State.DISABLED;
        return true;
    }

    public synchronized void fail(String reason) {
        MatchEngine.requireReason(reason);
        this.failureReason = reason;
        this.state = State.ERROR;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1024) {
            throw new IllegalArgumentException("Reason must contain 1..1024 characters");
        }
    }

    private Optional<Outcome> finish(Team winner, boolean aborted, String reason) {
        if (this.outcome != null) {
            return Optional.empty();
        }
        this.outcome = new Outcome(this.generation, winner, aborted, reason);
        this.state = State.ENDING;
        return Optional.of(this.outcome);
    }

    private static void checkTime(long nowMillis) {
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("Negative time");
        }
    }

    private long count(Team team) {
        return this.teams.values().stream().filter(t -> t == team).count();
    }

    public synchronized boolean join(UUID player, Team requested) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(requested);
        return !this.teams.containsKey(player) && this.assign(player, requested);
    }

    public synchronized boolean chooseTeam(UUID player, Team requested) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(requested);
        return this.teams.containsKey(player) && this.assign(player, requested);
    }

    private boolean assign(UUID player, Team requested) {
        Team target;
        if (this.state != State.WAITING && this.state != State.COUNTDOWN
                || requested == Team.NONE) {
            return false;
        }
        long a = this.count(Team.ATTACKERS);
        long d = this.count(Team.DEFENDERS);
        Team old = this.teams.get(player);
        if (old == Team.ATTACKERS) {
            --a;
        }
        if (old == Team.DEFENDERS) {
            --d;
        }
        target = requested == Team.AUTO ? (a <= d ? Team.ATTACKERS : Team.DEFENDERS) : requested;
        if (target == Team.ATTACKERS) {
            ++a;
        } else {
            ++d;
        }
        if (a > (long) this.teamCapacity
                || d > (long) this.teamCapacity
                || Math.abs(a - d) > (long) this.maxTeamDifference) {
            return false;
        }
        this.teams.put(player, target);
        if (this.state == State.COUNTDOWN && !this.eligible()) {
            this.cancelCountdown();
        }
        return true;
    }

    public static enum State {
        DISABLED,
        WAITING,
        COUNTDOWN,
        RUNNING,
        ENDING,
        RESETTING,
        ERROR;
    }

    public static enum Mode {
        NORMAL,
        HARDCORE;
    }

    public record Outcome(UUID generation, Team winner, boolean aborted, String reason) {}

    public static enum Team {
        ATTACKERS,
        DEFENDERS,
        AUTO,
        NONE;
    }
}
