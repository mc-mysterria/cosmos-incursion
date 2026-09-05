package net.mysterria.cosmos.domain.combat.service;

import net.citizensnpcs.api.event.NPCDeathEvent;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.model.HollowBody;
import net.mysterria.cosmos.toolkit.CitizensToolkit;
import net.mysterria.cosmos.toolkit.InventoryUtils;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.PlayerInventory;

/**
 * Handles combat logging mechanics
 * - Spawns Hollow Body NPCs when players disconnect in zones
 * - Tracks NPC deaths for penalty application
 */
public class CombatLogHandler implements Listener {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;
    private final CitizensToolkit citizensToolkit;
    private final KillTracker killTracker;

    public CombatLogHandler(CosmosIncursion plugin, PlayerStateManager playerStateManager,
                            CitizensToolkit citizensToolkit, KillTracker killTracker) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        this.citizensToolkit = citizensToolkit;
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
        if (!citizensToolkit.isAvailable()) {
            plugin.log("Cannot spawn Hollow Body - Citizens not available");
            return false;
        }

        // Spawn Hollow Body NPC (transfers inventory off the player)
        HollowBody hollowBody = citizensToolkit.createHollowBody(player, player.getLocation());

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
        if (!citizensToolkit.isAvailable()) {
            return;
        }

        // Get the death location
        org.bukkit.Location deathLocation = event.getNPC().getStoredLocation();

        // Mark as killed and handle item drops
        citizensToolkit.markNPCKilled(npcId, deathLocation);
    }

    /**
     * Handle player reconnecting
     * Check if their Hollow Body was killed and apply penalty, otherwise restore transferred items once.
     */
    public void handleReconnect(Player player) {
        if (!citizensToolkit.isAvailable()) {
            return;
        }

        HollowBody hollowBody = citizensToolkit.getHollowBody(player.getUniqueId());
        if (hollowBody == null) {
            return;
        }

        // Defer by 1 tick so a same-tick NPCDeathEvent is observed before we decide restore vs penalty
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            HollowBody pending = citizensToolkit.getHollowBody(player.getUniqueId());
            if (pending == null) {
                return;
            }
            applyReconnectOutcome(player, pending);
        }, 1L);
    }

    private void applyReconnectOutcome(Player player, HollowBody hollowBody) {
        if (hollowBody.isWasKilled()) {
            plugin.log("Player " + player.getName() + " reconnected - Hollow Body was killed, applying full penalty");

            // Inventory was transferred at disconnect and dropped on hollow death — keep player empty
            InventoryUtils.clearPlayerInventory(player);
            player.saveData();

            // Teleport player to death location
            if (hollowBody.getDeathLocation() != null) {
                player.teleport(hollowBody.getDeathLocation());
            }

            // Kill the player to apply death mechanics and sequence regression
            if (player.isOnline()) {
                player.setHealth(0);
                plugin.log("Player " + player.getName() + " killed due to Hollow Body death");
            }
        } else {
            plugin.log("Player " + player.getName() + " reconnected - Hollow Body survived, restoring inventory");
            restoreTransferredInventory(player, hollowBody);
            player.saveData();
        }

        // Remove the Hollow Body / pending outcome (items already dropped or restored)
        citizensToolkit.removeHollowBody(player.getUniqueId());
    }

    private static void restoreTransferredInventory(Player player, HollowBody hollowBody) {
        if (hollowBody.isItemsDropped()) {
            InventoryUtils.clearPlayerInventory(player);
            return;
        }

        PlayerInventory inv = player.getInventory();
        InventoryUtils.clearPlayerInventory(player);

        if (hollowBody.getInventory() != null) {
            inv.setStorageContents(hollowBody.getInventory());
        }
        if (hollowBody.getArmor() != null) {
            inv.setArmorContents(hollowBody.getArmor());
        }
        if (hollowBody.getOffhand() != null) {
            inv.setItemInOffHand(hollowBody.getOffhand());
        }

        hollowBody.clearStoredItems();
    }

}
