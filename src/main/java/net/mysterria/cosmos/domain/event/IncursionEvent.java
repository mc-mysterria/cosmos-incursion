package net.mysterria.cosmos.domain.event;

import lombok.Getter;
import net.mysterria.cosmos.domain.zone.IncursionZone;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public class IncursionEvent {

    private final UUID eventId;
    private final long startTime;
    private final long plannedEndTime;
    private final List<IncursionZone> incursionZones;

    // Countdown for STARTING state
    private int countdownRemaining;

    // Statistics
    private int totalKills;
    private int totalDeaths;

    public IncursionEvent(long durationMillis) {
        this.eventId = UUID.randomUUID();
        this.startTime = System.currentTimeMillis();
        this.plannedEndTime = startTime + durationMillis;
        this.incursionZones = new ArrayList<>();
        this.countdownRemaining = 0;
        this.totalKills = 0;
        this.totalDeaths = 0;
    }

    /**
     * Add a zone to this event
     */
    public void addZone(IncursionZone incursionZone) {
        incursionZones.add(incursionZone);
    }

    /**
     * Set the countdown timer (in seconds)
     */
    public void setCountdown(int seconds) {
        this.countdownRemaining = seconds;
    }

    /**
     * Decrease countdown by 1 second
     * @return true if countdown reached 0
     */
    public boolean tickCountdown() {
        if (countdownRemaining > 0) {
            countdownRemaining--;
            return countdownRemaining == 0;
        }
        return false;
    }

    /**
     * Check if event should end based on time
     */
    public boolean shouldEnd() {
        return System.currentTimeMillis() >= plannedEndTime;
    }

    /**
     * Get remaining time in milliseconds
     */
    public long getRemainingTime() {
        return Math.max(0, plannedEndTime - System.currentTimeMillis());
    }

    /**
     * Increment kill counter
     */
    public void incrementKills() {
        totalKills++;
    }

    /**
     * Increment death counter
     */
    public void incrementDeaths() {
        totalDeaths++;
    }

}
