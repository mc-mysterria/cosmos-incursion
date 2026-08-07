package net.mysterria.cosmos.domain.beacon.task;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.beacon.model.BeaconCapture;
import net.mysterria.cosmos.domain.beacon.service.BeaconManager;
import net.mysterria.cosmos.domain.beacon.model.SpiritBeacon;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.beacon.service.BeaconUIManager;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task that runs every second to update beacon capture progress
 * - Counts players per town in capture radius, grouped into Nation-level factions
 * - Calculates capture rate (1 point/player/second)
 * - Handles contested state and decay
 */
public class BeaconCaptureTask extends BukkitRunnable {

    private final CosmosIncursion plugin;
    private final BeaconManager beaconManager;
    private final BeaconUIManager beaconUIManager;
    private final CosmosConfig config;

    public BeaconCaptureTask(CosmosIncursion plugin, BeaconManager beaconManager,
                             BeaconUIManager beaconUIManager) {
        this.plugin = plugin;
        this.beaconManager = beaconManager;
        this.beaconUIManager = beaconUIManager;
        this.config = plugin.getConfigLoader().getConfig();
    }

    @Override
    public void run() {
        try {
            // Process each beacon
            for (BeaconCapture capture : beaconManager.getAllCaptureStates()) {
                processBeacon(capture);
            }
        } catch (Exception e) {
            plugin.log("Error in BeaconCaptureTask: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Process capture mechanics for a single beacon
     */
    private void processBeacon(BeaconCapture capture) {
        SpiritBeacon beacon = capture.getBeacon();

        TownPresence presence = playersNearBeacon(beacon);
        Map<Integer, List<Player>> townPlayers = presence.byTown();

        if (townPlayers.isEmpty()) {
            // No players nearby - decay
            handleDecay(capture);
        } else if (distinctFactions(townPlayers.keySet(), presence.factionByTown()) == 1) {
            // Every town present belongs to the same faction (same Nation, or a single
            // unaffiliated town) - they cooperate on the capture rather than contesting it.
            handleCapture(capture, townPlayers);
        } else {
            // Multiple rival factions present - contested
            handleContested(capture, townPlayers, presence.factionByTown());
        }

        // Update UI for all nearby players
        beaconUIManager.updateBeaconUI(capture, beacon);
    }

    /** Per-town player presence near a beacon, plus each town's faction for contest resolution. */
    private record TownPresence(Map<Integer, List<Player>> byTown, Map<Integer, Integer> factionByTown) {}

    /**
     * Group players by town among those within capture radius, and record each town's faction.
     */
    private TownPresence playersNearBeacon(SpiritBeacon beacon) {
        Map<Integer, List<Player>> byTown = new HashMap<>();
        Map<Integer, Integer> factionByTown = new HashMap<>();
        double captureRadius = config.getBeaconCaptureRadius();

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Skip Citizens NPCs (they have "NPC" metadata)
            if (player.hasMetadata("NPC")) {
                continue;
            }

            // Check if player is within capture radius
            if (!beacon.isWithinCaptureRadius(player.getLocation(), captureRadius)) {
                continue;
            }

            if (player.getGameMode() != GameMode.SURVIVAL){
                continue;
            }

            // Get player's town
            Optional<TownData> townOpt = TownsToolkit.getPlayerTown(player);
            if (townOpt.isEmpty()) {
                continue;
            }

            TownData town = townOpt.get();
            byTown.computeIfAbsent(town.id(), k -> new ArrayList<>()).add(player);
            factionByTown.putIfAbsent(town.id(), town.factionId());
        }

        return new TownPresence(byTown, factionByTown);
    }

    private long distinctFactions(java.util.Set<Integer> townIds, Map<Integer, Integer> factionByTown) {
        return townIds.stream().map(factionByTown::get).distinct().count();
    }

    /**
     * Handle beacon capture by a single faction, which may span several allied towns.
     * All present players (from every allied town) count toward capture speed and personal
     * contribution; only the per-town hold-time ledger that drives the resource split is
     * credited to whichever single town has the most players present this tick (ties favor
     * the current owner, to avoid ownership flickering between allies of equal size).
     */
    private void handleCapture(BeaconCapture capture, Map<Integer, List<Player>> townPlayers) {
        capture.setContested(false);

        int leadingTownId = pickLeadingTown(townPlayers, capture.getOwningTownId());
        Optional<TownData> townOpt = TownsToolkit.getTownById(leadingTownId);
        if (townOpt.isEmpty()) {
            return;
        }
        TownData leadingTown = townOpt.get();

        List<Player> allPresent = townPlayers.values().stream()
                .flatMap(List::stream)
                .toList();

        // Calculate capture delta from every allied player present, not just the leading town's
        double pointsPerPlayer = config.getPointsPerPlayer();
        double delta = pointsPerPlayer * allPresent.size();

        // Apply capture progress
        capture.updateProgress(delta, leadingTown, config.getBeaconCapturePoints());

        // Beacon just completed capture this tick - log and reward everyone who helped secure it
        if (capture.consumeJustCaptured()) {
            plugin.log("Beacon " + capture.getBeacon().name() + " captured by " + leadingTown.name());
            double captureBonus = tierWeight(capture.getBeacon()) * config.getContributionCaptureWeight();
            for (Player player : allPresent) {
                plugin.getActingRewardManager().grantBeaconCaptureActing(player);
                plugin.getContributionTracker().credit(player, captureBonus);
            }
        }

        // Personal hold credit for every allied player present, once the beacon is actually
        // owned by the leading town (mirrors the per-town ledger in BeaconCapture, which only
        // counts confirmed ownership, not progress made while still capturing a neutral or
        // enemy-held beacon).
        if (capture.isOwnedBy(leadingTownId)) {
            double perSecond = tierWeight(capture.getBeacon()) * config.getContributionHoldWeight();
            for (Player player : allPresent) {
                plugin.getContributionTracker().credit(player, perSecond);
            }
        }
    }

    /** Deterministic tie-break: most players present; ties prefer the current owner, then the lowest town id. */
    private int pickLeadingTown(Map<Integer, List<Player>> townPlayers, int currentOwnerId) {
        List<Map.Entry<Integer, List<Player>>> entries = new ArrayList<>(townPlayers.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        int leadingTownId = entries.get(0).getKey();
        int leadingCount = -1;
        for (Map.Entry<Integer, List<Player>> entry : entries) {
            int count = entry.getValue().size();
            boolean better = count > leadingCount
                    || (count == leadingCount && entry.getKey() == currentOwnerId);
            if (better) {
                leadingTownId = entry.getKey();
                leadingCount = count;
            }
        }
        return leadingTownId;
    }

    /**
     * Handle contested beacon (rival factions present)
     */
    private void handleContested(BeaconCapture capture, Map<Integer, List<Player>> townPlayers,
                                 Map<Integer, Integer> factionByTown) {
        if (!capture.isContested()) {
            plugin.log("Beacon " + capture.getBeacon().name() + " is now contested");
        }
        capture.setContested(true);
        // No progress change when contested, but still advance the clock so contested hold time
        // accrues to whichever town currently owns the beacon (rewarded more heavily than
        // uncontested holding — see ContributionTracker).
        capture.updateProgress(0, null, config.getBeaconCapturePoints());

        int owningTownId = capture.getOwningTownId();
        if (owningTownId == 0 || !townPlayers.containsKey(owningTownId)) {
            return;
        }

        // Credit every town sharing the owner's faction (allies helping defend), not just the
        // owner's own roster — matches the cooperative treatment in handleCapture.
        int defendingFaction = factionByTown.get(owningTownId);
        List<Player> defenders = townPlayers.entrySet().stream()
                .filter(entry -> defendingFaction == factionByTown.get(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .toList();

        double perSecond = tierWeight(capture.getBeacon()) * config.getContributionContestedHoldWeight();
        for (Player defender : defenders) {
            plugin.getContributionTracker().credit(defender, perSecond);
        }
    }

    /** Weight multiplier for the incursion zone tier the beacon belongs to. */
    private double tierWeight(SpiritBeacon beacon) {
        return config.getContributionTierWeights().getOrDefault(beacon.tier(), 1.0);
    }

    /**
     * Handle beacon decay (no players nearby)
     */
    private void handleDecay(BeaconCapture capture) {
        capture.setContested(false);

        // Only decay if beacon is not at zero
        if (capture.getCaptureProgress() > 0) {
            double decayRate = config.getDecayRate();
            capture.updateProgress(-decayRate, null, config.getBeaconCapturePoints());
        }
    }

}
