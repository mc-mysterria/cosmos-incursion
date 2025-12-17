package net.mysterria.cosmos.integration;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.combat.HollowBody;
import net.mysterria.cosmos.config.CosmosConfig;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles Citizens API integration for Hollow Body NPCs
 * Creates NPCs that represent combat-logged players
 */
public class CitizensIntegration {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
    private final Map<UUID, HollowBody> hollowBodies;
    private final Map<Integer, UUID> npcIdToPlayerId;
    private NPCRegistry registry;

    public CitizensIntegration(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager().getConfig();
        this.hollowBodies = new HashMap<>();
        this.npcIdToPlayerId = new HashMap<>();
    }

    /**
     * Initialize Citizens integration
     */
    public boolean initialize() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Citizens")) {
            return false; // Silent fail if plugin not present
        }

        try {
            // Use the default registry instead of creating a named one
            // Temporary NPCs like Hollow Bodies don't need a separate registry with persistence
            this.registry = CitizensAPI.getNPCRegistry();
            if (this.registry == null) {
                plugin.log("Citizens registry is null - API not ready");
                return false;
            }
            plugin.log("Citizens integration enabled - Hollow Body NPCs active");
            return true;
        } catch (IllegalStateException e) {
            // Citizens API not ready yet
            return false;
        } catch (Exception e) {
            plugin.log("Failed to initialize Citizens integration: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create a Hollow Body NPC for a combat-logged player
     */
    public HollowBody createHollowBody(Player player, Location location) {
        if (registry == null) {
            plugin.log("Cannot create Hollow Body - Citizens not initialized");
            return null;
        }

        try {
            // Capture player inventory before creating NPC
            ItemStack[] inventory = player.getInventory().getContents().clone();
            ItemStack[] armor = player.getInventory().getArmorContents().clone();

            // Create NPC name from config
            String npcName = config.getNpcNameFormat().replace("%player%", player.getName());

            // Create NPC
            NPC npc = registry.createNPC(EntityType.PLAYER, npcName);

            // Make NPC vulnerable (can be killed)
            npc.setProtected(false);

            // Copy player appearance
            npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);
            npc.data().setPersistent(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, false);
            npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, false);

            // Set skin to match player
            npc.getOrAddTrait(SkinTrait.class).setSkinName(player.getName());

            npc.spawn(location);

            // Calculate duration
            long durationMillis = config.getNpcDurationMinutes() * 60_000L;

            // Create HollowBody wrapper with inventory
            HollowBody hollowBody = new HollowBody(
                    player.getUniqueId(),
                    player.getName(),
                    npc.getId(),
                    location,
                    durationMillis,
                    inventory,
                    armor
            );

            // Store mappings
            hollowBodies.put(player.getUniqueId(), hollowBody);
            npcIdToPlayerId.put(npc.getId(), player.getUniqueId());

            plugin.log("Created Hollow Body NPC for " + player.getName() + " (ID: " + npc.getId() + ")");
            return hollowBody;
        } catch (Exception e) {
            plugin.log("Error creating Hollow Body for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Remove a Hollow Body NPC
     */
    public void removeHollowBody(UUID playerId) {
        HollowBody hollowBody = hollowBodies.remove(playerId);
        if (hollowBody != null) {
            removeNPC(hollowBody.getNpcId());
            npcIdToPlayerId.remove(hollowBody.getNpcId());
            plugin.log("Removed Hollow Body for player " + playerId);
        }
    }

    /**
     * Remove an NPC by ID
     */
    private void removeNPC(int npcId) {
        if (registry == null) {
            return;
        }

        try {
            NPC npc = registry.getById(npcId);
            if (npc != null) {
                npc.destroy();
            }
        } catch (Exception e) {
            plugin.log("Error removing NPC " + npcId + ": " + e.getMessage());
        }
    }

    /**
     * Mark an NPC as killed and drop its inventory
     */
    public void markNPCKilled(int npcId, org.bukkit.Location deathLocation) {
        UUID playerId = npcIdToPlayerId.get(npcId);
        if (playerId != null) {
            HollowBody hollowBody = hollowBodies.get(playerId);
            if (hollowBody != null) {
                hollowBody.markKilled(deathLocation);

                // Drop the player's inventory at death location
                dropInventory(hollowBody, deathLocation);

                plugin.log("Hollow Body NPC " + npcId + " was killed (player: " + playerId + ") - items dropped");
            }
        }
    }

    /**
     * Drop a Hollow Body's stored inventory at a location
     */
    private void dropInventory(HollowBody hollowBody, org.bukkit.Location location) {
        if (location == null || location.getWorld() == null) {
            plugin.log("Cannot drop inventory - invalid location");
            return;
        }

        org.bukkit.World world = location.getWorld();
        int droppedItems = 0;

        // Drop main inventory items
        if (hollowBody.getInventory() != null) {
            for (org.bukkit.inventory.ItemStack item : hollowBody.getInventory()) {
                if (item != null && item.getType() != org.bukkit.Material.AIR) {
                    world.dropItemNaturally(location, item);
                    droppedItems++;
                }
            }
        }

        // Drop armor items
        if (hollowBody.getArmor() != null) {
            for (org.bukkit.inventory.ItemStack item : hollowBody.getArmor()) {
                if (item != null && item.getType() != org.bukkit.Material.AIR) {
                    world.dropItemNaturally(location, item);
                    droppedItems++;
                }
            }
        }

        plugin.log("Dropped " + droppedItems + " items from " + hollowBody.getPlayerName() + "'s Hollow Body");
    }

    /**
     * Get Hollow Body for a player
     */
    public HollowBody getHollowBody(UUID playerId) {
        return hollowBodies.get(playerId);
    }

    /**
     * Check if player has an active Hollow Body
     */
    public boolean hasHollowBody(UUID playerId) {
        return hollowBodies.containsKey(playerId);
    }

    /**
     * Clean up expired Hollow Bodies
     */
    public void cleanupExpired() {
        if (registry == null) {
            return;
        }

        hollowBodies.entrySet().removeIf(entry -> {
            HollowBody hollowBody = entry.getValue();
            if (hollowBody.shouldDespawn()) {
                removeNPC(hollowBody.getNpcId());
                npcIdToPlayerId.remove(hollowBody.getNpcId());
                plugin.log("Hollow Body for " + hollowBody.getPlayerName() + " despawned (timeout)");
                return true;
            }
            return false;
        });
    }

    /**
     * Despawn all remaining Hollow Body NPCs (called when event ends)
     */
    public void despawnAllHollowBodies() {
        if (registry == null) {
            return;
        }

        int despawnedCount = 0;
        for (HollowBody hollowBody : new java.util.ArrayList<>(hollowBodies.values())) {
            removeNPC(hollowBody.getNpcId());
            npcIdToPlayerId.remove(hollowBody.getNpcId());
            despawnedCount++;
        }

        hollowBodies.clear();
        plugin.log("Force-despawned " + despawnedCount + " Hollow Body NPCs due to event end");
    }

    /**
     * Check if Citizens is available
     */
    public boolean isAvailable() {
        return registry != null;
    }

}
