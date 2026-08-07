package net.mysterria.cosmos.domain.beacon.model;

import lombok.Getter;
import lombok.Setter;
import net.mysterria.cosmos.toolkit.towns.TownData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    // Per-town contribution accounting, credited to whichever town currently owns the beacon.
    // Unlike the old single totalOwnershipTime counter, these attribute time to the town that
    // actually held it — a last-second snipe no longer inherits a prior holder's accumulated time.
    private final Map<Integer, Long> ownershipMillisByTown = new HashMap<>();
    private final Map<Integer, Long> contestedMillisByTown = new HashMap<>();
    private final Map<Integer, Integer> capturesByTown = new HashMap<>();

    // True for the tick a new town completes capture; consumed (and reset) by the capture task.
    private boolean justCaptured;

    public BeaconCapture(SpiritBeacon beacon) {
        this.beacon = beacon;
        this.owningTownId = 0;
        this.owningTownName = null;
        this.captureProgress = 0.0;
        this.contested = false;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Update capture progress
     * @param delta Change in capture points
     * @param capturingTown The town attempting to capture (nullable if decaying)
     * @param maxPoints Maximum capture points needed
     */
    public void updateProgress(double delta, TownData capturingTown, double maxPoints) {
        long now = System.currentTimeMillis();

        // Credit the elapsed slice to whichever town currently owns the beacon, split between
        // the ownership and contested ledgers so contested defense can be weighted separately.
        if (owningTownId != 0) {
            long elapsed = now - lastUpdateTime;
            Map<Integer, Long> ledger = contested ? contestedMillisByTown : ownershipMillisByTown;
            ledger.merge(owningTownId, elapsed, Long::sum);
        }

        lastUpdateTime = now;

        // Apply delta to progress
        captureProgress = Math.max(0, Math.min(maxPoints, captureProgress + delta));

        // Check if fully captured
        if (captureProgress >= maxPoints && capturingTown != null) {
            // Check if this is a new owner
            if (!(owningTownId == capturingTown.id())) {
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
    private void setOwner(TownData town) {
        this.owningTownId = town.id();
        this.owningTownName = town.name();
        this.contested = false;
        this.justCaptured = true;
        capturesByTown.merge(town.id(), 1, Integer::sum);
    }

    /** Returns true if the beacon completed capture this tick, and clears the flag. */
    public boolean consumeJustCaptured() {
        boolean result = justCaptured;
        justCaptured = false;
        return result;
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
        this.justCaptured = false;
        this.ownershipMillisByTown.clear();
        this.contestedMillisByTown.clear();
        this.capturesByTown.clear();
    }

    /** Uncontested hold time (seconds) a town has accumulated on this beacon this event. */
    public long getOwnershipSeconds(int townId) {
        long total = ownershipMillisByTown.getOrDefault(townId, 0L);
        if (owningTownId == townId && !contested) {
            total += System.currentTimeMillis() - lastUpdateTime;
        }
        return total / 1000L;
    }

    /** Contested hold time (seconds) a town has accumulated on this beacon this event. */
    public long getContestedSeconds(int townId) {
        long total = contestedMillisByTown.getOrDefault(townId, 0L);
        if (owningTownId == townId && contested) {
            total += System.currentTimeMillis() - lastUpdateTime;
        }
        return total / 1000L;
    }

    /** Number of times a town has completed capture of this beacon this event. */
    public int getCaptures(int townId) {
        return capturesByTown.getOrDefault(townId, 0);
    }

    /** Every town that has held, contested, or captured this beacon this event. */
    public Set<Integer> getInvolvedTownIds() {
        Set<Integer> ids = new HashSet<>(ownershipMillisByTown.keySet());
        ids.addAll(contestedMillisByTown.keySet());
        ids.addAll(capturesByTown.keySet());
        return ids;
    }

}
