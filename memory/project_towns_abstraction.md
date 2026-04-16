---
name: Towns plugin abstraction
description: TownData record abstracts HuskTowns Town and Lands Land; TownsToolkit is the single entry point
type: project
---

HuskTowns and Lands are both soft-dependencies (plugin.yml). The plugin disables itself only if neither is available.

`TownData(int id, String name, Set<UUID> memberUuids)` is the unified record used throughout the codebase instead of the native `Town` or `Land` types.

For Lands, the numeric `id` is derived from `Math.abs(land.getName().hashCode())`, avoiding 0 (the "no owner" sentinel). This is stable across restarts because Java's `String.hashCode()` is deterministic.

`TownsToolkit.init(HuskTownsAPI, LandsIntegration)` is called from `CosmosIncursion.initializeTownsPlugins()` during `onEnable()`. When HuskTowns is present it takes precedence; Lands is the fallback.

**Why:** User wanted multi-towns-plugin support so servers can choose either HuskTowns or Lands.

**How to apply:** Never import `net.william278.husktowns.town.Town` or `me.angeschossen.lands.api.land.Land` in domain/command code — always use `TownData` via `TownsToolkit`.
