package net.mysterria.cosmos.beacon;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.zone.IncursionZone;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Manages Spirit Beacons and their capture states
 * Loads beacons from beacons.yml configuration
 */
public class BeaconManager {

    private final CosmosIncursion plugin;
    private final Map<String, SpiritBeacon> beacons;
    private final Map<String, BeaconCapture> captureStates;
    private FileConfiguration beaconsConfig;

    public BeaconManager(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.beacons = new LinkedHashMap<>();
        this.captureStates = new HashMap<>();
    }

    /**
     * Initialize capture states for all beacons
     */
    public void initializeCaptureStates() {
        captureStates.clear();

        for (SpiritBeacon beacon : beacons.values()) {
            captureStates.put(beacon.getId(), new BeaconCapture(beacon));
        }

        plugin.log("Initialized capture states for " + captureStates.size() + " beacons");
    }

    /**
     * Reset all beacon capture states
     */
    public void resetAllCaptures() {
        for (BeaconCapture capture : captureStates.values()) {
            capture.reset();
        }
        plugin.log("Reset all beacon capture states");
    }

    /**
     * Get a beacon by ID
     */
    public SpiritBeacon getBeacon(String beaconId) {
        return beacons.get(beaconId);
    }

    /**
     * Get all beacons
     */
    public Collection<SpiritBeacon> getAllBeacons() {
        return new ArrayList<>(beacons.values());
    }

    /**
     * Get capture state for a beacon
     */
    public BeaconCapture getCaptureState(String beaconId) {
        return captureStates.get(beaconId);
    }

    /**
     * Get capture state for a beacon
     */
    public BeaconCapture getCaptureState(SpiritBeacon beacon) {
        return captureStates.get(beacon.getId());
    }

    /**
     * Get all capture states
     */
    public Collection<BeaconCapture> getAllCaptureStates() {
        return new ArrayList<>(captureStates.values());
    }

    /**
     * Get beacons owned by a specific town
     */
    public List<BeaconCapture> getBeaconsOwnedBy(int townId) {
        List<BeaconCapture> owned = new ArrayList<>();
        for (BeaconCapture capture : captureStates.values()) {
            if (capture.isOwnedBy(townId)) {
                owned.add(capture);
            }
        }
        return owned;
    }

    /**
     * Calculate total ownership time for a town across all beacons
     */
    public long getTotalOwnershipTime(int townId) {
        long total = 0;
        for (BeaconCapture capture : captureStates.values()) {
            if (capture.isOwnedBy(townId)) {
                total += capture.getTotalOwnershipSeconds();
            }
        }
        return total;
    }

    /**
     * Get the town with the most total ownership time
     */
    public int getWinningTown() {
        Map<Integer, Long> townTimes = new HashMap<>();

        for (BeaconCapture capture : captureStates.values()) {
            if (capture.getOwningTownId() != 0) {
                int townId = capture.getOwningTownId();
                long time = capture.getTotalOwnershipSeconds();
                townTimes.merge(townId, time, Long::sum);
            }
        }

        if (townTimes.isEmpty()) {
            return 0;
        }

        // Find town with most time
        return townTimes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    /**
     * Check if any beacons are loaded
     */
    public boolean hasBeacons() {
        return !beacons.isEmpty();
    }

    /**
     * Get beacon count
     */
    public int getBeaconCount() {
        return beacons.size();
    }

    /**
     * Generate beacons automatically for a list of incursion zones
     * Creates 1 beacon per zone at the center
     */
    public void generateBeaconsForZones(List<IncursionZone> zones) {
        // Clear existing beacons
        beacons.clear();
        captureStates.clear();

        int beaconCounter = 0;
        for (IncursionZone zone : zones) {
            Location center = zone.getCenter();

            // Create one beacon at the center of the zone
            createBeaconForZone(zone, center, beaconCounter++);
        }

        plugin.log("Auto-generated " + beacons.size() + " beacons for " + zones.size() + " zones");
    }

    /**
     * Create a single beacon for a zone
     */
    private void createBeaconForZone(IncursionZone zone, Location location, int counter) {
        String beaconId = "beacon_" + counter;
        String beaconName = zone.getName() + " - Beacon";

        SpiritBeacon beacon = new SpiritBeacon(beaconId, beaconName, location);
        beacons.put(beaconId, beacon);

        plugin.log("Generated beacon: " + beacon);
    }

    /**
     * Clear all beacons and capture states
     */
    public void clearAllBeacons() {
        beacons.clear();
        captureStates.clear();
        plugin.log("Cleared all beacons");
    }

}
