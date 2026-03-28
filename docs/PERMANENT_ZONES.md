# Permanent Extraction Zones

Always-on polygon PvP zones, active from server start. Managed by `PermanentZoneManager`.

## Overview

Permanent zones work like an extraction shooter loop:
1. Stand near a **PoI** → accumulate resources in your carry buffer
2. Walk to an **Extraction Point** → resources drain from buffer into town balance
3. Get killed before extracting → lose all carried resources (attacker gets nothing directly, but you drop your inventory items per incursion event rules if applicable)

## Zone Shape

Zones use arbitrary polygon boundaries defined by admin-placed vertices. Containment uses ray-casting on the XZ plane. Zones must have **≥3 vertices** to be active. Centroid and approximate radius are auto-calculated from vertices.

## Admin Setup

```
/cosmos exclusion add <name>               - Create a new zone
/cosmos exclusion vertex add <name>        - Add a vertex at your current location
/cosmos exclusion vertex remove <name>     - Remove nearest vertex
/cosmos exclusion vertex tp <name>         - Teleport to a vertex for inspection
/cosmos exclusion tp <name>                - Teleport to zone centroid
/cosmos exclusion info <name>              - Show PoIs, extraction points, players
/cosmos exclusion remove <name>            - Delete zone (removes PoIs/extraction points)
/cosmos exclusion reload                   - Reload all zones from zones_permanent.json
```

PoIs and extraction points are automatically spawned once a zone has 3+ valid vertices.

## Resource Types

| Type   | Material      | Accumulation Rate | Exchange Rate |
|--------|---------------|-------------------|---------------|
| GOLD   | Gold Ingot    | 1.0 /sec          | 1.0×          |
| SILVER | Iron Ingot    | 2.0 /sec          | 0.5×          |
| GEMS   | Emerald       | 0.5 /sec          | 3.0×          |

Rates and exchange multipliers are configurable in `config.yml` under `permanent-zones`.

## Points of Interest (PoIs)

- **Count**: 3 active per zone simultaneously (configurable: `poi-count`)
- **Duration**: 5 minutes before rotating to a new random location (configurable: `poi-duration-seconds`)
- **Capture radius**: 8 blocks (configurable: `poi-capture-radius`)
- Players within radius accumulate resources at the type's rate into their `PlayerResourceBuffer`
- Only the first matched PoI counts per tick per player
- Actionbar shows current carry amounts

## Extraction Points

- **Count**: 2 active per zone simultaneously (configurable: `extraction-point-count`)
- **Duration**: 3 minutes before rotating (configurable: `extraction-point-duration-seconds`)
- **Radius**: 6 blocks (configurable: `extraction-radius`)
- Standing nearby drains buffer at `extraction-rate-per-second` (default 5.0 units/sec)
- Drained resources are deposited to player's town balance via `TownsToolkit`
- Players without a town are skipped (no extraction)
- Actionbar shows extraction progress

## Town Balance System

- Resources are deposited to `TownsToolkit` under the player's town name
- Exchange rate applied: `deposited_value = amount × resource.exchange_rate`
- Balances persist in `permanent_zone_balances.json`
- Multiple resource types tracked separately per town

## Persistence Files

| File                            | Contents                               |
|---------------------------------|----------------------------------------|
| `zones_permanent.json`          | Zone definitions (vertices, IDs, names)|
| `permanent_zone_balances.json`  | Town balances per resource type        |

## BlueMap Markers

Permanent zones have a dedicated marker set (separate from incursion zones, toggleable):
- Zone polygon outlines in blue
- Active PoI markers with resource type labels
- Active extraction point markers
- Updated by `PoIRotationTask` on spawn/despawn
