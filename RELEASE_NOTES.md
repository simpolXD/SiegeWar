# Release notes — 1.0.3

OLD VERSION: **1.0.2**  
NEW VERSION: **1.0.3**  
Target: **Paper 26.2 / Java 25**

## CHANGELOG

- Isolate H2 **2.3.232** and HikariCP **6.3.0** under private runtime packages. Bind the driver and thread context loader explicitly. Report matching build/runtime versions in diagnostics, metadata and the version command.
- Upgrade Maven Shade for Java 25, relocate H2 resource paths and its packed resource archive, and retain the JDBC service descriptor.
- Convert legacy 1.0.2 database files through `SCRIPT`/`RUNSCRIPT` with an isolated offline reader. Preserve the original binary and SQL export, validate a staged replacement, and use a resumable journal and atomic file replacement. Preserve the namespace marker with the database.
- Preserve participants' own state at join. Fill available inventory/armor slots with kit gear; retain owned items when slots are occupied or full. Restore effects before saved health/absorption.
- Register issued items by a unique ID bound to player, arena generation, combat life, kit, ability/action and material. Metadata, names and lore alone cannot authorize abilities.
- Use native lethal damage/death events, legitimate maximum health and attributed ability damage. Keep TNT from damaging the King.
- Enable native terrain breaking during active rounds, journal/restore the plugin's managed WorldGuard break flag and retain FAWE regeneration and dirty markers.
- Add configurable disabled materials/effects, warnings, bounded entity tracking and reset admission. Remove world-wide entity scans from match cleanup and overlapping guard handlers.
- Keep stale menu, respawn and interaction callbacks tied to their original connection/session. Keep same-match cooldowns across leaving/rejoining and do not reset charges on respawn.
- Consolidate kit and ability definitions in `config.yml`, retaining legacy edits through one-time migration and backup. Keep idle-only validated reloads; infrastructure changes require restart.

## FIXED ISSUES

| Earlier behavior | 1.0.3 behavior |
| --- | --- |
| Shared H2/Hikari packages could resolve another plugin's version | Private packages and explicit driver binding |
| Relocation could make an old MVStore file unreadable | SQL conversion before opening with the private runtime |
| Joining cleared inventory, XP, effects and other owned state | Entry preserves owned state; snapshot precedes mutations |
| Kit slots and armor replaced existing contents | Only empty slots receive issued gear |
| Fixed 20 HP spawn restoration | Current legitimate maximum health |
| Lethal damage was intercepted as a virtual defeat | Native death and cancellation semantics |
| Map breaking defaulted off and managed region denied building | Active break policy plus journaled managed-region flag |
| Item authorization relied on copied metadata | Server-side unique issued-item registry |
| Cleanup scanned loaded entities in entire worlds | Bounded per-session entity ownership map |
| Literal command syntax could render a broken HTML entity | Literal component placeholder |

## FILES MODIFIED

See [FILES_MODIFIED.md](FILES_MODIFIED.md). Editable source and the complete Markdown notes are included in the update package.

## Operational notes

- Stop the server for the upgrade and back up the entire plugin folder and player data first. Do not hot-load this JAR.
- Keep `.h2-namespace` and any migration journal with the database. For rollback, restore the pre-upgrade JAR **and** its complete matching data backup; a private 1.0.3 MVStore file must not be handed directly to the old unshaded runtime.
- `config.yml` now owns kit/ability definitions. Legacy YAML files are retained for reference after conversion.
- Restrictions disable owned blacklisted items in place. They do not confiscate them.
- A full inventory can prevent some issued kit pieces from fitting. Make space before joining when the full kit is desired.
- Native vanilla hits can still be lethal. This release removes forced kills and fixed-health overrides; it does not override legitimate server combat attributes.
- Review [TEST_REPORT.md](TEST_REPORT.md) before rollout. Physical Bedrock, the complete production plugin stack and hardware power-loss behavior require separate validation.
