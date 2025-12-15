package net.mysterria.cosmos.zone;

import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ZoneManager {

    private final CosmosIncursion plugin;
    private final Map<UUID, IncursionZone> zones;
    private final Map<UUID, IncursionZone> playerZoneMap; // Quick lookup: which zone is a player in?

    public ZoneManager(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.zones = new ConcurrentHashMap<>();
        this.playerZoneMap = new ConcurrentHashMap<>();
    }

    /**
     * Register a new zone
     */
    public void registerZone(IncursionZone incursionZone) {
        zones.put(incursionZone.getId(), incursionZone);
        plugin.log("Registered zone: " + incursionZone.getName() + " at " +
                   String.format("(%.0f, %.0f, %.0f) with radius %.0f",
                        incursionZone.getCenter().getX(),
                        incursionZone.getCenter().getY(),
                        incursionZone.getCenter().getZ(),
                        incursionZone.getRadius()));
    }

    /**
     * Unregister a zone
     */
    public void unregisterZone(UUID zoneId) {
        IncursionZone incursionZone = zones.remove(zoneId);
        if (incursionZone != null) {
            // Remove all players from this zone
            incursionZone.getPlayersInside().forEach(playerZoneMap::remove);
            plugin.log("Unregistered zone: " + incursionZone.getName());
        }
    }

    /**
     * Get zone by ID
     */
    public Optional<IncursionZone> getZone(UUID zoneId) {
        return Optional.ofNullable(zones.get(zoneId));
    }

    /**
     * Get zone by name
     */
    public Optional<IncursionZone> getZone(String name) {
        return zones.values().stream()
                .filter(zone -> zone.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * Get all zones
     */
    public Collection<IncursionZone> getAllZones() {
        return new ArrayList<>(zones.values());
    }

    /**
     * Get all active zones
     */
    public Collection<IncursionZone> getActiveZones() {
        return zones.values().stream()
                .filter(IncursionZone::isActive)
                .toList();
    }

    /**
     * Get the zone that contains a specific location
     */
    public IncursionZone getZoneAt(Location location) {
        return zones.values().stream()
                .filter(zone -> zone.isActive() && zone.contains(location))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the zone a player is currently in
     */
    public IncursionZone getPlayerZone(UUID playerId) {
        return playerZoneMap.get(playerId);
    }

    /**
     * Get the zone a player is currently in
     */
    public IncursionZone getPlayerZone(Player player) {
        return getPlayerZone(player.getUniqueId());
    }

    /**
     * Update player zone tracking (called on movement)
     */
    public void updatePlayerZone(Player player, IncursionZone newIncursionZone) {
        UUID playerId = player.getUniqueId();
        IncursionZone oldIncursionZone = playerZoneMap.get(playerId);

        if (oldIncursionZone == newIncursionZone) {
            return; // No change
        }

        // Remove from old zone
        if (oldIncursionZone != null) {
            oldIncursionZone.removePlayer(playerId);
        }

        // Add to new zone
        if (newIncursionZone != null) {
            newIncursionZone.addPlayer(playerId);
            playerZoneMap.put(playerId, newIncursionZone);
        } else {
            playerZoneMap.remove(playerId);
        }
    }

    /**
     * Activate all zones
     */
    public void activateAllZones() {
        zones.values().forEach(zone -> zone.setActive(true));
        plugin.log("Activated " + zones.size() + " zones");
    }

    /**
     * Deactivate all zones
     */
    public void deactivateAllZones() {
        zones.values().forEach(zone -> {
            zone.setActive(false);
            // Clear players from zone
            zone.getPlayersInside().clear();
        });
        playerZoneMap.clear();
        plugin.log("Deactivated all zones");
    }

    /**
     * Clear all zones
     */
    public void clearAllZones() {
        deactivateAllZones();
        zones.clear();
        plugin.log("Cleared all zones");
    }

    /**
     * Check if a location is inside any active zone
     */
    public boolean isInAnyZone(Location location) {
        return getZoneAt(location) != null;
    }

    /**
     * Get count of players in all zones
     */
    public int getTotalPlayersInZones() {
        return playerZoneMap.size();
    }

}
