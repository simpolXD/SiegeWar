# AbyssalDominionSiegeWar 1.0.3

A Paper castle siege minigame with kits, a King objective, siege coins, statistics, leaderboards, inventory recovery and FAWE map regeneration.

| Release field | Value |
| --- | --- |
| OLD VERSION | 1.0.2 |
| NEW VERSION | 1.0.3 |
| Target | Paper 26.2; Java 25 |
| Database | Private H2 2.3.232 and private HikariCP 6.3.0 |
| CHANGELOG | [CHANGELOG.md](CHANGELOG.md) |
| FIXED ISSUES | [RELEASE_NOTES.md](RELEASE_NOTES.md) |
| FILES MODIFIED | [FILES_MODIFIED.md](FILES_MODIFIED.md) |
| Verification | [TEST_REPORT.md](TEST_REPORT.md) |

Read [UPGRADE_GUIDE.md](UPGRADE_GUIDE.md) before replacing an installed version. This release changes the H2 namespace and consolidates kit/ability settings.

## Resulting behavior

- Joining preserves participants' own inventory, armor, offhand, XP, hunger, health, absorption, effects and game mode. A durable session-bound snapshot is committed before the first teleport or issued item. Spectating necessarily changes game mode after capture.
- Waiting tools and kit items use available storage slots. Kit armor uses empty armor slots; occupied armor remains owned by the player. Spare kit armor can use storage space. A full inventory causes missing kit pieces and one warning, never replacement of belongings or ground spills. Kit purchases unlock access; the unlock persists when a particular round has insufficient slots.
- Own items remain available for combat. Own consumables, dropping, external inventories, crafting and item transfer are restricted during sessions so restoring a snapshot cannot refund exported items. Own equipment durability and own bow ammunition are protected. Issued consumables belong to the match. Leaving applies the authoritative snapshot once and saves player data before consuming its recovery record.
- Match starts and respawns use the player's current legitimate maximum health. The plugin does not set a fixed 20 HP attribute. Leaving restores saved health after restoring effects, within the current legitimate limit.
- Damage uses normal cancellable Bukkit damage events and vanilla mitigation. Real death events retain attribution for other death/lifesteal listeners. There is no forced King kill or damage multiplier. Ordinary lethal vanilla hits remain possible with the server's weapons, attributes, effects and configuration; this is not an artificial damage cap. Ability base amounts remain configurable.
- Configured blacklisted items stay in the owner's inventory but cannot function. Warnings appear at entry and attempted pickup/use/equip. Defaults cover elytra, wind charges, fireworks, tridents, ender pearls, chorus fruit and maces. `blacklisted-items: []` disables the material check. Flight rules and the separate effect blacklist still apply.
- Active participants may break vanilla breakable terrain inside their own arena. Drops and block XP are suppressed; placement and container extraction remain blocked. The plugin temporarily permits breaking in its own exact `asw_<arena>` cuboid, journals the original WorldGuard flag, and restores it at match end or restart. Other plugins' denials are respected. Custom/overlapping WorldGuard regions may require administrator configuration.
- Match completion, abort and reset regenerate the captured FAWE template after players leave. Container data is included. Dirty/reset markers prevent reuse after interrupted rounds. Disabling `regenerate-after-match` leaves the arena unavailable until a manual reset.

## Dependency compatibility

| Component | Target / role | Evidence for this release |
| --- | --- | --- |
| Paper | 26.2 build 135; Java 25 | Compilation and isolated server checks |
| FastAsyncWorldEdit | 2.16.2; required | Real capture/reset checks |
| WorldGuard | 7.0.19; required by default config | Loaded in the staging server; see test report |
| PlaceholderAPI | 2.12.3; optional | Compiled adapter retained; live behavior listed in test report |
| Multiverse-Core | Optional world manager | Integration retained; see verification limits |
| Geyser-Spigot / floodgate | Optional Java/Bedrock crossplay | Ordinary inventory menus and UUID identity retained; physical Bedrock not verified |
| ViaVersion / ViaBackwards | Optional protocol translation | Used only as staging infrastructure; not bundled or required by this plugin |
| H2 / HikariCP | 2.3.232 / 6.3.0 | Private runtime packages; packaged isolation test |
| Folia / Paper 26.3 | Outside this release's verified target | NOT VERIFIED |

FAWE supplies the WorldEdit implementation. Do not install a second conflicting WorldEdit implementation. Dependencies are separate plugins; this release does not install or update them.

## First arena setup

Use `castle` below as an example ID. IDs use 1–32 lowercase letters, digits, `_` or `-`.

1. Stand at a safe lobby outside every arena's reset bounds:

   ```text
   /siegewar setlobby
   /siegewar arena create castle
   ```

2. Set a cuboid encompassing the complete map, all combat spawns, the King and the spectator point. Use both opposite corners, including floor and roof height:

   ```text
   /siegewar arena setbounds castle x1 y1 z1 x2 y2 z2
   ```

   Replace the six placeholders with integer coordinates in your current world. Alternatively, select both corners with FAWE and run `/siegewar arena setbounds castle`. `arena pos1 castle` and `pos2` are another option.

3. Stand at each corresponding location inside the cuboid:

   ```text
   /siegewar arena setspawn castle attackers
   /siegewar arena setspawn castle defenders
   /siegewar arena setspawn castle king
   /siegewar arena setspawn castle spectator
   ```

   The waiting and exit locations must remain outside reset bounds. `setlobby` supplies their defaults for new arenas. Change them with `arena setspawn castle waiting` and `exit` if needed. If existing spawns fall outside new bounds, correct the spawns/bounds before saving the new setup.

4. Finish building, remove players/items from the map, then capture it:

   ```text
   /siegewar arena capture castle
   /siegewar arena validate castle
   /siegewar arena enable castle
   ```

   Capture uses saved bounds; without bounds it uses the current FAWE selection. It creates `asw_castle` when no region is configured and WorldGuard is available. With saved bounds, you may capture from the safe lobby in the same world. Wait for completion before enabling. A missing spectator point defaults to the defenders' spawn.

5. Play or test with at least two players:

   ```text
   /siegewar join castle
   /siegewar team attackers
   /siegewar kits
   /siegewar arena start castle
   ```

   The last command requires management permission. Both teams must have eligible players. World PvP and King mob spawning must be allowed. The configured King entity must be able to exist in the world's difficulty and region settings.

## Commands and permissions

Aliases: `/adsiege` and `/asw`. Administration also supports the `/siegewar admin ...` wrapper.

| Command | Permission suffix / purpose |
| --- | --- |
| `/siegewar`, `menu`, `help`, `version` | `use` for menus; help/version show information |
| `join <arena>`, `randomjoin`, `team <attackers\|defenders\|auto>` | `join` for joining; `use` for team changes |
| `kits`, `kit <id>`, `buykit <id>` | Kit selection or an explicit coin purchase confirmation |
| `ability <id>` | `use`; requires a currently registered issued item from the selected kit |
| `spectate <arena>` | `spectate` |
| `leave`, `recover` | Recovery remains reachable without join/use permission |
| `stats [player/UUID]`, `top [wins\|kills\|deaths]` | `stats` |
| `setlobby`, `arena create/setbounds/setspawn/setregion/capture ...` | `admin.setup` |
| `arena enable/disable/start/stop/reset <id>` | `admin.manage` |
| `arena mode <id> <normal\|hardcore>`, `timer <id> <seconds>`, `countdown <id> <seconds>` | `admin.setup` |
| `coins <give\|take\|set> <player/UUID> <amount>` | `admin.coins`; changes internal siege coins |
| `diagnostics`, `arena validate <id>` | `admin.diagnostics` |
| `reload` | `admin.reload`; only while idle with recovery/template work finished |

All suffixes begin with `abyssaldominionsiegewar.`. The parent `abyssaldominionsiegewar.admin` grants administration. Paid kit nodes follow `abyssaldominionsiegewar.kit.<kit-id>`. A paid kit is usable through its permission or a stored unlock; free defaults remain available. Siege coins are this plugin's currency, separate from CMI/Vault balances.

## Configuration and integrations

`config.yml` is the active home for global rules, blacklist, database settings, `kits:` and `abilities:`. `arenas.yml` owns arena geometry, spawns and per-arena mode/timers. Messages, menus and scoreboards retain their respective YAML files. Do not keep editing legacy `kits.yml`/`abilities.yml` after migration: they are reference copies and no longer read during ordinary reloads.

Global rules include team capacity/balance, minimum players, round/countdown/respawn times, King type/HP, rewards, command rate, schematic volume/bytes, reset workers and projectile/entity caps. Arena timers set by commands override their creation defaults. Invalid YAML, duplicate keys, invalid materials and invalid numeric settings fail validation. Reload validates a candidate before swapping it. Database paths, integration settings and reset worker count require a full restart.

Storage is embedded and offline; it exposes no database host/port, password or TCP server. Paths stay beneath the plugin data folder. The original H2 file is converted through SQL when upgrading from 1.0.2; an isolated bundled reader performs that one-time conversion. `.h2-namespace` and migration journals are part of the database state. Keep them with the data folder. [UPGRADE_GUIDE.md](UPGRADE_GUIDE.md) explains backups and rollback.

PlaceholderAPI expansion identifier: `abyssaldominionsiegewar`. Use the existing configured scoreboard/placeholder keys. Install and configure Geyser/Floodgate separately for Bedrock. Player identity, rewards, recovery and purchases use server UUIDs rather than display names. If proxy authentication or cross-world inventory plugins change inventories outside normal events, test that combination before a public rollout.

## Build

Use a Java 25 JDK and Maven:

```bash
mvn -B -ntp clean verify
```

The distributable is `target/AbyssalDominionSiegeWar-1.0.3.jar`. Use the shaded JAR, not `original-*.jar`. Dependencies are pinned in `pom.xml`; Maven embeds a private migration reader and rewrites H2's internal resource archive as part of the build. Verification source under `verification/` is staging infrastructure and is excluded from the playable JAR.

See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) and [TEST_REPORT.md](TEST_REPORT.md) for observed checks and remaining limits. No claim of universal bug freedom, exploit immunity or compatibility with every server plugin is made.
