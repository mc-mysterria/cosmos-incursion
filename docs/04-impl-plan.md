# Implementation Plan — Weekly Vault & Beyonder Raids

**Ship order:** Vault first (wraps all existing content immediately), then Raid.

---

## Phase 1 — Weekly Vault (CoI) · ✅ COMPLETE

### Step 1 · Core domain model
**Package:** `domain/vault/`

| Class | Responsibility |
|---|---|
| `VaultTrack` | Enum: `DUNGEONS`, `WORLD`, `BEYONDER` |
| `VaultProgress` | Per-player state: track → credits accumulated, slot unlock flags, rolled reward per slot, expiry week |
| `VaultService` | Credit grant, weekly reset, seeded roll, expiry sweep |
| `VaultGUI` | Chest GUI showing ≤3 unlocked slots; player picks one, rest vanish |

**Storage:** standalone `data/vault/<uuid>.yml` — avoids tying vault progress to Beyonder status.

**Seeded roll:** seed = `playerUUID.mostSignificantBits XOR isoWeekNumber` — same choices every time the GUI is opened within the week.

---

### Step 2 · Cross-plugin credit API
**Location:** CoI API module

```java
// Bukkit event — all plugins call this (CoI API 1.4.9-SNAPSHOT, see C-1)
public class VaultCreditEvent extends Event {   // non-cancellable, main thread
    Player player;        // online player
    VaultTrack track;     // DUNGEONS / WORLD / BEYONDER
    int amount;           // ≤ 0 ignored by CoI's listener
}
```

**Emitter wiring:**

| Plugin | Call site | Track | Status |
|---|---|---|---|
| VOTS | `DungeonCoordinator.handleCompletion` (per confirmed completion) | `DUNGEONS` | ✅ emits `VaultCreditEvent` (CoI API 1.4.9-SNAPSHOT) |
| Cosmos | `RewardDistributor.distribute` after event payout (per online contributor) | `WORLD` | ✅ emits `VaultCreditEvent` (CoI API 1.4.9-SNAPSHOT) |
| OGA | `ContributionTracker.onDefeat` + `RaidManager` boss-kill branch | `WORLD` | ✅ emits `VaultCreditEvent` (CoI API 1.4.9-SNAPSHOT) |
| Minigame network | `RedisVaultBridge` drains `<prefix>:vault-credits` Redis list, fires `VaultCreditEvent` per online player | any track | ✅ CoI-side bridge done; minigame servers still need to push messages |

---

### Step 3 · Reward pools
**File:** `vault-rewards.yml`

```yaml
pools:
  low:    # slot 1 quality
    - { id: "coi:acting_bottle_i",    weight: 40 }
    - { id: "coi:spirituality_potion",weight: 30 }
    - { id: "coi:seq4_ingredient",    weight: 30 }
  mid:    # slot 2 quality
    - { id: "coi:acting_bottle_ii",   weight: 35 }
    - { id: "coi:seq3_ingredient",    weight: 40 }
    - { id: "coi:keystone_fragment",  weight: 25 }
  high:   # slot 3 quality
    - { id: "coi:acting_multiplier",  weight: 30 }
    - { id: "coi:seq2_ingredient",    weight: 40 }
    - { id: "coi:seq2_recipe_token",  weight: 30 }
```

Resolve IDs through existing `BaublesFactory`. Add keystone items (Plan 03) as another pool entry once Plan 03 ships.

---

### Step 4 · Reset scheduler
- Register via `ManagedScheduler` on plugin enable.
- Fire every Monday 00:00 UTC (ISO week boundary).
- On fire: roll rewards for all players with `credits > 0`, set expiry to `currentWeek + 2`, sweep expired vaults (week < `currentWeek - 2`).

---

### Step 5 · Command & lang keys
- `/coi vault` via LiteCommands — opens `VaultGUI`.
- Lang keys needed (EN + UK): `vault.slot.locked`, `vault.slot.unlocked`, `vault.slot.claimed`, `vault.expired`, `vault.pick-one`, `vault.reward-claimed`.

---

## Phase 2 — Beyonder Raids (OGA) · ✅ COMPLETE

### Step 6 · `raid/` package skeleton · ✅

| Class | Responsibility |
|---|---|
| `RaidSchedule` | Reads config window, fires open/close events, Discord webhook announce |
| `RaidInstance` | State machine: `LOBBY → WAVE(n) → CLEARED / FAILED` |
| `WaveSpawner` | Reuses OGA defender-budget logic; budget = `base × waveIndex × partyPower` |
| `RaidContribution` | Per-raid scoring: kill splits, heal weight, revive credit |
| `RaidLockoutDao` | SQLite table `raid_lockouts(uuid, iso_week, year, tier)` |
| `RaidArenaService` | Teleports raiders into the arena world and back; persists each return spot (`raid_return_locations`) so nobody is stranded on leave/death/disconnect/restart |
| `RaidReturnDao` | SQLite table `raid_return_locations(uuid, world, x, y, z, yaw, pitch)` |

**Notes:**
- Arena uses a static configurable world + XYZ (`raid.arena.*` in config.yml) rather than MythicDungeons — the simpler path endorsed by the plan. Admin supplies an always-loaded flat/void world (Multiverse etc.); OGA resolves it by name and does not create it.
- No crash recovery for the raid *instance* (a restart fails the active run), **but** stranded players are recovered: any `raid_return_locations` row still present on the next enable teleports that player (online now, or on their next join) back out of the arena.

---

### Step 7 · Arena & party formation · ✅

- **Arena:** Static configurable world + XYZ (`raid.arena.*`). MythicDungeons integration deferred.
- **Party formation:** Players join via `/oga raid join [normal|ordeal]`. Min 3 / max 10 enforced; `beyonder-only: true` default gates on CoI sequence ≥ 0.
- **Teleport in:** on raid start, every lobby player is teleported to `(arena.x+0.5, arena.y, arena.z+0.5)`.
- **Teleport out** (`RaidArenaService.sendBack`): on clear, fail, `/oga raid leave`, death (via `PlayerRespawnEvent`), and disconnect-then-relog (`PlayerJoinEvent`). Return spot = wherever the player stood when the raid started, persisted in `raid_return_locations`.
- **`/oga raid leave`:** drops the player from the lobby/run and teleports them back if the raid had started.
- Entry: command-based. NPC/portal can be wired later by calling `joinRaid()` from an interaction listener.

---

### Step 8 · Wave design · ✅

| Wave | Content | Reward bracket |
|---|---|---|
| 1–4 | Standard defender budget, scaled | Seq 4 ingredients |
| 5 | Miniboss (OGA MINIBOSS role) | Seq 4 + small Seq 3 |
| 6–7 | Elevated budget | Seq 3 ingredients |
| 8–9 | High budget | Seq 2 ingredients |
| 10 | Final boss — rotating god theme (1 of 5 god YAMLs per week) | Rolled Seq 2 recipe/ingredient + vault credit + raid cosmetic/title |

God rotation: `godIndex = isoWeek % 5` → maps to existing `gods/*.yml`.
Per-god boss mob: add `raid-boss: <MythicMob internal name>` to the god's YAML; falls back to a MINIBOSS roster spawn if omitted.

---

### Step 9 · Contribution & qualification · ✅

- Qualification threshold: `wave-qualify-threshold: 15` (configurable).
- `heal-weight: 0.05` per HP healed — hooked via `EntityRegainHealthEvent` in `RaidEventListener`.
- `revive-weight: 5` — hooked in `RaidContribution.recordRevive`.
- Players below threshold for a wave bracket receive no loot for that bracket (not kicked).
- Bracket rewards are cumulative — each bracket the player qualified for is awarded independently.

---

### Step 10 · Reward config & lockout · ✅

**File:** `raid-rewards.yml`

```yaml
wave-brackets:
  - min-wave: 1
    max-wave: 4
    commands: [ "coi give ingredient seq4 2 %player%" ]
  - min-wave: 5
    max-wave: 7
    commands: [ "coi give ingredient seq3 2 %player%" ]
  - min-wave: 8
    max-wave: 9
    commands: [ "coi give ingredient seq2 1 %player%" ]

boss-kill:
  commands:
    - "coi give token seq2_recipe 1 %player%"
    - "give %player% minecraft:name_tag 1"
```

Lockout: one rewarded clear per player per week per tier (`raid_lockouts` DB table). Second clear = practice mode. Normal + Ordeal have independent lockouts.

---

### Step 11 · Discord webhook + vault credit emission · ✅

- Discord announces wired: 30-min warning (`notifyRaidWarning`) and window-open (`notifyRaidOpen`) in `DiscordNotifier`.
- `discord.enabled: false` default — flip on once webhooks are configured.
- **Vault credit emission:** OGA fires `VaultCreditEvent(player, VaultTrack.WORLD, 1)` once per
  qualifying player — from `ContributionTracker.onDefeat()` (anchor defeat, score ≥
  `min-score-for-rewards`) and from `RaidManager.grantBracketRewards()` (wave-10 boss clear +
  qualified for wave 10). CoI accumulates the credit for the current ISO week, or ignores it if its
  weekly-vault feature is off. Nothing to configure.

---

## Phase 3 — CoI Work Required to Close the Loop

> **Status 2026-09-06:** CoI API `1.4.9-SNAPSHOT` ships `VaultCreditEvent`
> (`dev.ua.ikeepcalm.coi.api.event`) and `VaultTrack`
> (`dev.ua.ikeepcalm.coi.api.model`). C-1 is done. OGA is on `1.4.9-SNAPSHOT` and emits the event
> from both call sites (C-3 done). C-2 is done on branch `vault-implementation` — `VaultService`
> listens for `VaultCreditEvent` and `RedisVaultBridge` drains the minigame Redis queue.
> VOTS is on `1.4.9-SNAPSHOT` and emits the event from `DungeonCoordinator.handleCompletion`
> (C-4 VOTS done). Cosmos is on `1.4.9-SNAPSHOT` and emits `VaultCreditEvent(player, WORLD, 1)`
> per online contributor from `RewardDistributor.distribute` (C-4 Cosmos done).
> Remaining: minigame servers pushing into the Redis queue.

### C-1 · `VaultCreditEvent` in the CoI API module — ✅ done (1.4.9-SNAPSHOT)

Shipped signature (differs from the original sketch — takes an online `Player`, not `PlayerData`):

```java
package dev.ua.ikeepcalm.coi.api.event;

public class VaultCreditEvent extends Event {          // non-cancellable, fire on main thread
    public VaultCreditEvent(@NotNull Player player, @NotNull VaultTrack track, int amount);
    public Player getPlayer();
    public UUID getPlayerId();
    public VaultTrack getTrack();
    public int getAmount();                            // ≤ 0 ignored by the listener
}
```

`VaultTrack` (`dev.ua.ikeepcalm.coi.api.model`): `DUNGEONS` / `WORLD` / `BEYONDER`, each with
`id()` and `fromId(String)`.

---

### C-2 · Listen to `VaultCreditEvent` inside CoI and credit the vault — ✅ done (`vault-implementation`)

`VaultService` (`domain/vault/service/VaultService.java`) `implements Listener`; registered at
startup in `CircleOfImagination` alongside `VaultService.getInstance().load(...)`.

```java
@EventHandler
public void onVaultCredit(VaultCreditEvent event) {
    if (event.getAmount() <= 0) return;
    UUID uuid = event.getPlayer().getUniqueId();
    VaultProgress progress = getOrCreate(uuid);
    // accumulates credit for the current ISO week
}
```

**Minigame Redis path:** `RedisVaultBridge.scheduleQueueDrain()` (scheduled on enable) pops
`<prefix>:vault-credits` list entries and re-fires `VaultCreditEvent` for each online player, so
off-server minigame wins fold into the same listener.

---

### C-3 · OGA emission — ✅ done (`VaultCreditEvent`)

OGA fires `VaultCreditEvent(player, VaultTrack.WORLD, 1)` from two call sites, once per qualifying
player, on the main thread:

| File | Method | Gate |
|---|---|---|
| `ContributionTracker.java` | `onDefeat` → `grantVaultCredit(UUID)` | contributor score ≥ `min-score-for-rewards`; skipped if the player is offline |
| `RaidManager.java` | `grantBracketRewards` → `grantVaultCredit(Player)` | wave-10 clear + qualified for wave 10 |

`build.gradle.kts` → `circle-of-imagination-api:1.4.9-SNAPSHOT`.

---

### C-4 · Wire VOTS and Cosmos emitters (other plugins — not OGA)

| Plugin | File | Call site | Status |
|---|---|---|---|
| VOTS | `DungeonCoordinator.handleCompletion` → `grantVaultCredit(Player)` | Per confirmed completion (boss beaten), once per player per session; gated on `coiAPI.isBeyonder(player)`. Emits `VaultCreditEvent(player, DUNGEONS, 1)` on the main thread. `build.gradle` → `circle-of-imagination-api:1.4.9-SNAPSHOT`. | ✅ done |
| Cosmos | `RewardDistributor.distribute` → `emitVaultCredits()` | At event end, after standings/buffs/resources/MVP payout, emits `VaultCreditEvent(player, WORLD, 1)` once per online player tracked by `ContributionTracker` (anyone who contributed anything this event). Offline contributors skipped. `build.gradle` → `circle-of-imagination-api:1.4.9-SNAPSHOT`. | ✅ done |

---

## Dependency Map

```
Phase 1 Steps 1–5  (Vault, no external deps)
         ↓
Phase 2 Steps 6–11 (Raid — OGA complete; emits VaultCreditEvent)
         ↓
Phase 3 C-1 ✅ · C-2 ✅ · C-3 ✅ · C-4 VOTS ✅ · C-4 Cosmos ✅  ·  remaining: minigame Redis push
```

---

## Open Questions

| # | Question | Status |
|---|---|---|
| 1 | Vault storage: Beyonder YAML vs standalone? | ✅ Standalone `data/vault/<uuid>.yml` |
| 2 | Raid instancing: MythicDungeons vs static arena? | ✅ Static arena world (`raid.arena.*`) — `RaidArenaService` teleports players in/out, return spots persisted for crash recovery |
| 3 | Lockout per-tier or shared? | ✅ Per-tier |
| 4 | Minigame Redis format: does it already emit wins? | ⬜ CoI side ready (`RedisVaultBridge` consumes `<prefix>:vault-credits`); minigame servers must push to that list |

---

## Acceptance Criteria

**Vault:**
- [x] `/coi vault` opens GUI showing correct unlocked slots after credit events fire
- [x] Seeded roll: closing and reopening shows identical choices within the same week
- [x] Weekly reset rolls rewards and clears credits; expiry sweeps after 2 weeks
- [x] OGA emits `VaultCreditEvent` at both call sites; `VaultService` listener credits the vault; `RedisVaultBridge` folds in minigame-network wins. VOTS `DungeonCoordinator` emits on the `DUNGEONS` track *(C-4)*. Cosmos `RewardDistributor` emits on the `WORLD` track per online contributor at event end *(C-4)*

**Raid:**
- [x] Raid window opens and closes on schedule; Discord embed fires 30 min before
- [x] 10 waves spawn with correct budgets; miniboss at wave 5, rotating god boss at wave 10
- [x] Healing contribution qualifies support players for bracket rewards
- [x] Weekly lockout: second clear yields no loot; Ordeal lockout is independent of Normal
- [x] `/oga raid join [normal|ordeal]` opens the lobby for the correct tier
- [x] Raid start teleports the party into `raid.arena.*`; clear/fail/leave/death/relog teleports them back out
- [x] Boss kill emits `VaultCreditEvent(WORLD, 1)` for wave-10 qualifiers
