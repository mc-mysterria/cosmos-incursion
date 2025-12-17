package net.mysterria.cosmos.domain.player;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.player.source.PlayerTier;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.domain.zone.IncursionZone;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
    private final Map<UUID, PlayerZoneState> playerStates;

    public PlayerStateManager(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager().getConfig();
        this.playerStates = new ConcurrentHashMap<>();
    }

    /**
     * Register a player entering a zone
     */
    public void registerEntry(Player player, IncursionZone incursionZone, PlayerTier tier) {
        UUID playerId = player.getUniqueId();

        // Remove any existing state
        playerStates.remove(playerId);

        // Create new state
        PlayerZoneState state = new PlayerZoneState(playerId, incursionZone, tier);
        playerStates.put(playerId, state);

        plugin.log("Player " + player.getName() + " entered zone as " + tier.name());
    }

    /**
     * Unregister a player leaving a zone
     */
    public void registerExit(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerZoneState state = playerStates.remove(playerId);

        if (state != null) {
            plugin.log("Player " + player.getName() + " exited zone after " +
                       state.getTimeInZoneSeconds() + " seconds");
        }
    }

    /**
     * Get player state (nullable)
     */
    public PlayerZoneState getState(UUID playerId) {
        return playerStates.get(playerId);
    }

    /**
     * Get player state (nullable)
     */
    public PlayerZoneState getState(Player player) {
        return getState(player.getUniqueId());
    }

    /**
     * Check if player is in a zone
     */
    public boolean isInZone(UUID playerId) {
        return playerStates.containsKey(playerId);
    }

    /**
     * Check if player is in a zone
     */
    public boolean isInZone(Player player) {
        return isInZone(player.getUniqueId());
    }

    /**
     * Get all player states
     */
    public Collection<PlayerZoneState> getAllStates() {
        return new ArrayList<>(playerStates.values());
    }

    /**
     * Get all players with a specific tier
     */
    public List<PlayerZoneState> getPlayersByTier(PlayerTier tier) {
        return playerStates.values().stream()
                .filter(state -> state.getTier() == tier)
                .toList();
    }

    /**
     * Clear all player states (used when event ends)
     */
    public void clearAll() {
        playerStates.clear();
        plugin.log("Cleared all player zone states");
    }

    /**
     * Determine player tier based on sequence
     */
    public PlayerTier calculateTier(Player player) {
        // Check if player is a beyonder
        if (!CoiToolkit.isBeyonder(player)) {
            return PlayerTier.INSIGNIFICANT;
        }

        // Get player sequence
        int sequence = CoiToolkit.getBeyonderSequence(player);

        // Spirit Weight applies to sequences 4-5
        if (sequence >= config.getSpiritWeightMinSequence() &&
            sequence <= config.getSpiritWeightMaxSequence()) {
            return PlayerTier.SPIRIT_WEIGHT;
        }

        // All others are Insignificant
        return PlayerTier.INSIGNIFICANT;
    }

}
