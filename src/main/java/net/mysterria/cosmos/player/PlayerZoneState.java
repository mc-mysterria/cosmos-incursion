package net.mysterria.cosmos.player;

import lombok.Getter;
import lombok.Setter;
import net.mysterria.cosmos.player.source.PlayerTier;
import net.mysterria.cosmos.zone.IncursionZone;

import java.util.UUID;

@Getter
public class PlayerZoneState {

    private final UUID playerId;
    private final IncursionZone incursionZone;
    private final long entryTime;
    private final PlayerTier tier;

    @Setter
    private boolean safeModeBlocked;

    public PlayerZoneState(UUID playerId, IncursionZone incursionZone, PlayerTier tier) {
        this.playerId = playerId;
        this.incursionZone = incursionZone;
        this.tier = tier;
        this.entryTime = System.currentTimeMillis();
        this.safeModeBlocked = true; // Blocked by default when in zone
    }

    /**
     * Get time in zone in milliseconds
     */
    public long getTimeInZone() {
        return System.currentTimeMillis() - entryTime;
    }

    /**
     * Get time in zone in seconds
     */
    public long getTimeInZoneSeconds() {
        return getTimeInZone() / 1000L;
    }

}
