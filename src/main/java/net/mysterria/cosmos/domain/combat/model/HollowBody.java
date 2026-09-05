package net.mysterria.cosmos.domain.combat.model;

import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Represents a Hollow Body NPC spawned when a player combat logs.
 * Owns the transferred inventory until the hollow is killed or the player rejoins.
 */
@Getter
public class HollowBody {

    private final UUID playerId;
    private final String playerName;
    private final int npcId;
    private final Location spawnLocation;
    private final long spawnTime;
    private final long despawnTime;
    private ItemStack[] inventory;
    private ItemStack[] armor;
    private ItemStack offhand;
    private boolean wasKilled;
    private boolean itemsDropped;
    private boolean npcRemoved;
    private Location deathLocation;

    public HollowBody(UUID playerId, String playerName, int npcId, Location spawnLocation,
                      long durationMillis, ItemStack[] inventory, ItemStack[] armor, ItemStack offhand) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.npcId = npcId;
        this.spawnLocation = spawnLocation.clone();
        this.spawnTime = System.currentTimeMillis();
        this.despawnTime = spawnTime + durationMillis;
        this.inventory = inventory;
        this.armor = armor;
        this.offhand = offhand;
        this.wasKilled = false;
        this.itemsDropped = false;
        this.npcRemoved = false;
        this.deathLocation = null;
    }

    /**
     * Mark this Hollow Body as killed at a specific location
     */
    public void markKilled(Location location) {
        this.wasKilled = true;
        this.deathLocation = location != null ? location.clone() : spawnLocation.clone();
    }

    /**
     * Mark the Citizens NPC entity as despawned/destroyed while keeping outcome state
     * for reconnect handling.
     */
    public void markNpcRemoved() {
        this.npcRemoved = true;
    }

    /**
     * Clear stored items after they have been dropped or restored so they cannot be applied twice.
     */
    public void clearStoredItems() {
        this.inventory = null;
        this.armor = null;
        this.offhand = null;
        this.itemsDropped = true;
    }

    /**
     * Check if this Hollow Body NPC entity should despawn (timeout)
     */
    public boolean shouldDespawn() {
        return !npcRemoved && System.currentTimeMillis() >= despawnTime;
    }

    /**
     * Get remaining time until despawn (in seconds)
     */
    public long getRemainingSeconds() {
        long remaining = (despawnTime - System.currentTimeMillis()) / 1000L;
        return Math.max(0, remaining);
    }

}
