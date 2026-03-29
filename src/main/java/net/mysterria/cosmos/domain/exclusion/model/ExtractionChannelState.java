package net.mysterria.cosmos.domain.exclusion.model;

import lombok.Getter;

import java.util.UUID;

/**
 * Tracks a player's active extraction channel at an extraction point.
 * The player must remain within range for the full channel duration to complete extraction.
 */
@Getter
public class ExtractionChannelState {

    private final UUID playerId;
    private final ExtractionPoint extractionPoint;
    private final long startTimeMillis;

    public ExtractionChannelState(UUID playerId, ExtractionPoint extractionPoint) {
        this.playerId = playerId;
        this.extractionPoint = extractionPoint;
        this.startTimeMillis = System.currentTimeMillis();
    }

    public long getElapsedMillis() {
        return System.currentTimeMillis() - startTimeMillis;
    }

    /** Returns extraction progress from 0.0 to 1.0. */
    public float getProgress(long channelMillis) {
        return Math.min(1.0f, (float) getElapsedMillis() / channelMillis);
    }

    public boolean isComplete(long channelMillis) {
        return getElapsedMillis() >= channelMillis;
    }
}
