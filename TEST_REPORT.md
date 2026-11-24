# Verification report — AbyssalDominionSiegeWar 1.0.3

Date: **2026-11-14 (Asia/Manila)**. OLD VERSION: **1.0.2**. NEW VERSION: **1.0.3**.

Final JAR SHA-256:

```text
ba621a1f5a69c74ed2f2e1d0b0ffdfcbe8733027a1c573f2207093c1bf934abe
```

Input 1.0.2 JAR SHA-256: `928fca90a73e7b009ab99b35170124a9dc1715a8c09cf48517df0b7ab51d400d`.

## Final results

| Check group | Observed result |
| --- | --- |
| Java 25 / Maven clean verify | Exit **0**; **33 tests**, 0 failures, 0 errors, 0 skipped |
| Packaged upgrade/isolation harness | Exit **0**; **9 PASS** checks |
| Real Paper session harness | Exit **0**; **43 PASS** checks; two connected clients and two successive matches |
| JAR inspection | Passed; Java class major **69**, **1296 unique entries**, no top-level unshaded H2/Hikari classes |
| Version agreement | JAR filename, POM, plugin.yml, manifest and build properties agree on **1.0.3** |
| Dependency metadata | H2 **2.3.232**, HikariCP **6.3.0**, private JDBC service descriptor and H2 resource archive present |
| Change whitespace check | `git diff --check` exit **0** |

These are observed results for this artifact, not a claim of universal bug freedom or exploit immunity. Historical reports under `verification/1.0.2/` and `verification/1.0.1/` are not results for this JAR.

## Build and unit verification

Command goals: `mvn -B -ntp clean verify`, using Maven **3.9.11** and Corretto **25.0.5**. Runtime-local repository/proxy/trust-store settings were also supplied for dependency downloads. No API stubs or skipped tests were used. The build compiles against the actual provided Paper/FAWE/WorldGuard/PlaceholderAPI dependencies.

Final Surefire counts:

| Suite | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| ArenaRepositoryTest | 3 | 0 / 0 / 0 |
| IssuedRegistryTest | 2 | 0 / 0 / 0 |
| MatchEngineTest | 5 | 0 / 0 / 0 |
| MigrationJournalTest | 3 | 0 / 0 / 0 |
| SnapshotTest | 3 | 0 / 0 / 0 |
| StorageRecoveryTest | 6 | 0 / 0 / 0 |
| StoreTest | 8 | 0 / 0 / 0 |
| TextTest | 3 | 0 / 0 / 0 |
| **Total** | **33** | **0 / 0 / 0** |

Tests cover concurrent purchase/capture, overspend prevention, reward/death replay, rollback, stale escrow heads, snapshot checksums/round trips, geometry, literal text injection, issued-item identity/budgets, context-loader restoration, SQL import/export, unsafe paths, corrupted import preservation and interrupted migration journal recovery before/after atomic installation.

Build output ends with:

```text
Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Raw XML/text reports and the final build log are in `verification/1.0.3/`. The build emits a benign shared-manifest resource notice during shading; the final retained manifest was inspected explicitly.

## Packaged compatibility verification

`verification/IsolatedStoreCheck.java` was compiled with Java 25 and run against the supplied 1.0.2 JAR and this final 1.0.3 JAR. It loads conflicting **H2 2.1.214** and **HikariCP 4.0.3** in a parent loader, then loads the new plugin through a separate child loader.

Observed: original thread context restored; H2 and Hikari resolve to the plugin's private loader; a database written by unshaded 1.0.2 retains a **77-coin balance**, purchased **wizard** kit, pending inventory escrow session and exact snapshot bytes after conversion. `SCRIPT`/`RUNSCRIPT` through the packaged shaded implementation also preserves the balance. The transcript ends with `PACKAGED_ISOLATION_COMPLETE`.

This catches the MVStore class-name incompatibility that a plain package relocation would otherwise introduce. A bundled isolated reader converts old files before the private runtime opens them. Interruption tests simulate file/journal layouts; they do not simulate actual storage-device failure.

## Real server verification

Environment:

- Paper **26.2 build 135**, Corretto **Java 25.0.5**.
- FastAsyncWorldEdit **2.16.2**, WorldGuard **7.0.19**.
- ViaVersion/ViaBackwards **5.12.2** for the two available Java **1.21.11** protocol test clients. These translation plugins are test infrastructure, not plugin requirements.
- An isolated loopback-only offline-mode server, disposable arena and staging-only native API harness. Dependency telemetry was disabled for the final run.
- The staged test JAR was byte-compared to the final deliverable; SHA-256 matches the value above.

The server-console `releasecheck` run completed **43 checks**, recorded verbatim in `verification/1.0.3/native-checks.txt`. Main outcomes:

| Scenario | Observed result |
| --- | --- |
| Configuration | Invalid material and duplicate keys rejected; empty blacklist accepted; edited legacy kits/abilities migrated; defaults added; repeated idle reload retained one listener set |
| Setup | Router-created geometry/spawns/managed region and real FAWE capture validated |
| Entry | Identifiable owned hotbar item, armor, offhand, XP, 34 HP, 4 absorption, hunger and potion effect remained intact |
| Kits | Occupied own slots remained; full 36-slot storage was not overwritten; a second match restored the full inventory exactly |
| Health | Match spawn and actual death/respawn respected a legitimate **40 HP** maximum; King used configured **30 HP** |
| Damage | Native 2 HP source damage resulted in about **1.04 HP** after vanilla armor mitigation, applied once; later cancellation prevented death/reward side effects; ordinary King hit did not force death; TNT could not damage King |
| Items and interaction | Unknown issued ID rejected; wind-charge and glide events denied without confiscation; external inventory and creative injection denied; already-denied item interaction did not schedule an ability or consume a charge |
| Cooldowns | Repeated ability use kept the existing cooldown; another player's death did not reset it |
| Terrain | Native left-click interaction and participant block/chest breaking permitted in the managed cuboid |
| End/recovery | Both players' exact original inventories, XP and health restored; escrow consumed only after durable return; second match remained idempotent |
| Regeneration | Broken stone and a chest containing three diamonds returned from the real FAWE template; original WorldGuard break flag restored |

Some protection cases dispatch real Bukkit event classes through the server harness; they are not full client-packet or physical Bedrock tests. Block breaking, damage, death, respawn, teleports, inventory restoration, capture and paste use the real server APIs and entities.

No plugin error or stack trace occurred in the final startup, match, reset and shutdown paths. The restricted offline staging environment separately logged a Mojang public-key DNS failure from Paper/authlib. That environmental message is not attributed to this plugin and is not claimed to be fixed by it. Standalone database tests also emit SLF4J no-provider notices because Paper's logger is absent from that standalone process.

## NOT VERIFIED / remaining rollout work

- Physical Bedrock/Geyser/Floodgate interaction; the exact production CMI, lifesteal, protection and 119-plugin stack.
- Live PlaceholderAPI/Multiverse behavior in this update, all eight kit/ability combinations, all block-entity/NBT forms, stress/long-duration load and multiple occupied arenas simultaneously. Existing features remain in source; historical coverage is archived separately.
- New-version live process-kill/power-loss tests with active players. Database migration interruption layouts and snapshot/storage regression tests passed; actual hardware failure was not simulated.
- Arbitrary administrator or external-plugin inventory edits/grants during matches, hostile server plugins, and return worlds removed by other systems. The pre-entry snapshot is authoritative; recovery retains its record when a safe return is unavailable.
- Paper 26.3 and Folia compatibility.

See [SECURITY_REVIEW.md](SECURITY_REVIEW.md) for the required security/deduplication review and [UPGRADE_GUIDE.md](UPGRADE_GUIDE.md) for deployment and rollback.
