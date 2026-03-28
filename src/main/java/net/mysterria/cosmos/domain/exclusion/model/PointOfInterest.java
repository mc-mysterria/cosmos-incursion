package net.mysterria.cosmos.domain.exclusion.model;

import lombok.Getter;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import org.bukkit.Location;

import java.util.UUID;

@Getter
public class PointOfInterest {

    private final UUID id;
    private final Location location;
    private final ResourceType resourceType;
    private final double extractionRadius;
    private long activeUntil;

    public PointOfInterest(UUID id, Location location, ResourceType resourceType,
                           double extractionRadius, long activeUntil) {
        this.id = id;
        this.location = location;
        this.resourceType = resourceType;
        this.extractionRadius = extractionRadius;
        this.activeUntil = activeUntil;
    }

    public PointOfInterest(Location location, ResourceType resourceType,
                           double extractionRadius, long durationMillis) {
        this(UUID.randomUUID(), location, resourceType, extractionRadius,
                System.currentTimeMillis() + durationMillis);
    }

    public boolean isActive() {
        return System.currentTimeMillis() < activeUntil;
    }

    public boolean isPlayerInRange(Location playerLoc) {
        if (!playerLoc.getWorld().equals(location.getWorld())) return false;
        double dx = playerLoc.getX() - location.getX();
        double dz = playerLoc.getZ() - location.getZ();
        return (dx * dx + dz * dz) <= extractionRadius * extractionRadius;
    }
}
