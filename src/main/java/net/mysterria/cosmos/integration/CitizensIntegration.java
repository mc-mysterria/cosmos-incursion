package net.mysterria.cosmos.integration;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.combat.HollowBody;
import net.mysterria.cosmos.config.CosmosConfig;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

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
            plugin.log("Citizens plugin not found - Hollow Body NPCs disabled");
            return false;
        }

        try {
            this.registry = CitizensAPI.getNamedNPCRegistry("CosmosIncursion");
            if (this.registry == null) {
                this.registry = CitizensAPI.createNamedNPCRegistry("CosmosIncursion", null);
            }
            plugin.log("Citizens integration enabled - Hollow Body NPCs active");
            return true;
        } catch (Exception e) {
            plugin.log("Failed to initialize Citizens integration: " + e.getMessage());
            e.printStackTrace();
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
            // Create NPC name from config
            String npcName = config.getNpcNameFormat().replace("%player%", player.getName());

            // Create NPC
            NPC npc = registry.createNPC(EntityType.PLAYER, npcName);
            npc.spawn(location);

            // Copy player appearance
            npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);
            npc.data().setPersistent(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, false);

            // Set skin to match player
//            npc.getOrAddTrait(SkinTrait.class).setSkinName(player.getName());

            // Calculate duration
            long durationMillis = config.getNpcDurationMinutes() * 60_000L;

            // Create HollowBody wrapper
            HollowBody hollowBody = new HollowBody(
                    player.getUniqueId(),
                    player.getName(),
                    npc.getId(),
                    location,
                    durationMillis
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
     * Mark an NPC as killed
     */
    public void markNPCKilled(int npcId) {
        UUID playerId = npcIdToPlayerId.get(npcId);
        if (playerId != null) {
            HollowBody hollowBody = hollowBodies.get(playerId);
            if (hollowBody != null) {
                hollowBody.markKilled();
                plugin.log("Hollow Body NPC " + npcId + " was killed (player: " + playerId + ")");
            }
        }
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
     * Check if Citizens is available
     */
    public boolean isAvailable() {
        return registry != null;
    }

}
