package net.mysterria.cosmos.combat;

import lombok.Getter;
import org.bukkit.Location;

import java.util.UUID;

/**
 * Represents a Hollow Body NPC spawned when a player combat logs
 * Stores data needed for penalty application
 */
@Getter
public class HollowBody {

    private final UUID playerId;
    private final String playerName;
    private final int npcId;
    private final Location spawnLocation;
    private final long spawnTime;
    private final long despawnTime;
    private boolean wasKilled;

    public HollowBody(UUID playerId, String playerName, int npcId, Location spawnLocation, long durationMillis) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.npcId = npcId;
        this.spawnLocation = spawnLocation.clone();
        this.spawnTime = System.currentTimeMillis();
        this.despawnTime = spawnTime + durationMillis;
        this.wasKilled = false;
    }

    /**
     * Mark this Hollow Body as killed
     */
    public void markKilled() {
        this.wasKilled = true;
    }

    /**
     * Check if this Hollow Body should despawn (timeout)
     */
    public boolean shouldDespawn() {
        return System.currentTimeMillis() >= despawnTime;
    }

    /**
     * Get remaining time until despawn (in seconds)
     */
    public long getRemainingSeconds() {
        long remaining = (despawnTime - System.currentTimeMillis()) / 1000L;
        return Math.max(0, remaining);
    }

}
