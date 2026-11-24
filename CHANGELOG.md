# Changelog — 1.0.3

OLD VERSION: **1.0.2**

NEW VERSION: **1.0.3**

- Private H2/Hikari runtime with explicit driver/context-loader binding and Java 25-compatible shading.
- H2 resource archive relocation, offline SQL namespace conversion, retained original backups, version marker and resumable migration journal.
- Preserved player inventory/state on join; empty-slot kit grants and exact authoritative recovery.
- Unique server-issued item registry, connection/generation-fenced actions and bounded entity cleanup.
- Native lethal damage/death handling, legitimate max health, and TNT King protection.
- Active arena breaking with journaled managed-region flags and retained FAWE regeneration.
- Configurable item/effect restrictions and warnings, creative injection prevention and combat mode restrictions.
- Canonical config migration, strict YAML/value validation, reload/infrastructure guards, accurate diagnostics and version metadata.
- New storage/identity/migration regression checks and real Paper verification evidence; see TEST_REPORT.md.

Detailed FIXED ISSUES are in RELEASE_NOTES.md. FILES MODIFIED are in FILES_MODIFIED.md.

---

## Historical entries from the supplied source

# 1.0.2 — 2026-11-13

- Require synchronous durable escrow commits and connection-bound recovery; keep failed snapshot cancellations gated.
- Finalize asynchronous cross-world returns after world-change callbacks and flush the actual Paper 26.x player-data file.
- Restore online state during quit and support real player deaths and respawns alongside virtual defeats.
- Scope issued projectiles and items to a particular combat life; remove owned entities on death/leave and discard stale loaded entities.
- Isolate every configured reset cuboid from external container extraction, automation, fluids, buckets, entity interactions, drops, and unauthorized entry.
- Prevent offhand interaction and external teleports during recovery; require active combatants to remain inside their arena.
- Defer menu actions until the next tick, reject stale menus, and resolve command ownership during matches.
- Validate additional configuration limits and king types, and translate incomplete FAWE selection into a user-facing setup error.
- Reject waiting spawns inside another arena's reset bounds.
- Expand persistence/geometry regression coverage to 22 automated tests and add a native staging harness for the live checks documented in TEST_REPORT.md.

# 1.0.1 — 2026-11-13

- Connected plugin startup, command routing, menu actions, gameplay listeners, and the match scheduler.
- Replaced dummy join/team/kit/ability/setup/reset responses with actual operations and useful validation errors.
- Fixed MiniMessage, legacy color, menu, scoreboard, and safe placeholder rendering.
- Implemented arena creation, locations, selection capture, validation, normal/hardcore rounds, King damage, team rules, spectators, respawns, and results.
- Implemented team loadouts, authenticated ability items, cooldowns, finite charges, healing, spells, throwing axes, and controlled TNT.
- Implemented confirmed kit purchases with persistent ownership and atomic coin changes; retained compatible statistics and database schema.
- Implemented bounded FAWE capture and serial restoration, interrupted-round markers, and arena locking during restoration.
- Repaired player inventory/state escrow, safe returns, quit/restart recovery, and recovery-command session cleanup.
- Added optional native PlaceholderAPI integration and tested Multiverse worlds. Kept the King chunk loaded during a round so a separate-world lobby does not prevent objective spawning.
- Added permission checks, session/owner tags, GUI validation, boundary protections, duplicate reward/death protection, and bounded persistence execution.
- Corrected unchanged bundled kit/ability defaults, with backups and preservation of edited files.
- Added editable Maven source, setup documentation, 17 automated tests, and live integration test notes.
