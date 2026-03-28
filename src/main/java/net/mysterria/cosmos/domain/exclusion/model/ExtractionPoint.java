package net.mysterria.cosmos.domain.exclusion.model;

import lombok.Getter;
import org.bukkit.Location;

import java.util.UUID;

@Getter
public class ExtractionPoint {

    private final UUID id;
    private final Location location;
    private final double captureRadius;
    private long activeUntil;

    public ExtractionPoint(UUID id, Location location, double captureRadius, long activeUntil) {
        this.id = id;
        this.location = location;
        this.captureRadius = captureRadius;
        this.activeUntil = activeUntil;
    }

    public ExtractionPoint(Location location, double captureRadius, long durationMillis) {
        this(UUID.randomUUID(), location, captureRadius,
                System.currentTimeMillis() + durationMillis);
    }

    public boolean isActive() {
        return System.currentTimeMillis() < activeUntil;
    }

    public boolean isPlayerInRange(Location playerLoc) {
        if (!playerLoc.getWorld().equals(location.getWorld())) return false;
        double dx = playerLoc.getX() - location.getX();
        double dz = playerLoc.getZ() - location.getZ();
        return (dx * dx + dz * dz) <= captureRadius * captureRadius;
    }
}
