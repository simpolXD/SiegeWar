# Security and duplication review — 1.0.3

Scope: the supplied 1.0.2 source/JAR and the changes recorded in this package. Evidence below identifies the protection and its verification level; it does not assert that every server combination is safe. Current execution results and release hash are in [TEST_REPORT.md](TEST_REPORT.md).

## Required security review

| Area | Protection / evidence |
| --- | --- |
| Input validation | Arena IDs, geometry, limits, prices, amounts and ability values are bounded; materials/effects are resolved from the real API; duplicate YAML keys and wrong numeric/boolean types are rejected. Native config cases cover invalid/empty blacklist and duplicate keys. Custom items and external plugin data require integration testing. |
| SQL injection | Player, event, reward, purchase and escrow values use prepared statements. Leaderboard columns are whitelisted. Administrative script paths are constrained and quoted; players have no SQL/script command. SQL imports are privileged administrator input. |
| UUID identity | Sessions, snapshots, ownership, cooldowns and rewards use server UUIDs. Names are display/lookup data. Production authentication/proxy configuration remains the server administrator's responsibility. |
| Session isolation | Each match has a UUID generation. Recovery also binds request/connection identity. Menus, respawn and item callbacks revalidate the current session/connection before applying state. Unit tests exercise stale match/escrow identities. |
| Issued items | A server-side registry binds unique item IDs to owner, generation, life, kit, ability/action and material. Copied lore/name/material cannot authorize an ability. Registry tests reject forged/expired bindings and cross-owner reuse; native item metadata rejection is checked. |
| Inventory replay/export | Snapshot commit precedes mutation. Recovery replaces contents and durably saves player data before consuming its escrow. Drops, external containers, crafting, hoppers, item-frame/armor-stand interaction, buckets and cross-boundary transfers remain restricted. Creative inventory injection is denied. Own consumables are restricted to prevent snapshot refunds after export/use. |
| Rewards and purchases | Unique database keys deduplicate match results, deaths/lives, purchase requests and coin events. Write serialization, conditional transactions, fingerprints and bounded balances prevent replay/overspend. Concurrent purchase/capture, reward replay and rollback tests run against real H2. |
| Event ordering | No forced damage/mitigation override or uncancellation of another plugin's denial. Native lethal deaths are used; actual damage and later cancellation are checked on Paper. TNT-to-King damage is denied. Lifesteal/CMI and other production listeners are NOT VERIFIED together in this release. |
| Config secrets | No credentials, tokens or remote connection strings are shipped. Storage is embedded and accepts constrained relative paths. Diagnostics report a whitelist of operational values, not secrets or arbitrary config dumps. |
| Flight and restricted items | Material blacklist blocks use/pickup/equip paths and glide events without confiscation. Flight changes are restricted for living combatants; forbidden effects are removed at spawn and rejected while active. Vanilla wind charges are covered; custom windblast items require their actual material/rules to be configured and tested. |
| Permissions | Commands and action services enforce relevant permissions; current kit ownership/price is checked at action time. Leave/recovery are exempt from access revocation and command throttling. Other plugins invoking public APIs remain trusted server code. |
| Reload | Reload is idle-only and validates candidates before replacement. It does not register more listeners/schedulers. Infrastructure changes require restart. Repeated idle reload listener counts are checked on the real server. |
| Storage isolation/upgrade | Runtime H2/Hikari are privately relocated; the driver is explicit and context-loader binding is restored afterward. A separate platform-parented legacy reader is used only for SQL conversion. Packaged checks include conflicting parent libraries and original 1.0.2 state. |
| Backup/import safety | No `BACKUP TO`. Plain `.sql` paths reject traversal, symlinks and overwrite. Import preflights schema, makes a safety export, serializes store maintenance and refuses to overwrite active recovery. Migration journals are tested at both sides of the atomic installation step. |

## Required deduplication review

| Area | Result and ownership |
| --- | --- |
| Listeners and schedules | One `Events` and one `ArenaGuards` registration plus one game timer on enable. Overlapping drop/fluid/explosion/session-interaction handlers were consolidated. Reload replaces settings/sessions while idle; it does not append listeners. |
| Scoreboards, teams, boss bars and entities | Session maps own scoreboards/boss bars and a bounded entity registry. Cleanup is scoped to session-owned objects. King chunk tickets are reference-counted. No world-wide entity scan remains in match cleanup. |
| Inventory restoration | `Snapshots` is the authoritative capture/apply/consume service. Synchronous quit/shutdown and asynchronous recovery share its checked snapshot, apply and durable-completion logic. Restore replaces inventory rather than adding copies; escrow head/session/phase checks prevent a stale restore consuming a newer session. |
| Kit/ability definitions | Active definitions are loaded once from canonical `config.yml`. Legacy standalone files are only migration inputs/reference; duplicate YAML keys fail. Each issued item has one registry owner and one authorization check. |
| Currency | One transactional store handles purchases, coin changes, deaths and terminal match rewards. Match/stat caches are projections of persistent UUID-based data; no second economy or external Vault/CMI debit path was added. |
| Match state | King, life tokens, timers, selected kits, cooldowns, charges and issued entities belong to a session/generation. Persistent statistics intentionally aggregate across matches using transactional event keys. |
| Configuration | Geometry belongs to `arenas.yml`; rules/kits/abilities belong to `config.yml`; text/menu/scoreboard definitions have separate owners. Existing per-arena timer overrides are intentional and documented. |

## Remaining verification limits

Physical Bedrock clients, a full server plugin stack, cross-world inventory managers, all block-entity/NBT variants, long-duration stress, hostile plugins and real hardware power failure were not exhaustively tested. The automated migration interruption tests simulate durable file layouts; they do not simulate storage-device failure. Historical 1.0.2 reports are archived separately and are not represented as tests of this JAR.

The game uses an authoritative pre-entry snapshot. Avoid unrelated administrative inventory grants/edits while a player is in a match; validate such integrations before rollout. Server plugins with direct API/file access can bypass event-level rules and are outside a player-level security boundary. Snapshot recovery deliberately waits if the original world or a required return teleport is unavailable rather than discarding recovery state.
