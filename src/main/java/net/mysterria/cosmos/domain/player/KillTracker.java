package net.mysterria.cosmos.domain.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.integration.BlueMapIntegration;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks player kills to detect and mark Corrupted Monsters
 * Prevents high-tier players from griefing low-tier players
 */
public class KillTracker {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
    private final BlueMapIntegration blueMapIntegration;
    private final MiniMessage miniMessage;

    // Map of killer UUID -> list of kill timestamps
    private final Map<UUID, List<Long>> killTimestamps;

    // Set of players currently marked as Corrupted Monster
    private final Map<UUID, Long> corruptedMonsters;  // UUID -> expiry time

    public KillTracker(CosmosIncursion plugin, BlueMapIntegration blueMapIntegration) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager().getConfig();
        this.blueMapIntegration = blueMapIntegration;
        this.miniMessage = MiniMessage.miniMessage();
        this.killTimestamps = new ConcurrentHashMap<>();
        this.corruptedMonsters = new ConcurrentHashMap<>();
    }

    /**
     * Record a kill and check for griefing behavior
     * @return true if this is a griefing kill, false otherwise
     */
    public boolean recordKill(Player killer, Player victim) {
        // Check if this qualifies as a griefing kill
        if (!isGriefingKill(killer, victim)) {
            return false;
        }

        UUID killerId = killer.getUniqueId();
        long now = System.currentTimeMillis();

        // Get or create kill list for this killer
        List<Long> kills = killTimestamps.computeIfAbsent(killerId, k -> new ArrayList<>());

        // Add this kill
        kills.add(now);

        // Remove old kills outside the time window
        long timeWindowMillis = config.getGriefTimeWindowSeconds() * 1000L;
        kills.removeIf(timestamp -> (now - timestamp) > timeWindowMillis);

        // Update the map
        killTimestamps.put(killerId, kills);

        plugin.log("Player " + killer.getName() + " has " + kills.size() + " griefing kills in time window");

        // Check if threshold is reached
        if (kills.size() >= config.getGriefKillThreshold()) {
            markAsCorruptedMonster(killer);
        }

        return true;  // This is a griefing kill
    }

    /**
     * Check if a kill qualifies as griefing
     * Griefing = high-tier killing low-tier with sequence difference > threshold
     */
    private boolean isGriefingKill(Player killer, Player victim) {
        // Both must be beyonders
        if (!CoiToolkit.isBeyonder(killer) || !CoiToolkit.isBeyonder(victim)) {
            return false;
        }

        int killerSequence = CoiToolkit.getBeyonderSequence(killer);
        int victimSequence = CoiToolkit.getBeyonderSequence(victim);

        // Calculate sequence difference (lower sequence = stronger)
        // If killer has Seq 3 and victim has Seq 7, difference is 4
        int sequenceDifference = victimSequence - killerSequence;

        // Check if difference exceeds threshold
        if (sequenceDifference >= config.getGriefSequenceDifference()) {
            plugin.log("Griefing kill detected: " + killer.getName() + " (Seq " + killerSequence +
                       ") killed " + victim.getName() + " (Seq " + victimSequence + ")");
            return true;
        }

        return false;
    }

    /**
     * Mark a player as Corrupted Monster
     */
    private void markAsCorruptedMonster(Player player) {
        UUID playerId = player.getUniqueId();

        // Check if already marked
        if (isCorruptedMonster(playerId)) {
            plugin.log("Player " + player.getName() + " is already a Corrupted Monster");
            return;
        }

        // Calculate expiry time
        long durationMillis = config.getCorruptedDurationMinutes() * 60_000L;
        long expiryTime = System.currentTimeMillis() + durationMillis;

        // Mark as corrupted
        corruptedMonsters.put(playerId, expiryTime);

        plugin.log("Marked " + player.getName() + " as Corrupted Monster for " +
                   config.getCorruptedDurationMinutes() + " minutes");

        // Broadcast to all players
        String message = config.getMsgCorruptedMonster().replace("%player%", player.getName());
        Component component = miniMessage.deserialize(message);
        Bukkit.getServer().sendMessage(component);

        // Add BlueMap marker
        if (blueMapIntegration.isAvailable()) {
            blueMapIntegration.markCorruptedMonster(player);
        }

        // Clear kill history
        killTimestamps.remove(playerId);
    }

    /**
     * Remove Corrupted Monster status from a player
     */
    public void removeCorruptedStatus(UUID playerId) {
        if (corruptedMonsters.remove(playerId) != null) {
            plugin.log("Removed Corrupted Monster status from player " + playerId);

            // Remove BlueMap marker
            if (blueMapIntegration.isAvailable()) {
                blueMapIntegration.removeCorruptedMonsterMarker(playerId);
            }
        }
    }

    /**
     * Check if a player is marked as Corrupted Monster
     */
    public boolean isCorruptedMonster(UUID playerId) {
        Long expiryTime = corruptedMonsters.get(playerId);
        if (expiryTime == null) {
            return false;
        }

        // Check if expired
        if (System.currentTimeMillis() >= expiryTime) {
            removeCorruptedStatus(playerId);
            return false;
        }

        return true;
    }

    /**
     * Check if a player is marked as Corrupted Monster
     */
    public boolean isCorruptedMonster(Player player) {
        return isCorruptedMonster(player.getUniqueId());
    }

    /**
     * Clean up expired Corrupted Monster statuses
     * Should be called periodically
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();

        corruptedMonsters.entrySet().removeIf(entry -> {
            if (now >= entry.getValue()) {
                plugin.log("Corrupted Monster status expired for player " + entry.getKey());

                // Remove BlueMap marker
                if (blueMapIntegration.isAvailable()) {
                    blueMapIntegration.removeCorruptedMonsterMarker(entry.getKey());
                }

                return true;
            }
            return false;
        });
    }

    /**
     * Get remaining time for Corrupted Monster status (in seconds)
     */
    public long getRemainingSeconds(UUID playerId) {
        Long expiryTime = corruptedMonsters.get(playerId);
        if (expiryTime == null) {
            return 0;
        }

        long remaining = (expiryTime - System.currentTimeMillis()) / 1000L;
        return Math.max(0, remaining);
    }

}
