# Files modified — 1.0.3

OLD VERSION: **1.0.2**

NEW VERSION: **1.0.3**

## Runtime and build changes

| File | Change |
| --- | --- |
| `pom.xml` | Pinned versions; Java 25 shading; private libraries; isolated legacy reader; relocated H2 resource archive and filtered metadata. |
| `src/main/java/com/abyssaldominion/siegewar/Abilities.java` | Registered item authorization, bounded entity tracking, permissions and native terrain breaking. |
| `src/main/java/com/abyssaldominion/siegewar/AbyssalDominionSiegeWarPlugin.java` | Early protection recovery, private store initialization, configured integrations and restart-only settings. |
| `src/main/java/com/abyssaldominion/siegewar/ArenaGuards.java` | Map interaction/automation isolation; consolidated overlapping handlers. |
| `src/main/java/com/abyssaldominion/siegewar/BuildInfo.java` | Filtered build metadata validation. |
| `src/main/java/com/abyssaldominion/siegewar/DefaultUpgrade.java` | Back up and migrate legacy edited configuration without replacing active edits. |
| `src/main/java/com/abyssaldominion/siegewar/Events.java` | Inventory/creative gates, cancellation-aware interactions, native death, blacklist and session fencing. |
| `src/main/java/com/abyssaldominion/siegewar/Game.java` | Inventory-preserving sessions, issued-item registry, health/damage lifecycle, owned entity tracking and terrain policy. |
| `src/main/java/com/abyssaldominion/siegewar/ItemRules.java` | Disable owned restricted materials in place and rate-limit warnings. |
| `src/main/java/com/abyssaldominion/siegewar/Protection.java` | Lifecycle hooks for managed region policy. |
| `src/main/java/com/abyssaldominion/siegewar/Router.java` | Accurate diagnostics/version, recovery throttling exemption, exact bounds syntax and literal usage text. |
| `src/main/java/com/abyssaldominion/siegewar/Settings.java` | Strict YAML, numeric, material/effect and path validation; canonical configuration. |
| `src/main/java/com/abyssaldominion/siegewar/Snapshots.java` | Expose capture request identity for error fencing; restore effects before health/absorption. |
| `src/main/java/com/abyssaldominion/siegewar/Templates.java` | Bounded reset/capture admission, executor and schematic byte budget. |
| `src/main/java/com/abyssaldominion/siegewar/Views.java` | Menu connection/generation/config fencing and consistent purchase completion. |
| `src/main/java/com/abyssaldominion/siegewar/WorldGuardProtection.java` | Managed break-policy journal and restart recovery. |
| `src/main/java/com/abyssaldominion/siegewar/core/IssuedRegistry.java` | Unique server-side issued-item bindings and per-owner budget. |
| `src/main/java/com/abyssaldominion/siegewar/store/LegacyDatabaseMigration.java` | Isolated SQL conversion with retained backups, namespace marker and resumable atomic installation. |
| `src/main/java/com/abyssaldominion/siegewar/store/SiegeStore.java` | Driver isolation, schema validation, maintenance locking and safe SQL import/export. |
| `src/main/resources/abilities.yml` | Removed as an active resource; definitions consolidated into config.yml. |
| `src/main/resources/build.properties` | One source for artifact/dependency versions. |
| `src/main/resources/config.yml` | Canonical rules, kit/ability definitions, blacklist, integrations and storage limits. |
| `src/main/resources/kits.yml` | Removed as an active resource; definitions consolidated into config.yml. |
| `src/main/resources/messages.yml` | Disabled-item and insufficient-space messages. |
| `src/main/resources/plugin.yml` | Filtered version/storage metadata and integration names. |

## Verification and notes

- New tests: `IssuedRegistryTest`, `StorageRecoveryTest`, `MigrationJournalTest`. Existing five suites retained.
- `verification/IsolatedStoreCheck.java`: packaged compatibility/isolation checks.
- `verification/live/ReleaseVerification.java` and `release-plugin.yml`: staging-only native checks; excluded from the playable JAR.
- `verification/1.0.3/`: current build reports, manifest inspection and observed test evidence.
- Previous reports and harnesses are archived under `verification/1.0.2/` and `verification/1.0.1/`.
- Notes: `README.md`, `RELEASE_NOTES.md`, `CHANGELOG.md`, `UPGRADE_GUIDE.md`, `TEST_REPORT.md`, `SECURITY_REVIEW.md`, `FILES_MODIFIED.md`.
- `UPDATE_REQUIREMENTS.md` preserves the supplied update brief.
