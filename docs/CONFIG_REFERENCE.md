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

| Key                | Default | Description                              |
|--------------------|---------|------------------------------------------|
| `capture-radius`   | varies  | Radius to count players for capture      |
| `points-per-player`| varies  | Capture progress per player per second   |
| `decay-rate`       | varies  | Progress decay when uncontested          |
| `buff-duration-hours` | `24` | Duration of Acting Speed buff           |
| `acting-speed-bonus` | `1.10` | Multiplier applied to winning town      |

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
| `extraction-rate-per-second`     | `5.0`   | Buffer drain rate during extraction      |
