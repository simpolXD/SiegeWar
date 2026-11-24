# Upgrade and rollback — 1.0.2 to 1.0.3

## Install the update

1. Finish/stop matches and let players leave. Stop Paper completely.
2. Back up the old JAR, the complete `plugins/AbyssalDominionSiegeWar/` folder and the server's matching player data. Keep this backup outside the working plugin folder.
3. Remove the old active JAR from `plugins/`. Install **only** `AbyssalDominionSiegeWar-1.0.3.jar` there. Keep the existing data folder and compatible dependencies.
4. Start normally on Paper 26.2 with Java 25. The configuration migration copies legacy kit/ability definitions into `config.yml` and keeps originals under `backup/pre-1.0.3/` when needed. Existing edited settings take precedence over defaults.
5. The database migration exports an unshaded 1.0.2 database through the bundled isolated reader. It validates a replacement through SQL, retains a SQL export plus the original binary, writes a migration journal and atomically installs the converted database. No live database is copied over while a pool is using it. Do not interrupt this first startup deliberately.
6. Run:

   ```text
   /siegewar version
   /siegewar diagnostics
   /siegewar arena validate castle
   ```

   Replace `castle` with each arena ID. Expected versions: plugin **1.0.3**, H2 **2.3.232**, HikariCP **6.3.0**; the driver name begins with `com.abyssaldominion.siegewar.internal.h2`.
7. If an arena reports an interrupted round/reset, move everybody outside its bounds and run `/siegewar arena reset <id>`, wait for completion, validate and enable it. Do not recapture a damaged map as its intended baseline.
8. Test join/leave with identifiable owned inventory, armor, offhand, XP and non-default maximum health before opening public matches. Test your actual CMI, lifesteal, protection, inventory/world manager and crossplay stack.

## Important files

| Path under the plugin folder | Purpose |
| --- | --- |
| `config.yml` | Rules, blacklist, integrations, database paths, kits and abilities |
| `arenas.yml` | Bounds, spawns, enable state, mode and per-arena timers |
| `messages.yml`, `menus.yml`, `scoreboards.yml` | Presentation settings |
| `database/siege.mv.db` | Default active database |
| `database/siege.h2-namespace` | Database implementation namespace marker; keep with the database |
| `database/siege.migration.pending` | Present only during an unfinished database conversion; do not delete it |
| `backup/legacy-before-shading-<UUID>.sql` | SQL export retained before namespace conversion |
| `backup/legacy-original-<UUID>.mv.db` | Original unshaded file retained for rollback with the matching old runtime |
| `backup/before-restore-<UUID>.sql` | Safety export taken before an SQL import |
| `backup/pre-1.0.3/` | Original configuration/legacy definitions from config migration |
| `schematics/<arena>.schem` | Captured authoritative map template, including block entities |
| `schematics/<arena>.dirty`, `<arena>.resetting` | Interrupted match/reset markers |
| `protection-recovery/<arena>.yml` | Original managed WorldGuard block-break flag awaiting restoration |

Paths above use defaults; retain the configured relative paths if customized. An unfinished conversion resumes from its journal on the next enable. If validation fails, the plugin disables rather than discarding the original database. Stop the server and inspect the exception/backups before retrying; do not delete recovery state to force startup.

## SQL import and export

`SiegeStore.backup(Path)` and `restore(Path)` are administrative storage APIs, not player commands. They accept validated plain `.sql` filenames beneath the configured backup root, reject parent traversal/symlinks and refuse backup overwrite. Restore preflights the script in a disposable database, protects the existing database with a safety export, serializes maintenance with normal store work, and refuses to discard pending inventory escrows.

Use these APIs only through controlled administrative tooling with the server stopped or the store otherwise exclusively managed. This release adds no remote SQL console, TCP database listener or arbitrary player SQL command. Stop Paper before file-level backup/rollback. Prefer SQL for transfer between namespaces; `BACKUP TO` is not used.

## Roll back

1. Stop Paper completely.
2. Restore the pre-upgrade plugin JAR and its entire matching pre-upgrade data folder. Restore matching player data when returning the whole server to an earlier state.
3. Keep only that old JAR active, then start and verify recovery before allowing play.

Replacing only the JAR is insufficient after namespace conversion. The old runtime cannot read private class names in a converted 1.0.3 MVStore file. SQL conversion and the original binary backups are recovery options for administrators; do not mix independent database/player-data restore points during a live session.

## Configuration after migration

Edit `kits:` and `abilities:` inside `config.yml`. Old standalone `kits.yml` and `abilities.yml` are ignored during ordinary runtime/reload. Geometry still belongs in `arenas.yml`. Reload is deliberately rejected while matches, joins, inventory restoration or template jobs are active. Database paths, integration settings and reset-worker count changes require a full restart.

If breaking remains denied in an existing custom region, inspect its `block-break`/build flags, membership, overlapping priorities and other protection plugins. The plugin changes only its own exact `asw_<arena>` cuboid and never uncancels another plugin's denial. Keep lobby/exit points outside all reset areas.
