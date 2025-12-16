package net.mysterria.cosmos.task;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.beacon.BeaconCapture;
import net.mysterria.cosmos.beacon.BeaconManager;
import net.mysterria.cosmos.beacon.SpiritBeacon;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import net.william278.husktowns.town.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Task that runs every second to update beacon capture progress
 * - Counts players per town in capture radius
 * - Calculates capture rate (1 point/player/second)
 * - Handles contested state and decay
 */
public class BeaconCaptureTask extends BukkitRunnable {

    private final CosmosIncursion plugin;
    private final BeaconManager beaconManager;
    private final net.mysterria.cosmos.beacon.ui.BeaconUIManager beaconUIManager;
    private final CosmosConfig config;

    public BeaconCaptureTask(CosmosIncursion plugin, BeaconManager beaconManager,
                             net.mysterria.cosmos.beacon.ui.BeaconUIManager beaconUIManager) {
        this.plugin = plugin;
        this.beaconManager = beaconManager;
        this.beaconUIManager = beaconUIManager;
        this.config = plugin.getConfigManager().getConfig();
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

        // Count players per town within capture radius
        Map<Integer, Integer> townPlayerCounts = countPlayersNearBeacon(beacon);

        // Determine capture state
        if (townPlayerCounts.isEmpty()) {
            // No players nearby - decay
            handleDecay(capture);
        } else if (townPlayerCounts.size() == 1) {
            // Single town - capturing
            Map.Entry<Integer, Integer> entry = townPlayerCounts.entrySet().iterator().next();
            handleCapture(capture, entry.getKey(), entry.getValue());
        } else {
            // Multiple towns - contested
            handleContested(capture);
        }

        // Update UI for all nearby players
        beaconUIManager.updateBeaconUI(capture, beacon);
    }

    /**
     * Count players per town near a beacon
     */
    private Map<Integer, Integer> countPlayersNearBeacon(SpiritBeacon beacon) {
        Map<Integer, Integer> counts = new HashMap<>();
        double captureRadius = config.getBeaconCaptureRadius();

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Check if player is within capture radius
            if (!beacon.isWithinCaptureRadius(player.getLocation(), captureRadius)) {
                continue;
            }

            // Get player's town
            Optional<Town> townOpt = TownsToolkit.getPlayerTown(player);
            if (townOpt.isEmpty()) {
                continue;
            }

            Town town = townOpt.get();
            counts.merge(town.getId(), 1, Integer::sum);
        }

        return counts;
    }

    /**
     * Handle beacon capture by a single town
     */
    private void handleCapture(BeaconCapture capture, int townId, int playerCount) {
        capture.setContested(false);

        // Get town
        Optional<Town> townOpt = TownsToolkit.getTownById(townId);
        if (townOpt.isEmpty()) {
            return;
        }

        Town town = townOpt.get();

        // Calculate capture delta
        double pointsPerPlayer = config.getPointsPerPlayer();
        double delta = pointsPerPlayer * playerCount;  // Per second

        // Apply capture progress
        capture.updateProgress(delta, town, config.getBeaconCapturePoints());

        // Log ownership changes
        if (capture.isOwnedBy(townId) && capture.getCaptureProgress() >= config.getBeaconCapturePoints()) {
            // Beacon is now fully captured
            if (!capture.getOwningTownName().equals(town.getName())) {
                plugin.log("Beacon " + capture.getBeacon().getName() + " captured by " + town.getName());
            }
        }
    }

    /**
     * Handle contested beacon (multiple towns present)
     */
    private void handleContested(BeaconCapture capture) {
        if (!capture.isContested()) {
            plugin.log("Beacon " + capture.getBeacon().getName() + " is now contested");
        }
        capture.setContested(true);
        // No progress change when contested
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
