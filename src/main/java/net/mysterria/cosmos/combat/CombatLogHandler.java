package net.mysterria.cosmos.combat;

import net.citizensnpcs.api.event.NPCDeathEvent;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.integration.CitizensIntegration;
import net.mysterria.cosmos.player.KillTracker;
import net.mysterria.cosmos.player.PlayerStateManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Handles combat logging mechanics
 * - Spawns Hollow Body NPCs when players disconnect in zones
 * - Tracks NPC deaths for penalty application
 */
public class CombatLogHandler implements Listener {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;
    private final CitizensIntegration citizensIntegration;
    private final KillTracker killTracker;

    public CombatLogHandler(CosmosIncursion plugin, PlayerStateManager playerStateManager,
                            CitizensIntegration citizensIntegration, KillTracker killTracker) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        this.citizensIntegration = citizensIntegration;
        this.killTracker = killTracker;
    }

    /**
     * Handle player disconnecting while in zone
     * @return true if Hollow Body was spawned, false otherwise
     */
    public boolean handleDisconnect(Player player) {
        // Only spawn NPC if player is in a zone
        if (!playerStateManager.isInZone(player)) {
            return false;
        }

        // Check if Citizens integration is available
        if (!citizensIntegration.isAvailable()) {
            plugin.log("Cannot spawn Hollow Body - Citizens not available");
            return false;
        }

        // Spawn Hollow Body NPC
        HollowBody hollowBody = citizensIntegration.createHollowBody(player, player.getLocation());

        if (hollowBody != null) {
            plugin.log("Player " + player.getName() + " disconnected in zone - Hollow Body spawned");
            return true;
        }

        return false;
    }

    /**
     * Listen for NPC deaths (Citizens API)
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onNPCDeath(NPCDeathEvent event) {
        int npcId = event.getNPC().getId();

        // Check if this is a Hollow Body NPC
        if (!citizensIntegration.isAvailable()) {
            return;
        }

        // Mark as killed
        citizensIntegration.markNPCKilled(npcId);
    }

    /**
     * Handle player reconnecting
     * Check if their Hollow Body was killed and apply penalty
     */
    public void handleReconnect(Player player) {
        if (!citizensIntegration.isAvailable()) {
            return;
        }

        HollowBody hollowBody = citizensIntegration.getHollowBody(player.getUniqueId());

        if (hollowBody != null) {
            if (hollowBody.isWasKilled()) {
                plugin.log("Player " + player.getName() + " reconnected - Hollow Body was killed, applying penalty");

                // Use DeathHandler to apply penalty
                DeathHandler deathHandler = new DeathHandler(plugin, playerStateManager, killTracker);
                deathHandler.handleZoneDeath(player, null, hollowBody.getSpawnLocation());
            } else {
                plugin.log("Player " + player.getName() + " reconnected - Hollow Body survived");
            }

            // Remove the Hollow Body
            citizensIntegration.removeHollowBody(player.getUniqueId());
        }
    }

}
