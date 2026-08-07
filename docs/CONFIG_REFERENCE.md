# config.yml Reference

All gameplay values live in `config.yml`. Hot-reload via `/cosmos admin reload`.

## event

| Key                  | Default | Description                                      |
|----------------------|---------|--------------------------------------------------|
| `auto-start`         | `false` | Automatically start events when conditions met   |
| `min-players`        | `30`    | Minimum online players to start an event         |
| `cooldown-minutes`   | `120`   | Cooldown between events                          |
| `duration-minutes`   | `30`    | Active event duration                            |
| `countdown-seconds`  | `60`    | Pre-event countdown duration                     |

## zones

| Key                         | Default | Description                                      |
|-----------------------------|---------|--------------------------------------------------|
| `base-count`                | `1`     | Minimum zones per event                          |
| `players-per-zone`          | `20`    | Players online per additional zone               |
| `max-count`                 | `5`     | Hard cap on zone count                           |
| `radius`                    | `150`   | Zone radius in blocks                            |
| `town-buffer`               | `50`    | Minimum distance from town claims                |
| `min-separation`            | `500`   | Minimum distance between zone centers            |
| `tier-distribution.green`   | `1`     | Ratio of GREEN zones per event                   |
| `tier-distribution.yellow`  | `1`     | Ratio of YELLOW zones per event                  |
| `tier-distribution.red`     | `1`     | Ratio of RED zones per event                     |
| `tier-distribution.death`   | `1`     | Ratio of DEATH zones per event                   |
| `tiers.<tier>.drop-chance`  | varies  | 0.0–1.0 item drop probability on death           |
| `tiers.<tier>.reward-command`| `""`   | Command run on kill (use `%player%` placeholder) |
| `tiers.<tier>.particle-color`| varies | Hex RGB for boundary particles                  |

## balancing.spirit-weight

| Key               | Default | Description                              |
|-------------------|---------|------------------------------------------|
| `min-sequence`    | `4`     | Lowest sequence affected by DOT/glow     |
| `max-sequence`    | `5`     | Highest sequence affected by DOT/glow    |
| `dot-damage`      | `1.0`   | HP lost per DOT tick                     |
| `dot-interval-ticks` | `100` | Ticks between DOT damage (100 = 5s)   |

## death

| Key                            | Default | Description                                        |
|--------------------------------|---------|----------------------------------------------------|
| `regression-sequence`          | `6`     | Sequences below this value can regress (4–5 if 6) |
| `regression-acting-restored`   | `0.99`  | % of new seq acting granted after regression       |
| `regression-acting-penalty`    | `0.5`   | % of needed acting lost on soft penalty            |
| `death-penalty-cooldown-seconds`| `20`   | Minimum seconds between penalties per player       |
| `crate-command`                | `...`   | Command to give Cosmos Crate to killer             |

## anti-grief

| Key                      | Default | Description                              |
|--------------------------|---------|------------------------------------------|
| `kill-threshold`         | `3`     | Kills before Corrupted Monster debuff    |
| `time-window-seconds`    | `600`   | Window for kill tracking                 |
| `sequence-difference`    | varies  | Sequence gap triggering grief tracking   |
| `corrupted-duration-minutes` | `15` | Duration of Corrupted Monster debuff    |

## beacons

| Key                     | Default | Description                                              |
|-------------------------|---------|-----------------------------------------------------------|
| `capture-radius`        | `20`    | Radius to count players for capture                      |
| `capture-points`        | `100`   | Progress needed to fully capture a beacon                |
| `points-per-player`     | `1`     | Capture progress per player per second                   |
| `decay-rate`            | `0.5`   | Progress decay per second when uncontested                |
| `capture-acting-effort` | `2.0`   | Acting effort granted to each player present on capture   |

## rewards

Event-end rewards are contribution-based, not winner-take-all — see [ZONE_TIERS.md](ZONE_TIERS.md#event-rewards) for the model. `acting-speed-bonus` and `buff-duration-hours` are deltas/fallbacks only; the actual podium payouts come from `rewards.podium`.

| Key                                             | Default | Description                                                          |
|--------------------------------------------------|---------|-------------------------------------------------------------------------|
| `acting-speed-bonus`                            | `0.10`  | Delta, not a multiplier (`0.10` = +10%). Fallback for old `buff_data.json` entries and for rank 1 if `podium` has no entry for it. |
| `buff-duration-hours`                           | `24`    | Fallback duration, same role as `acting-speed-bonus` above.          |
| `winner-resources.<tier>.<gold\|silver\|gems>`  | varies  | Reward pool contributed per zone tier active in the event; split proportionally among qualifying towns. |

### rewards.contribution

| Key                                          | Default | Description                                                        |
|------------------------------------------------|---------|-------------------------------------------------------------------------|
| `hold-weight`                                 | `1.0`   | Score per second a town uncontestedly holds a beacon                |
| `contested-hold-weight`                       | `2.0`   | Score per second a town holds a beacon while contested               |
| `capture-weight`                              | `30.0`  | Score awarded per completed capture                                   |
| `tier-weights.<green\|yellow\|red\|death>`    | varies  | Multiplier applied to the above, based on the beacon's zone tier |
| `min-score-share`                             | `0.05`  | A town scoring below this fraction of the top score gets no reward   |

### rewards.podium

| Key                          | Default | Description                                        |
|---------------------------------|---------|-------------------------------------------------------|
| `rank-<N>.acting-speed`        | varies  | Acting Speed delta for the town placing rank N        |
| `rank-<N>.duration-hours`      | varies  | Buff duration for rank N                              |

Ranks with no entry receive resources only — no buff. Defaults: rank 1 = `0.10`/24h, rank 2 = `0.05`/12h.

### rewards.nation

Requires the Lands plugin (HuskTowns has no nation concept — towns from it always get a `1.0` multiplier).

| Key                     | Default | Description                                                          |
|--------------------------|---------|-------------------------------------------------------------------------|
| `enabled`                | `true`  | Master switch for nation amplification                               |
| `bonus-per-extra-town`   | `0.10`  | Resource-share multiplier bonus per additional qualifying nation-mate |
| `max-multiplier`         | `1.30`  | Cap on the combined nation multiplier                                 |

### rewards.mvp

| Key              | Default | Description                                                  |
|-------------------|---------|------------------------------------------------------------------|
| `count`          | `3`     | Number of top individual contributors rewarded                   |
| `acting-effort`  | `10.0`  | Acting effort granted to each MVP                                 |
| `command`        | `""`    | Optional console command run per MVP (`%player%` placeholder)    |

### rewards.holder

| Key                             | Default | Description                                                    |
|------------------------------------|---------|---------------------------------------------------------------|
| `streak-bonus-per-win`            | `0.02`  | Added to the rank-1 delta per consecutive win, before the cap  |
| `max-streak-bonus`                | `0.06`  | Cap on the total streak bonus                                  |
| `dethrone-resource-multiplier`    | `1.25`  | Resource-share multiplier for dethroning a holder with streak ≥ 2 |

## combat-log

| Key                   | Default | Description                                      |
|-----------------------|---------|--------------------------------------------------|
| `npc-duration-minutes`| `5`     | How long Hollow Body NPC persists                |
| `npc-name-format`     | varies  | NPC display name template (`%player%` supported) |

## permanent-zones

| Key                              | Default | Description                              |
|----------------------------------|---------|------------------------------------------|
| `poi-count`                      | `3`     | Active PoIs per zone                     |
| `poi-duration-seconds`           | `300`   | PoI lifespan before rotation             |
| `extraction-point-count`         | `2`     | Active extraction points per zone        |
| `extraction-point-duration-seconds` | `180` | Extraction point lifespan             |
| `poi-capture-radius`             | `8.0`   | PoI accumulation radius (blocks)         |
| `extraction-radius`              | `6.0`   | Extraction point deposit radius (blocks) |
| `extraction-channel-seconds`     | `10`    | Seconds standing at an extraction point to bank resources |
| `poi-base-amount` / `poi-base-interval` | `1.0` / `10` | Baseline accumulation rate (amount per interval seconds) |
| `poi-bonus-amount` / `poi-bonus-interval` | `0.5` / `30` | Extra amount per base-interval for each full bonus-interval of continuous stay |
