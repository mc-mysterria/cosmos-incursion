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
    private final double resourceCap;
    private long activeUntil;
    private double resourcesRemaining;

    public PointOfInterest(UUID id, Location location, ResourceType resourceType,
                           double extractionRadius, long activeUntil, double resourceCap) {
        this.id = id;
        this.location = location;
        this.resourceType = resourceType;
        this.extractionRadius = extractionRadius;
        this.activeUntil = activeUntil;
        this.resourceCap = resourceCap;
        this.resourcesRemaining = resourceCap;
    }

    public PointOfInterest(Location location, ResourceType resourceType,
                           double extractionRadius, long durationMillis, double resourceCap) {
        this(UUID.randomUUID(), location, resourceType, extractionRadius,
                System.currentTimeMillis() + durationMillis, resourceCap);
    }

    /**
     * Consume up to {@code amount} from this PoI's remaining resources.
     * Returns the actual amount consumed (capped at remaining).
     */
    public double consumeResource(double amount) {
        double actual = Math.min(amount, resourcesRemaining);
        resourcesRemaining -= actual;
        return actual;
    }

    public boolean isDepleted() {
        return resourcesRemaining <= 0;
    }

    /** Active while time hasn't expired AND resources haven't been fully drained. */
    public boolean isActive() {
        return System.currentTimeMillis() < activeUntil && !isDepleted();
    }

    public boolean isPlayerInRange(Location playerLoc) {
        if (!playerLoc.getWorld().equals(location.getWorld())) return false;
        double dx = playerLoc.getX() - location.getX();
        double dz = playerLoc.getZ() - location.getZ();
        return (dx * dx + dz * dz) <= extractionRadius * extractionRadius;
    }
}
