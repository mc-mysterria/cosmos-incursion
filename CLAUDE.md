# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CosmosIncursion is a Minecraft Paper plugin (1.21+) that manages timed PvP events with incursion zones and spirit beacons. It integrates with CircleOfImagination (beyonder system), HuskTowns (town claims), BlueMap (visualization), and Citizens (NPC creation).

## Build & Development Commands

```bash
# Build the plugin (creates shadowJar in build/libs/)
./gradlew build

# Run a local Paper test server with the plugin
./gradlew runServer

# Clean build artifacts
./gradlew clean
```

## Admin Commands

```
/cosmos status - Show current event status
/cosmos admin reload - Reload configuration
/cosmos admin start - Force start event
/cosmos admin stop - Force stop event
/cosmos admin give paperangel <player> [amount] - Give Paper Angel items
/cosmos admin zone list - List all zones
/cosmos admin zone add <name> - Create zone at your location
/cosmos admin zone remove <name> - Remove a zone
/cosmos admin zone tp <name> - Teleport to a zone
```

## Core Architecture

### Event State Machine

The plugin operates through a state machine managed by `EventManager`:

1. **IDLE** - Waiting for trigger conditions (player count threshold, cooldown elapsed)
2. **STARTING** - Countdown phase, zones generated, beacons placed
   - Broadcasts countdown at 60, 30, 10, 5, 4, 3, 2, 1 seconds
3. **ACTIVE** - Event running, zones active, beacons capturable
   - Broadcasts time remaining at 25, 20, 15, 10, 5, 3, 2, 1 minutes and 30 seconds
4. **ENDING** - Cleanup phase, calculate winners, award buffs

State transitions are driven by `EventCheckTask` (ticks every second) and handle zone activation/deactivation, beacon lifecycle, and player consent management.

### Manager Hierarchy

The main plugin class `CosmosIncursion` initializes managers in dependency order:

1. `ConfigManager` - Loads config.yml
2. `ZoneManager` - Tracks incursion zones and player locations
3. `BeaconManager` - Manages spirit beacons and capture states
4. `PlayerStateManager` - Tracks which players are in zones and their tier
5. `EffectManager` - Applies glowing/DOT effects based on tier
6. `BeaconUIManager` - Handles beacon visuals, action bars, boss bars
7. `EventManager` - Orchestrates the event lifecycle (depends on all above)

### Zone Entry Flow

1. `ZoneCheckTask` (runs every 5 ticks) detects player movement
2. Checks if player is in a new zone using `ZoneManager.getZoneAt()`
3. If entering a zone without consent, shows `ConsentGUI`
4. On consent, registers entry via `PlayerStateManager.registerEntry()`
5. `PlayerStateManager.calculateTier()` determines SPIRIT_WEIGHT vs INSIGNIFICANT
6. `EffectManager` applies effects based on tier:
   - SPIRIT_WEIGHT: glowing effect + DOT damage (via `SpiritWeightTask`)
   - INSIGNIFICANT: no effects

### Beacon Capture Mechanics

During ACTIVE state:
- `BeaconCaptureTask` runs every second
- For each beacon, counts nearby players by town
- Updates `BeaconCapture.progress` (+points per player, -decay when uncontested)
- Tracks total ownership time per town
- At event end, town with most total ownership seconds wins the Acting Speed buff

### Combat & Death Systems

**Death in Zone:**
- `PlayerDeathListener` → `DeathHandler.handleZoneDeath()`
- Sequence 4+ players regress unless they have a "Paper Angel" protection active
- **Paper Angel System**:
  - Given via `/cosmos admin give paperangel <player>`
  - Right-click to activate (sets PDC: `paper_angel = true`)
  - Protects from sequence regression on next zone death
  - One-time use (PDC removed after saving player)
  - Cannot activate if protection already active
- Killer earns a Cosmos Crate (if not griefing)
- Low-tier killers tracked by `KillTracker` for anti-grief (becomes "Corrupted Monster")

**Combat Logging:**
- `PlayerQuitListener` → `CombatLogHandler.handleDisconnect()`
- If player disconnects while in zone:
  - Captures full inventory (items + armor) snapshot
  - Spawns a "Hollow Body" NPC via Citizens with stored inventory
  - NPC is vulnerable and persists for configured duration
- When Hollow Body NPC dies:
  - Drops ALL stored items at death location (lootable by others)
  - Marks NPC as killed
- On reconnect:
  - If killed: Clears inventory, teleports to death location, kills player (triggers sequence regression)
  - If survived: No penalty
  - Works even after event ends (Hollow Body persists)
- Event end cleanup:
  - All remaining Hollow Body NPCs are force-despawned when event transitions to ENDING state
  - Prevents NPCs from persisting into the next event

## API Integrations

### CircleOfImagination (Required)
- `CoiToolkit` wraps API calls
- Used for checking beyonder status and sequence level
- Determines player tier classification

### HuskTowns (Required)
- `TownsToolkit` wraps API calls
- Validates town membership for beacon capture
- Prevents zone placement near town claims

### BlueMap (Required)
- `BlueMapIntegration` creates map markers for zones and beacons
- Updates beacon markers with ownership colors during capture
- Marks SPIRIT_WEIGHT players on the map

### Citizens (Soft Dependency)
- `CitizensIntegration` manages Hollow Body NPCs
- Creates/removes NPCs with configured duration
- Tracks kill state for reconnect penalty logic

## Configuration

All gameplay values are in `config.yml`:
- Event triggers: auto-start (enable/disable automatic event starts), min-players, cooldown-minutes, duration-minutes
- Zone generation: base-count, players-per-zone, radius, min-separation
- Spirit Weight: min-sequence, max-sequence, dot-damage, dot-interval-ticks
- Anti-grief: kill-threshold, time-window-seconds, sequence-difference
- Death penalties: regression-sequence, regression-acting-threshold, regression-acting-restored, regression-acting-penalty
- Beacons: capture-radius, capture-points, points-per-player, decay-rate
- Rewards: acting-speed-bonus, buff-duration-hours

### Death Penalty System
The sequence regression system uses a two-tier penalty based on acting percentage:
- **Below threshold** (default 51%): Full sequence regression
  - Player regresses to next sequence (e.g., Seq 4 → Seq 5)
  - Receives percentage of new sequence's needed acting (default 80%)
  - Drops characteristic item of previous sequence
- **Above threshold**: Acting penalty only
  - No sequence change
  - Loses percentage of current needed acting (default 50%)
  - No characteristic drop

All values configurable in `config.yml` under `death` section.

## Key Design Patterns

### Tick-Based Detection
The plugin uses scheduled tasks instead of event listeners for zone detection to ensure reliable boundary checking:
- `ZoneCheckTask` every 5 ticks (200ms) - zone entry/exit detection
- `EventCheckTask` every 20 ticks (1 second) - event state machine tick
- `SpiritWeightTask` every 100 ticks (5 seconds, configurable) - DOT damage application
- `BeaconCaptureTask` every 20 ticks (1 second) - beacon capture progress
- `ZoneBoundaryParticleTask` every 40 ticks (2 seconds, configurable) - visual boundary markers

### Zone Placement & Validation
Zones are generated by `ZonePlacer` to ensure suitable combat locations:
- **Random Distribution**: Uses random angles and radii (50-90% of town distance) with ±100 block offsets for variety
- **Biome Validation**: Rejects ocean, deep ocean, and river biomes
- **Surface Checks**: Ensures spawn point is not underwater or in lava
- **Area Suitability**: Scans 60x60 block area around each candidate, rejects if >30% is water
- **Town Avoidance**: Maintains buffer distance from town claims
- **Zone Separation**: Enforces minimum distance between zones

This multi-layer validation ensures zones spawn on land surfaces suitable for PvP combat, not in oceans or large bodies of water.

### Consent System
Players must consent to zone rules before entering:
- `ConsentGUI` shows agreement GUI on boundary approach
- Consent state tracked per player, reset when event ends
- Players inside zones when event starts are teleported outside

### Thread Safety
All manager maps use `ConcurrentHashMap` to support async operations and tick-based updates without race conditions.

## Testing Notes

When testing locally with `runServer`:
- Default min-players is 30 (adjust in config.yml for solo testing)
- Set `event.auto-start: false` in config.yml to disable automatic event triggers (useful for manual testing)
- Use `/cosmos admin start` to manually start an event
- Use `/cosmos admin stop` to manually stop an event
- Use `/cosmos admin reload` to reload configuration and restart config-dependent tasks (no server restart needed)
- Check console logs prefixed with `[CI]` for state transitions

### Runtime Configuration
The plugin supports hot-reloading via `/cosmos admin reload`:
- Reloads all values from config.yml
- Restarts tasks with updated timing intervals (e.g., DOT interval)
- No server restart required for most config changes
- Event-specific settings (zone count, beacons, etc.) apply to next event start
