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
| `incursion.completed` | Cleanup and reward distribution complete | kills, deaths, zone count |
| `incursion.cancelled` | Zone generation fails, no zones are available, or an admin/shutdown stop completes | reason, error (when available), event stats |
| `incursion.winner` | Qualified rank-one town determined | town ID/name, score, share, rank |
| `incursion.holder_changed` | Holder/streak state updated in `EventHistoryStore` | previous/current holder and streak, reason |
| `incursion.reward_granted` | Resource payout deposited to a town balance | town, rank/share/multiplier, pool, payout |
| `incursion.mvp.result` | Final MVP list selected | player UUID/name, score, rank, online state |
| `incursion.mvp.reward_pending` | Offline MVP effort queued in `EventHistoryStore` | player, acting effort, offline reason |
| `incursion.mvp.reward_granted` | Acting effort/command granted (online or on join) | player, acting effort, trigger |
| `shop.purchase` | Town balance deduction and item delivery result | town, shop/COI item IDs, top-level physical item/parent UUID when present, price, balance before/after, outcome |
| `shop.item_granted` | An item from a committed purchase is placed in inventory | purchase correlation/business ID, item/parent UUID when present, logical shop item, material, amount |
| `shop.item_dropped` | An inventory fallback places a purchased item in the world | purchase correlation/business ID, item/parent UUID when present, dropped entity UUID, material, amount |

`EventHistoryStore` remains operational because holder, streak, cooldown, event leaderboard, and
pending offline MVP behavior read it directly. Shop history in the GUI uses bounded in-memory
records; the duplicate shop transaction text/console writer has been removed.

Pending MVP rewards are durably claimed before acting or command delivery, preserving the original at-most-once policy. A failed claim leaves the reward pending; a failure or crash after claim requires staff reconciliation and is not automatically replayed. Canonical shop events replace the duplicate text/console transaction logger; bounded in-memory history remains available in the shop GUI.

## Overlap policy

Event history, holder streaks, cooldowns and pending MVP rewards remain functional state; the shop GUI keeps its bounded memory view. MVP operation IDs propagate to coordinated COI acting APIs, with an older-API gameplay fallback selected before dispatch.
