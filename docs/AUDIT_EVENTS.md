# Cosmos audit event catalog

Cosmos emits best-effort events through its shaded neutral audit client. Events follow the final operation result, except `incursion.mvp.result`, which records finalized ranking before reward delivery. Audit failures do not change gameplay.

The optional per-server audit engine owns SQLite and local staff searches. Each producer writes to its own bounded spool directory even when the engine is absent. Existing gameplay dependencies remain separate from audit transport.

All events use the `mysterria-cosmos.` namespace and `STAFF_RESTRICTED` privacy. Incursion
lifecycle, rewards, and MVP records reuse the incursion UUID as their correlation ID. A zone-shop
purchase gets one correlation UUID and a stable `zone-shop.purchase.<uuid>` business ID across all
success and failure outcomes. Metadata is immutable and bounded; balances and payouts are keyed by
`gold`, `silver`, and `gems`.

| Event | Commit/finalization point | Main evidence |
| --- | --- | --- |
| `incursion.created` | Event object created after start checks | forced/automatic trigger, duration, minimum players |
| `incursion.started` | Zones and beacons registered and ACTIVE entered | event ID, zone/beacon counts, countdown |
| `incursion.completed` | Cleanup and reward distribution complete; `FAILED` with `error` (exception class) if distribution threw | kills, deaths, zone count, termination reason |
| `incursion.cancelled` | Zone generation fails, no zones are available, or an admin/shutdown stop completes | reason, error (when available), event stats |
| `incursion.winner` | Qualified rank-one town determined | town ID/name, score, share, rank |
| `incursion.holder_changed` | Holder/streak state updated in `EventHistoryStore` | previous/current holder and streak, reason |
| `incursion.reward_granted` | That town's payout deposited (one row per town). `COMMITTED` when its own save persisted; `ATTEMPTED` with reason `persist_deferred` when the credit is applied in memory but the save failed (a later save stores it and emits `town.balance_persisted`); `FAILED` with reason `persist_serialization_failed` when the save threw (credit applied in memory, not persisted) | town, rank/share/multiplier, pool, payout, `applied_in_memory`, `persisted`, `error` |
| `incursion.mvp.result` | Final MVP list selected | player UUID/name, score, rank, online state, location when online |
| `incursion.mvp.reward_pending` | Offline MVP effort queued in `EventHistoryStore` | player, acting effort, offline reason |
| `incursion.mvp.reward_granted` | MVP reward delivered (online or on join). `COMMITTED` when acting points or a configured command were applied; `OBSERVED` with reason `no_reward_applied` when no command is configured and 0 acting points were granted (capped or non-Beyonder); `FAILED` when the acting grant, a configured command, or the claim failed (a thrown command is recorded before it propagates) | player, acting effort, `acting_applied`, `acting_granted`, `acting_error`, `command_configured`, `command_applied`, `command_error`, trigger, location |
| `incursion.acting_granted` | COI acting grant returned (extraction, beacon capture, PvP); `DENIED` when COI granted 0 points; LOW risk | source, source category, tier, effort, points granted, repeat multiplier, victim, location |
| `town.balance_adjusted` | Town balance changed (admin set/add/remove, extraction deposit). `COMMITTED` when persisted; `ATTEMPTED`/`persist_deferred` when applied in memory but the save failed; `FAILED`/`persist_serialization_failed` when the save threw | town, operation, requested amounts, balance before/after, `applied_in_memory`, `persisted`, trigger, actor name/type, actor location |
| `town.balance_persisted` | A later successful balance save stored a change previously reported as `persist_deferred`; same correlation ID as the deferred row. At most 1024 deferred changes are tracked; when older ones were dropped, one `OBSERVED` row with reason `deferred_persist_overflow` reports the count | town, source event, persisted balance; `dropped_deferred_count` on overflow rows |
| `admin.zone_shop_edited` | Shop catalogue replaced (editor GUI save) or extended (`addcoi`) and saved | operation, item counts, catalogue before/after/added/removed (with CoI `item_uuid`/`parent_item_uuid` per stack), shop entry IDs before/after, `item_uuids_added`/`item_uuids_removed`, top-level `item_uuid`/`parent_item_uuid` of the first newly listed tracked stack, actor |
| `shop.purchase` | Town balance deduction and item delivery result. `COMMITTED` only when every requested item reached the inventory or a live dropped entity; `FAILED`/`delivery_incomplete` when a drop spawn was cancelled after payment; deduction failures use `balance_changed_before_commit`, `invalid_price`, `balance_persist_failed` or `balance_persist_threw`. Refusals before the confirmation screen are `DENIED` (`not_town_member`, `missing_permission`, `price_unset`, `insufficient_balance`, stage `pre_confirmation`), at most one row per player per 5 s | town, shop/COI item IDs, top-level physical item/parent UUID when present, price, balance before/after, requested/granted/dropped/undelivered counts and amounts, location, outcome |
| `shop.item_granted` | An item from a committed purchase is placed in inventory | purchase correlation/business ID, item/parent UUID when present, logical shop item, material, amount |
| `shop.item_dropped` | An inventory fallback places a purchased item in the world. `COMMITTED`/`inventory_fallback` for a live dropped entity (location is the entity's); `FAILED`/`drop_spawn_cancelled` when the spawn was cancelled and the stack was not delivered (location is the player's) | purchase correlation/business ID, item/parent UUID when present, dropped entity UUID, material, amount, world/x/y/z |

`EventHistoryStore` remains operational because holder, streak, cooldown, event leaderboard, and
pending offline MVP behavior read it directly. Shop history in the GUI uses bounded in-memory
records (50 per town), persisted to `zone-shop-history.yml` with a debounced asynchronous write
and loaded on enable so the GUI view survives restarts. The duplicate shop transaction
text/console writer has been removed.

Rows involving a player carry `world`, `x`, `y`, `z` block coordinates. Queued MVP rewards
migrated from the legacy effort-only format have no event ID; their correlation is a
deterministic name-based UUID derived from the player and pending entry.

Pending MVP rewards are durably claimed before acting or command delivery, preserving the original at-most-once policy. A failed claim leaves the reward pending; a failure or crash after claim requires staff reconciliation and is not automatically replayed. Canonical shop events replace the duplicate text/console transaction logger; bounded, file-backed history remains available in the shop GUI.

## Overlap policy

Event history, holder streaks, cooldowns and pending MVP rewards remain functional state; the shop GUI keeps its bounded memory view. MVP operation IDs propagate to coordinated COI acting APIs, with an older-API gameplay fallback selected before dispatch.
