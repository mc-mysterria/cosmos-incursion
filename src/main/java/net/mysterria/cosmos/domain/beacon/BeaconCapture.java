package net.mysterria.cosmos.domain.beacon;

import lombok.Getter;
import lombok.Setter;
import net.william278.husktowns.town.Town;

/**
 * Tracks the capture state of a Spirit Beacon
 * Manages capture progress, ownership, and contested status
 */
@Getter
public class BeaconCapture {

    private final SpiritBeacon beacon;
    private int owningTownId;
    private String owningTownName;
    private double captureProgress;
    /**
     * -- SETTER --
     *  Set contested status
     */
    @Setter
    private boolean contested;
    private long lastUpdateTime;
    private long totalOwnershipTime;  // Total milliseconds owned during event

    public BeaconCapture(SpiritBeacon beacon) {
        this.beacon = beacon;
        this.owningTownId = 0;
        this.owningTownName = null;
        this.captureProgress = 0.0;
        this.contested = false;
        this.lastUpdateTime = System.currentTimeMillis();
        this.totalOwnershipTime = 0;
    }

    /**
     * Update capture progress
     * @param delta Change in capture points
     * @param capturingTown The town attempting to capture (nullable if decaying)
     * @param maxPoints Maximum capture points needed
     */
    public void updateProgress(double delta, Town capturingTown, double maxPoints) {
        long now = System.currentTimeMillis();

        // Track ownership time if beacon is owned
        if (owningTownId != 0 && !contested) {
            long elapsed = now - lastUpdateTime;
            totalOwnershipTime += elapsed;
        }

        lastUpdateTime = now;

        // Apply delta to progress
        captureProgress = Math.max(0, Math.min(maxPoints, captureProgress + delta));

        // Check if fully captured
        if (captureProgress >= maxPoints && capturingTown != null) {
            // Check if this is a new owner
            if (!(owningTownId == capturingTown.getId())) {
                setOwner(capturingTown);
            }
        }
        // Check if ownership lost
        else if (captureProgress <= 0) {
            clearOwner();
        }
    }

    /**
     * Set the owning town
     */
    private void setOwner(Town town) {
        this.owningTownId = town.getId();
        this.owningTownName = town.getName();
        this.contested = false;
    }

    /**
     * Clear ownership
     */
    private void clearOwner() {
        this.owningTownId = 0;
        this.owningTownName = null;
        this.captureProgress = 0.0;
        this.contested = false;
    }

    /**
     * Check if beacon is owned by a specific town
     */
    public boolean isOwnedBy(int townId) {
        return owningTownId != 0 && owningTownId == townId;
    }

    /**
     * Get capture progress as percentage
     */
    public double getCapturePercentage(double maxPoints) {
        return (captureProgress / maxPoints) * 100.0;
    }

    /**
     * Reset beacon state (used when event ends)
     */
    public void reset() {
        this.owningTownId = 0;
        this.owningTownName = null;
        this.captureProgress = 0.0;
        this.contested = false;
        this.lastUpdateTime = System.currentTimeMillis();
        this.totalOwnershipTime = 0;
    }

    /**
     * Get total ownership time in seconds
     */
    public long getTotalOwnershipSeconds() {
        long total = totalOwnershipTime;

        // Add current ownership time if owned and not contested
        if (owningTownId != 0 && !contested) {
            long elapsed = System.currentTimeMillis() - lastUpdateTime;
            total += elapsed;
        }

        return total / 1000L;
    }

}
