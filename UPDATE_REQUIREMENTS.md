# Prompt for ChatGPT — AbyssalDominionSiegeWar rework

Send the text below to ChatGPT. It is based on static inspection of your build
`AbyssalDominionSiegeWar-1.0.2.jar` (no source tree present), so ChatGPT should
first unpack the JAR, read the actual classes/resources, then apply the fixes
below and deliver the corrected source + JAR.

## 0. Delivery contract (non-negotiable)

- Act as **Senior Software Engineer** and **Senior Minecraft Plugin Engineer**,
  with expertise in Java, Paper, multiplayer minigames, crossplay (Geyser/Floodgate),
  FAWE, WorldGuard, Multiverse, PlaceholderAPI, permissions, transactional storage,
  and exploit prevention.
- **Senior Architecture** review required for every design decision before coding.
- Compile, test, resolve failures, deliver the corrected source and the FINAL JAR.
  Do not finish with a plan, pseudocode, snippets or unfinished methods.
- If a tool, install or runtime is missing to verify a claim, say so and mark it
  NOT VERIFIED instead of inventing evidence.
- **Security & exploit resistance is a release requirement**, not a nicety:
  no known critical exploits, inventory-loss vectors, or duplicated-state bugs
  in the tested environment.
- **Deduplication is a release requirement**: no duplicate code paths, listeners,
  tasks, scoreboards, teams, item IDs, rewards, purchase charges, or inventory
  restore paths anywhere in the delivered tree. Single source of truth, enforced.
- Do not promise that any software is bug-free, unexploitable, or production-ready.
  Demonstrate protections with tests and report remaining risks explicitly.

## 1. Role assignments for the agent (adopt all)

- **Senior Software Engineer**: correctness, reliability, maintainability,
  production-ready code, compile-time soundness.
- **Senior Minecraft Plugin Engineer**: Paper API compatibility, Bukkit event
  semantics, inventory/economy/session lifecycle, Crossplay (Geyser/Floodgate)
  correctness, FAWE/WorldGuard/Multiverse adapters.
- **Security & Exploitation Analyst**: threat-model every player-facing input and
  event path; ensure no bypass, abuse, duplication, or privilege escalation.
- **Performance Engineer**: low CPU footprint, bounded work, no per-tick world
  scans, no leaking entities/tasks/connections.
- **Database Engineer**: H2/HikariCP isolation and pooling correctness.

## 2. Current project state (ground truth)

From your build, verified by static inspection:
- Name: `AbyssalDominionSiegeWar`, version `1.0.2`, artifact ID `abyssaldominionsiegewar`.
- Package: `com.abyssaldominion.siegewar`; main class `AbyssalDominionSiegeWarPlugin`.
- Entry points & commands: `/siegewar` (+ aliases `/adsiege`, `/asw`).
- Dependencies in `plugin.yml`: hard `FastAsyncWorldEdit`; soft `WorldGuard`,
  `Multiverse-Core`, `PlaceholderAPI`, `Geyser`, `Floodgate`, `ViaVersion`.
- `config.yml` global defaults: `countdown-seconds`, `round-seconds`, `respawn-seconds`,
  `team-capacity`, `max-team-difference`, `min-players`, `king-health`, `king-type`,
  `kill-coins`, `win-coins`, `max-arena-blocks`, `max-schematic-bytes`,
  `max-concurrent-resets`, `max-arenas`, `require-worldguard`, `require-multiverse`,
  `scoreboard-enabled`, `command-interval-millis`, `spectator-limit`, `allowed-commands`.
- `abilities.yml`: `rage`, `ragnarok`, `sight`, `oathstone`, `attack_wand`,
  `support_wand`, `throwing_axe`, `throwable_tnt`.
- `kits.yml`: 8 kits (Marksman, Spearman, Wizard, Knight, Skald, Bombardier,
  Berserker, Warrior). Armor order: boots, leggings, chestplate, helmet.
  `default-kit` marks the free default.
- Resources: `config.yml`, `abilities.yml`, `kits.yml`, `messages.yml`,
  `menus.yml`, `arenas.yml`, `scoreboards.yml`, `plugin.yml`.
- The plugin embeds H2 and uses HikariCP for pooling. This is the known technical
  problem: a different plugin shipping a different H2 version can clash.

## 3. Required fixes (apply all)

### 3a. Reliably isolate H2 and HikariCP (the technical defect)
- Eliminate any cross-plugin H2/HikariCP version clash. Prefer one of:
  (1) shade a fixed, private copy of H2 + HikariCP under a dedicated shaded
      package/namespace, or (2) pin the server-provided H2/HikariCP version and
      fail open with a clear error if the runtime version is unsuitable.
- Bind the H2 JDBC driver to the correct classloader before HikariCP pool creation:
  set `driverClassName = org.h2.Driver` and ensure the driver is resolvable on the
  thread-context classloader (HikariCP fails at pool init with `No suitable driver`
  otherwise). Do not delegate driver resolution to the plugin classloader alone.
- Keep the embedded SQL store offline-safe: validate the schema on enable, run
  versioned migrations, and back up/restore via `SCRIPT TO` / `RUNSCRIPT FROM`
  (NOT `BACKUP TO`, which leaves an unreopenable `.mv.db`) when persistence is
  needed. Constrain every backup/restore path to validated plain `.sql` files
  beneath an explicit root; reject traversal and unsafe destinations.
- Report the H2/HikariCP version used in `/siegewar diagnostics` and in release
  notes, and keep it in sync across `pom.xml`, the shaded JAR entry, `plugin.yml`
  and the version command.

### 3b. True inventory ownership (no unverified inventory loss)
- When a player enters an arena, they maintain their OWN inventory. Do NOT clear
  the player's inventory, XP, hunger, health/absorption, effects, flight or game
  mode at join. Save a versioned, session-bound snapshot (inventory, armor,
  offhand, relevant cursor/slot state, XP, health/absorption, hunger, game mode,
  potion effects, flight, return world/location/orientation) only when the plugin
  actually mutates those states (arena entry/exit, death, round end, restart).
- Bought kits are match-owned gear. At game end (match finished / reset), remove
  ONLY the items the game itself issued (bought kits + issued gear). Never remove
  the player's pre-existing own inventory items, armor, offhand or balance.
- Restore the player's own inventory idempotently by applying the authoritative
  snapshot (replace state), not by repeatedly adding items. Keep the snapshot
  until restoration succeeds and persist the restored state.
- On kick, disconnect, teleport failure, arena shutdown, plugin disable and
  restart, protect items that the plugin may not touch: crafting, furnaces,
  anvils, hoppers, ender chests, item frames, trades, armor stands, portals,
  cross-world transfers. On successful restore, the player regains their own
  contents and no siege gear escapes the session.

### 3c. Arena blocks: breakable at start, regenerate at end
- At match start, mark all arena-blocks within the arena bounds breakable (remove
  any block-protection state) so players can interact with the terrain.
- After the match ends (victory, defeat, abort, reset, cancel), regenerate the
  arena blocks to their template/defaults (schematic or per-block defaults).
- Persist the pre-match block state so regeneration restores exactly what was
  there; rely on `FastAsyncWorldEdit` capture as the source of truth and verify
  block-entity/container data restored. Never paste through an occupied match.
- Ensure registration works on Paper 26.2/Java 25.

### 3d. Vanilla damage only (no 1-hit kills)
- Combat damage must use normal vanilla damage only. Remove/ignore any damage
  multiplier, 1-hit-King, instant-kill, or mitigation override.
- Use the player's current legitimate max health (respect attribute bases,
  `regainHealth`, external effects, lifesteal). Do NOT force a fixed 20 HP.
- Apply damage through an attributed, cancellable path and respect damage
  policies (e.g. Bombardier TNT must NOT damage the King by default). Verify
  callers and the kill event ordering. Expose the configured `king-health` as the
  King's max health.

### 3e. Un-exploitable, optimised, low CPU, inventory saves
- Isolate every match/arena by UUID (match generation token); expired respawns,
  teleports, projectiles, forms, database callbacks and countdown handlers from
  an old match must never modify a new one. Use generation tokens on every
  delayed callback.
- Keep per-tick cost bounded: do not scan the whole world or every entity per
  tick. Batch bulk operations (regeneration, respawns, cleanup) into
  off-main-thread work where the API allows it, then return to the server thread
  before applying. Cap entities, projectiles, particles, queue length,
  schematic volume, reset concurrency and menu/command request rates.
- Fence all delayed callbacks with the generation/session token, not merely a
  captured old object. Re-validate session/generation and online state before
  applying any result.
- Inventory snapshots must be written atomically and only on state changes; do
  not persist inventory on every tick or every ability use.

### 3f. Disallow combat-bonus items (elytra, windblast, and alike)
- Disable/neutralise any item that can grant a huge advantage in arena: elytra,
  windblast, and any other flight/levitation/agility/bypass item that would
  trivialise combat or break the damage model.
- All disabled items are listed in `config.yml` under a `blacklisted-items:` list.
- When a player joins the arena or picks up a blacklisted item, send them a
  WARNING message (e.g. "<red>Elytra/<item> is disabled in the arena and will not
  function.</red>") and remove/disable it so it cannot be used to bypass arena
  rules. The warning must be presented at arena entry and on pickup/equip.
- The blacklist is fully configurable: add/remove items in `config.yml`; a blank
  list disables the check. Respect the config key types, reject invalid material
  names at load with a clear error, and reload cleanly.

### 3g. Config-driven everything
- Every feature/requestable behaviour must be adjustable in `config.yml`:
  arena max players, team capacity/limits, team difference, min players, round
  time, respawn delay, King HP and type, kit prices (including free kits), kit
  grants, cooldowns, charges, damage/healing amounts, radius/targets/duration,
  currency award (kill coins, win coins), respawn flags, hardcore rules,
  blacklisted items, block regeneration flags, reset concurrency, port/host,
  database path, and all integrations (WorldGuard, Multiverse, Geyser/Floodgate
  presence). Defaults remain usable without editing the file.
- Report the effective config values (at least the key ones) in
  `/siegewar diagnostics`.

### 3h. Preserve behaviour you are not asked to change
- Do not remove: kit selection, shop/buying, stats, leaderboards, scoreboards,
  the match state machine, King objective, cooldown management, cooldowns not
  resetting on kit switch/death/rejoin/reload, kit prices, FAWE capture/reset,
  PlaceholderAPI expansion, Geyser/Floodgate crossplay flows.

## 4. Security & unexploitable review (mandatory before completion)

Before delivering, the agent must run these reviews and report the result of each:

- [ ] **Input validation**: every player-supplied value (arena id, kit id, ability
      id, coordinates, material names, UUIDs, prices, cooldowns, amounts) is
      validated against its schema and type, with clear errors. No raw player name
      or message is interpreted as executable command/formatting.
- [ ] **No SQL injection**: all SQL uses bound parameters; SQL identifiers (table
      name, column name) are whitelisted, never interpolated from player input.
- [ ] **No name-based identity**: identity is authenticated server UUID (including
      Floodgate-prefixed names); display names are never treated as unique identity
      and never drive rewards, sessions, or cooldowns.
- [ ] **Session isolation**: a player's session state cannot be read/modified by
      another player or an unrelated survival player. Match/arena must not leak
      across players or across rounds.
- [ ] **Inventory duplication prevention**: issued items are bound server-side by
      unique item ID + player + arena + match generation + registered item ID.
      A renamed item, matching lore, or material alone must never grant an ability.
      No duplication route via ground drops, XP orbs, projectiles, or cross-world.
- [ ] **Reward/event deduplication**: unique keys per match/result/reward event
      prevent double-awarding kills, wins, coins, or project reward.
      Purchase events are conditional/debit-locked with idempotency.
- [ ] **Event ordering**: respect cancellation/adjusted amounts; never bypass
      another plugin's denial via MONITOR priority or direct mutation that
      overrides protections. Verify knight/knight-bypass and lifesteal hearth
      handling.
- [ ] **Config security**: no secrets/tokens/passwords/connection strings in
      config.yml or anywhere in source; mask any accidental hit as [REDACTED] in
      reports.
- [ ] **Fly/flight bypasses**: disable elytra, windblast and any flight-granting
      item; verify no flight/levitation/effect-based bypass of arena rules.
- [ ] **Permission semantics**: permissions checked at service commit boundaries
      (not only UI visibility); leaving/recovery must remain available when an
      access permission is revoked so a player cannot be trapped.
- [ ] **Reload safety**: reload must not duplicate listeners/tasks, replace a
      running match's rules halfway through, lose snapshots, replay rewards, or
      bypass cooldowns. Defer incompatible changes to the next round.

## 5. Deduplication review (mandatory before completion)

Before delivering, the agent must run these checks and report the result of each:

- [ ] **No duplicate listeners/tasks/schedulers**: reload replaces, not appends;
      match state machine and timers are per-match UUID, never global reuse.
- [ ] **No duplicate scoreboard/teams/objects**: one authoritative owner per
      scoreboard, team, objective, boss bar, and arena entity; cleanup on match
      end removes only match-owned state.
- [ ] **No duplicate inventory restore paths**: one authoritative snapshot path
      (inventory, armor, offhand, cursor/slot, XP, health, effects, flight).
      Restore is idempotent (replace state) — not repeated additive application.
- [ ] **No duplicate item/ability definitions**: kits, abilities, cooldowns,
      charges, damage/healing amounts, radius/targets/duration are defined once,
      in yml + one service read path; no duplicate registrations of abilities.
- [ ] **No duplicate reward/currency flows**: coins and kit unlocks are a single
      atomic flow; no parallel paths that could charge twice or pay twice.
- [ ] **No shared mutable state across matches**: no global-singleton King,
      timer, leaderboard, or player map that could be read by the wrong match.
- [ ] **No duplicate config keys**: each config key has one authoritative owner;
      duplicate files or semantically duplicated keys produce a warning.

## 6. Verification (do the work, do not claim)

- [ ] Run a clean `mvn -B -ntp verify` (Java 25, `paper-api` provided) and report
      the actual exit status and Surefire counts; never accept skipped/empty test
      counts.
- [ ] Inspect the JAR: `jar tf`, read back `plugin.yml` and the manifest, verify
      the `Implementation-Version` entry and classpath entries match, and record
      the SHA-256 of the final artifact.
- [ ] Static checks: resolve every import/compile error from real signatures (use
      `javap` on the dependency bytecode when needed); do not hand-write stubs.
- [ ] Review the full diff for unintended duplicate code, leftover stubs, and
      security regressions. Report what was verified and what is NOT VERIFIED,
      with the exact command and output. Do not label a partially-tested build
      as production-ready.

## 7. Deliverables

- Fully readable, editable Maven source tree (the module layout that builds the
  plugin, with `engine`, `store` and `plugin` modules if you restructure).
- Final JAR: `AbyssalDominionSiegeWar-<new-version>.jar`.
- `plugin.yml` + `pom.xml` + manifest + resources kept in sync with a versioned
  release (patch for backward-compatible fixes).
- `config.yml` containing the blacklisted-items list and all feature toggles.
- README: dependency-compatibility matrix, Java/Bedrock deployment, permissions,
  commands, database/import, rollback, and this fix list. Include
  `OLD VERSION`, `NEW VERSION`, `CHANGELOG`, `FIXED ISSUES`, `FILES MODIFIED`.
