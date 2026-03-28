# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

**CosmosIncursion** is a Minecraft Paper 1.21+ plugin that manages timed PvP incursion events and permanent extraction zones. It integrates with CircleOfImagination (beyonder/sequence system), HuskTowns (town claims), BlueMap (map visualization), and Citizens (NPC combat logging).

See [`docs/`](docs/) for detailed documentation.

---

## Build & Dev Commands

```bash
./gradlew build       # Build shadowJar → build/libs/
./gradlew runServer   # Start local Paper test server
./gradlew clean       # Clean build artifacts
```

---

## Admin Commands

```
/cosmos status                              - Show current event status
/cosmos admin reload                        - Hot-reload config.yml
/cosmos admin start / stop                  - Force start/stop event
/cosmos admin give paperangel <player>      - Give Paper Angel protection item
/cosmos admin zone list/add/remove/tp       - Manage incursion zones

/cosmos exclusion list/add/remove/tp/info   - Manage permanent zones
/cosmos exclusion vertex add/remove/tp      - Manage polygon vertices
/cosmos exclusion reload                    - Reload permanent zones from file
```

---

## Package Structure

```
net.mysterria.cosmos/
├── CosmosIncursion.java              ← Plugin entry point, initializes all managers
├── domain/
│   ├── incursion/                    ← Timed event zones (circle-based)
│   │   ├── source/                   ← Enums: ZoneTier, PlayerTier, EventState
│   │   ├── tasks/                    ← ZoneCheckTask, EventCheckTask, ZoneBoundaryParticleTask
│   │   └── gui/                      ← ConsentGUI
│   ├── beacon/                       ← Spirit beacon capture mechanics
│   │   └── tasks/                    ← BeaconCaptureTask, BeaconParticleTask
│   ├── combat/                       ← Death, combat-log, anti-grief, rewards
│   └── exclusion/                    ← Permanent polygon extraction zones
│       └── tasks/                    ← PermanentZonePlayerTask, ResourceAccumulationTask,
│                                       ExtractionTask, PoIRotationTask
├── command/                          ← LiteCommands: GeneralCommand, AdminCommand, ExclusionCommand
├── toolkit/                          ← Wrappers: CoiToolkit, TownsToolkit, CitizensToolkit,
│                                       ZonePlacerToolkit, BlueMapIntegration, BuffToolkit, etc.
└── config/                           ← ConfigLoader, CosmosConfig
```

---

## Core Architecture

### Manager Initialization Order (CosmosIncursion.onEnable)
1. `ConfigManager` → loads config.yml
2. `ZoneManager` → incursion zone registry
3. `BeaconManager` → beacon registry + capture states
4. `PlayerStateManager` → per-player zone + tier tracking
5. `EffectManager` → glowing/DOT effects
6. `BeaconUIManager` → action bars, boss bars
7. `PermanentZoneManager` → permanent zones, PoIs, extraction points
8. `EventManager` → event state machine (depends on all above)

### Event State Machine (`EventManager` + `EventCheckTask`)

`IDLE → STARTING → ACTIVE → ENDING`

- **IDLE**: Waits for `min-players` threshold + cooldown. Does not re-check after event starts.
- **STARTING**: 60s countdown; zones + beacons generated with tier distribution.
- **ACTIVE**: Full duration regardless of player count changes. Runs beacon capture + rewards.
- **ENDING**: Awards buff to winning town, despawns Hollow Bodies, cleans up state.

### Zone Tiers (Incursion Events)

Zones are assigned tiers at event start with configurable ratios. See [docs/ZONE_TIERS.md](docs/ZONE_TIERS.md).

| Tier  | Item Drop | Sequence Regression | Reward Level |
|-------|-----------|--------------------:|--------------|
| GREEN | 0%        | Never               | Low          |
| YELLOW| ~33%      | Never               | Medium       |
| RED   | 100%      | Never               | High         |
| DEATH | 100%      | Yes (Seq 4–5 only)  | Best         |

### Permanent Extraction Zones

Always-on polygon PvP zones with rotating PoIs and extraction points. See [docs/PERMANENT_ZONES.md](docs/PERMANENT_ZONES.md).

- Players accumulate resources near **PoIs** (rotate every ~5 min)
- Resources are deposited to town balance by standing at **Extraction Points** (rotate every ~3 min)
- Dying in zone loses all carried (unextracted) resources

### Combat & Death

See [docs/COMBAT_DEATH.md](docs/COMBAT_DEATH.md).

- **Death Penalty**: Tier-gated; DEATH zones trigger sequence regression for Seq 4–5 players
- **Paper Angel**: One-time protection item, right-click to arm
- **Hollow Body NPCs**: Spawned on combat-log (disconnect in zone), drop full inventory on death
- **Anti-Grief**: 3 low-tier kills in 600s → "Corrupted Monster" debuff

### Key Design Patterns

- **Tick-based detection** instead of event listeners for zone boundaries (reliable under lag)
- **Polygon containment** for permanent zones via ray-casting (XZ plane)
- **ConcurrentHashMap** throughout for thread-safe async/tick updates
- **JSON persistence**: `zones_permanent.json`, `permanent_zone_balances.json`, `buff_data.json`
- **Map integration**: BlueMap → squaremap → NoOp fallback chain

---

## Background Tasks

| Task                     | Interval        | Purpose                              |
|--------------------------|-----------------|--------------------------------------|
| `EventCheckTask`         | 20t (1s)        | Event state machine tick             |
| `ZoneCheckTask`          | 5t (200ms)      | Incursion zone entry/exit detection  |
| `ZoneBoundaryParticleTask`| 40t (2s)       | Boundary particle visuals            |
| `BeaconCaptureTask`      | 20t (1s)        | Beacon progress + decay              |
| `PermanentZonePlayerTask`| 5t (200ms)      | Permanent zone entry/exit            |
| `ResourceAccumulationTask`| 20t (1s)       | Resource gain from PoIs              |
| `ExtractionTask`         | 20t (1s)        | Deposit resources to town balance    |
| `PoIRotationTask`        | 20t (1s)        | PoI/extraction point rotation        |

---

## API Integrations

| Plugin              | Wrapper            | Required? | Purpose                                    |
|---------------------|--------------------|-----------|--------------------------------------------|
| CircleOfImagination | `CoiToolkit`       | Required  | Beyonder status, sequence, acting points   |
| HuskTowns           | `TownsToolkit`     | Required  | Town membership, town balance              |
| BlueMap             | `BlueMapIntegration`| Optional | Map markers for zones, beacons, players   |
| squaremap           | `SquareMapIntegration`| Optional| Map fallback                              |
| Citizens            | `CitizensToolkit`  | Optional  | Hollow Body NPCs for combat logging        |

---

## Testing Notes

```yaml
# config.yml overrides for local testing
event:
  auto-start: false    # disable auto triggers
  min-players: 1       # allow solo testing
```

- Logs prefixed with `[CI]` for state transitions
- `/cosmos admin reload` applies config changes without restart (task intervals, etc.)
- Event-specific settings (zone count, tier distribution) apply on next event start
- Console logs from `[CI]` prefix track all state transitions

---

## Further Reading

- [docs/ZONE_TIERS.md](docs/ZONE_TIERS.md) — Zone tier system, consent GUI, tier mechanics
- [docs/PERMANENT_ZONES.md](docs/PERMANENT_ZONES.md) — Extraction zones, PoIs, resource types, town balances
- [docs/COMBAT_DEATH.md](docs/COMBAT_DEATH.md) — Death penalties, Paper Angel, Hollow Body NPCs, anti-grief
- [docs/CONFIG_REFERENCE.md](docs/CONFIG_REFERENCE.md) — Full config.yml key reference
