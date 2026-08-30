# Cosmos audit event catalog

Cosmos emits best-effort events to the optional `MysterriaAudit` Bukkit service. Events are
emitted only after the owning operation has a final result, except for `incursion.mvp.result`,
which records the finalized ranking before reward delivery. A missing or failing provider never
changes gameplay behavior.

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
pending offline MVP behavior read it directly. `ShopTransactionLogger` and its per-town text files
remain enabled as a review-window fallback; they can be retired only after ledger parity and runtime
queries have been validated.
