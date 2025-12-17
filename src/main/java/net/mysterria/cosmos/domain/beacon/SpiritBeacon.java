package net.mysterria.cosmos.domain.beacon;

import org.bukkit.Location;

/**
 * Represents a Spirit Beacon - a virtual capture point for territory control
 * Beacons are defined by coordinates in config, not physical blocks
 */
public record SpiritBeacon(String id, String name, Location location) {

    /**
     * Constructor with Location object (for auto-generated beacons)
     */
    public SpiritBeacon(String id, String name, Location location) {
        this.id = id;
        this.name = name;
        this.location = location.clone();
    }

    /**
     * Check if a location is within capture radius of this beacon
     */
    public boolean isWithinCaptureRadius(Location playerLocation, double captureRadius) {
        if (!playerLocation.getWorld().equals(location.getWorld())) {
            return false;
        }

        // 2D distance check (ignore Y coordinate)
        double dx = playerLocation.getX() - location.getX();
        double dz = playerLocation.getZ() - location.getZ();
        double distanceSquared = dx * dx + dz * dz;

        return distanceSquared <= (captureRadius * captureRadius);
    }

    /**
     * Get 2D distance to a location
     */
    public double getDistance(Location other) {
        if (!other.getWorld().equals(location.getWorld())) {
            return Double.MAX_VALUE;
        }

        double dx = other.getX() - location.getX();
        double dz = other.getZ() - location.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String toString() {
        return name + " (" + id + ") at " +
               String.format("%.0f, %.0f, %.0f", location.getX(), location.getY(), location.getZ());
    }

}
