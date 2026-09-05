# Combat & Death Systems

## Death in a Zone

Handled by `PlayerDeathListener` (priority HIGHEST — runs before graves plugins).

### Item Handling
1. All inventory items (including armor + off-hand) are manually dropped at death location
2. `event.getDrops().clear()` prevents default drops
3. Inventory cleared before death completes
4. This blocks graves plugins from capturing items and prevents duplication

### Death Penalty Logic (`DeathHandler`)

Penalty only applies on **DEATH tier zones** to **Seq 4–5 beyonders**. No penalty applies in GREEN/YELLOW/RED zones.

```
If player has Paper Angel active:
  → Consume angel, skip all penalties

Else if player acting >= regression-acting-penalty% of needed acting:
  → Lose (regression-acting-penalty)% of needed acting
  → No sequence change, no characteristic drop

Else:
  → Regress sequence (e.g., Seq 4 → Seq 5)
  → Receive (regression-acting-restored)% of new sequence's needed acting
  → Drop characteristic item of previous sequence
```

Config keys (`death` section):
- `regression-sequence` — threshold sequence for regression (default: 6, meaning Seq 4–5 affected)
- `regression-acting-restored` — % of new sequence acting granted after regression (default: 0.99 = 99%)
- `regression-acting-penalty` — % of needed acting lost on soft penalty (default: 0.5 = 50%)
- `death-penalty-cooldown-seconds` — cooldown between penalties for the same player

### Kill Rewards
- Non-griefing killers receive a Cosmos Crate (command in `death.crate-command`)
- DEATH zone kills may execute a tier-specific reward command (`zones.tiers.death.reward-command`)

---

## Paper Angel

Protection item granted via `/cosmos admin give paperangel <player> [amount]`.

- Right-click to **arm** (sets PDC `paper_angel = true`)
- On next zone death: protection consumed, all penalties skipped
- One-time use per activation; cannot re-arm while active
- Handled by `PaperAngelListener`

---

## Combat Logging (Hollow Body NPCs)

Requires Citizens plugin (soft dependency).

### On Disconnect in Zone (`CombatLogHandler`)
1. Full inventory transferred to the Hollow Body (storage + armor + offhand deep-cloned)
2. Player inventory cleared and `saveData()` so the player no longer holds those items
3. Citizens NPC spawned at player's location as "Hollow Body"
4. NPC vulnerable to attack; persists for `combat-log.npc-duration-minutes`

### When Hollow Body Dies
- Drops transferred items once at death location (lootable by any player)
- Marks NPC as killed; stored snapshot cleared so it cannot drop again

### On Player Reconnect (`PlayerJoinListener`)
| NPC State   | Outcome                                                        |
|-------------|----------------------------------------------------------------|
| Killed      | Inventory stays empty → teleported to NPC death location → killed (triggers death penalty) |
| Survived    | Transferred inventory restored once; NPC/outcome cleared       |
| No NPC      | Normal login                                                   |

### Cleanup
- NPC entities force-despawned when event enters `ENDING` or on timeout
- Killed/survived outcome state is retained until the player rejoins (prevents leave/kill item dupe and restore loss)

---

## Anti-Grief Detection (`KillTracker`)

Tracks Seq 6–9 (INSIGNIFICANT) players killing Seq 4–5 (SPIRIT_WEIGHT) players.

- 3 kills within 600 seconds → player marked as **"Corrupted Monster"**
- Duration: 15 minutes (configurable: `anti-grief.corrupted-duration-minutes`)
- Visual marker placed on BlueMap during duration
- Corrupted players tracked in memory; cleaned up every 60 seconds

Config keys (`anti-grief` section):
- `kill-threshold` — kills before marking (default: 3)
- `time-window-seconds` — tracking window (default: 600)
- `sequence-difference` — sequence gap that triggers tracking (default: configured value)
