package net.mysterria.cosmos.zone;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
public class IncursionZone {

    private final UUID id;
    private final String name;
    private final Location center;
    private final double radius;
    private final Set<UUID> playersInside;
    @Setter
    private boolean active;

    public IncursionZone(String name, Location center, double radius) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.center = center;
        this.radius = radius;
        this.active = false;
        this.playersInside = new HashSet<>();
    }

    /**
     * Check if a location is inside this zone
     * Uses squared distance to avoid expensive sqrt() calculations
     */
    public boolean contains(Location location) {
        if (location.getWorld() == null || !location.getWorld().equals(center.getWorld())) {
            return false;
        }

        double dx = location.getX() - center.getX();
        double dz = location.getZ() - center.getZ();
        double distanceSquared = dx * dx + dz * dz;

        return distanceSquared <= radius * radius;
    }

    /**
     * Check if a chunk overlaps with this zone
     */
    public boolean overlapsChunk(World world, int chunkX, int chunkZ) {
        if (!world.equals(center.getWorld())) {
            return false;
        }

        // Convert chunk coordinates to block coordinates (center of chunk)
        double chunkCenterX = (chunkX << 4) + 8;
        double chunkCenterZ = (chunkZ << 4) + 8;

        // Check if any corner of the chunk is within the zone
        double[][] corners = {
                {chunkCenterX - 8, chunkCenterZ - 8},
                {chunkCenterX + 8, chunkCenterZ - 8},
                {chunkCenterX - 8, chunkCenterZ + 8},
                {chunkCenterX + 8, chunkCenterZ + 8}
        };

        for (double[] corner : corners) {
            double dx = corner[0] - center.getX();
            double dz = corner[1] - center.getZ();
            if (dx * dx + dz * dz <= radius * radius) {
                return true;
            }
        }

        return false;
    }

    /**
     * Add a player to the zone
     */
    public void addPlayer(UUID playerId) {
        playersInside.add(playerId);
    }

    /**
     * Remove a player from the zone
     */
    public void removePlayer(UUID playerId) {
        playersInside.remove(playerId);
    }

    /**
     * Get the distance from the center to a location (2D)
     */
    public double getDistanceFromCenter(Location location) {
        if (location.getWorld() == null || !location.getWorld().equals(center.getWorld())) {
            return Double.MAX_VALUE;
        }

        double dx = location.getX() - center.getX();
        double dz = location.getZ() - center.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Check if this zone is too close to another zone
     */
    public boolean isTooClose(IncursionZone other, double minDistance) {
        if (!center.getWorld().equals(other.getCenter().getWorld())) {
            return false;
        }

        double dx = center.getX() - other.getCenter().getX();
        double dz = center.getZ() - other.getCenter().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        return distance < minDistance;
    }

}
