package net.mysterria.cosmos.domain.beacon.ui;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tracks UI state for a single player during beacon capture events
 * Manages bossbars, scoreboard, sound cooldowns, and last known state
 */
@Getter
@Setter
public class PlayerBeaconUIState {

    /**
     * Active bossbars this player is viewing
     * Key: Beacon ID, Value: BossBar reference
     */
    private final Set<String> activeBossbars;

    /**
     * Sound cooldown tracking
     * Key: "beaconId:soundType", Value: Last play timestamp (millis)
     */
    private final Map<String, Long> lastSoundTimes;
    /**
     * Last title sent timestamp (for cooldown)
     * Key: Beacon ID, Value: Last title timestamp
     */
    private final Map<String, Long> lastTitleTimes;
    /**
     * Cache of last known beacon states for this player
     * Used to detect state changes (contested, ownership change, etc.)
     * Key: Beacon ID, Value: Last known state hash
     */
    private final Map<String, Integer> lastBeaconStates;
    /**
     * Last beacon this player was near (for state change detection)
     */
    private String currentBeaconId;
    /**
     * Reference to player's personal scoreboard (if active)
     */
    private Scoreboard scoreboard;
    /**
     * Last action bar message sent (for duplicate prevention)
     */
    private String lastActionBarMessage;
    /**
     * Timestamp when scoreboard was last updated
     */
    private long lastScoreboardUpdate;

    public PlayerBeaconUIState() {
        this.activeBossbars = new HashSet<>();
        this.lastSoundTimes = new HashMap<>();
        this.lastTitleTimes = new HashMap<>();
        this.lastBeaconStates = new HashMap<>();
        this.currentBeaconId = null;
        this.scoreboard = null;
        this.lastActionBarMessage = "";
        this.lastScoreboardUpdate = 0;
    }

    /**
     * Check if enough time has elapsed since last sound play
     * @param beaconId The beacon ID
     * @param soundType The sound type (ambient, progress, complete, etc.)
     * @param cooldownMillis Minimum time between plays
     * @return true if sound can be played
     */
    public boolean canPlaySound(String beaconId, String soundType, long cooldownMillis) {
        String key = beaconId + ":" + soundType;
        Long lastPlay = lastSoundTimes.get(key);

        if (lastPlay == null) {
            return true;
        }

        return System.currentTimeMillis() - lastPlay >= cooldownMillis;
    }

    /**
     * Record that a sound was just played
     * @param beaconId The beacon ID
     * @param soundType The sound type
     */
    public void recordSoundPlay(String beaconId, String soundType) {
        String key = beaconId + ":" + soundType;
        lastSoundTimes.put(key, System.currentTimeMillis());
    }

    /**
     * Check if enough time has elapsed since last title
     * @param beaconId The beacon ID
     * @param cooldownMillis Minimum time between titles
     * @return true if title can be sent
     */
    public boolean canSendTitle(String beaconId, long cooldownMillis) {
        Long lastTitle = lastTitleTimes.get(beaconId);

        if (lastTitle == null) {
            return true;
        }

        return System.currentTimeMillis() - lastTitle >= cooldownMillis;
    }

    /**
     * Record that a title was just sent
     * @param beaconId The beacon ID
     */
    public void recordTitleSent(String beaconId) {
        lastTitleTimes.put(beaconId, System.currentTimeMillis());
    }

    /**
     * Check if beacon state has changed
     * @param beaconId The beacon ID
     * @param newStateHash Hash of current beacon state
     * @return true if state changed
     */
    public boolean hasBeaconStateChanged(String beaconId, int newStateHash) {
        Integer lastHash = lastBeaconStates.get(beaconId);
        return lastHash == null || lastHash != newStateHash;
    }

    /**
     * Update stored beacon state
     * @param beaconId The beacon ID
     * @param stateHash Hash of current beacon state
     */
    public void updateBeaconState(String beaconId, int stateHash) {
        lastBeaconStates.put(beaconId, stateHash);
    }

    /**
     * Check if scoreboard needs update (based on config interval)
     * @param updateIntervalMillis Minimum time between updates
     * @return true if should update
     */
    public boolean shouldUpdateScoreboard(long updateIntervalMillis) {
        return System.currentTimeMillis() - lastScoreboardUpdate >= updateIntervalMillis;
    }

    /**
     * Record scoreboard update
     */
    public void recordScoreboardUpdate() {
        lastScoreboardUpdate = System.currentTimeMillis();
    }

    /**
     * Clear all UI state (for cleanup)
     */
    public void clear() {
        activeBossbars.clear();
        lastSoundTimes.clear();
        lastTitleTimes.clear();
        lastBeaconStates.clear();
        currentBeaconId = null;
        scoreboard = null;
        lastActionBarMessage = "";
        lastScoreboardUpdate = 0;
    }
}
