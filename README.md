# CosmosIncursion

A Minecraft Paper 1.21+ plugin for the Mysterria server. Adds timed PvP incursion events with tiered risk/reward zones, spirit beacon capture, and permanent extraction zones.

## Features

- **Incursion Events** — Periodic PvP events with 4 zone tiers (Green → Death), each scaling item loss and sequence regression risk against better rewards
- **Spirit Beacons** — Town-vs-town beacon capture during events; winning town earns an Acting Speed buff
- **Permanent Extraction Zones** — Always-on polygon PvP areas with rotating PoIs and extraction points; players accumulate resources and deposit them to town balances
- **Combat Logging Prevention** — Disconnect in a zone spawns a vulnerable "Hollow Body" NPC holding your inventory
- **Paper Angel** — One-time protection item preventing sequence regression on death
- **BlueMap Integration** — Live map markers for zones, beacons, Spirit Weight players, and permanent zones

## Dependencies

| Plugin              | Required |
|---------------------|----------|
| CircleOfImagination | Yes      |
| HuskTowns           | Yes      |
| BlueMap             | Optional |
| Citizens            | Optional |

## Build

```bash
./gradlew build       # → build/libs/CosmosIncursion-*.jar
./gradlew runServer   # Local test server
```

## Docs

- [CLAUDE.md](CLAUDE.md) — Architecture & dev guide
- [docs/ZONE_TIERS.md](docs/ZONE_TIERS.md) — Zone tier mechanics
- [docs/PERMANENT_ZONES.md](docs/PERMANENT_ZONES.md) — Extraction zone system
- [docs/COMBAT_DEATH.md](docs/COMBAT_DEATH.md) — Death penalties & combat logging
- [docs/CONFIG_REFERENCE.md](docs/CONFIG_REFERENCE.md) — Full config.yml reference
