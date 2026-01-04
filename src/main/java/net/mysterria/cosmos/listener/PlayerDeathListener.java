package net.mysterria.cosmos.listener;

import net.mysterria.cosmos.domain.combat.DeathHandler;
import net.mysterria.cosmos.domain.player.KillTracker;
import net.mysterria.cosmos.domain.player.PlayerStateManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listens for player deaths in Incursion zones
 * Delegates to DeathHandler for processing and KillTracker for griefing detection
 */
public class PlayerDeathListener implements Listener {

    private final PlayerStateManager playerStateManager;
    private final KillTracker killTracker;
    private final DeathHandler deathHandler;

    public PlayerDeathListener(PlayerStateManager playerStateManager, KillTracker killTracker, DeathHandler deathHandler) {
        this.playerStateManager = playerStateManager;
        this.killTracker = killTracker;
        this.deathHandler = deathHandler;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // Only process deaths in incursion zones
        if (!playerStateManager.isInZone(victim)) {
            return;
        }

        // Get killer (nullable for environmental deaths)
        Player killer = victim.getKiller();

        // Record kill for griefing detection (only if there is a killer)
        if (killer != null && !killer.equals(victim)) {
            killTracker.recordKill(killer, victim);
        }

        // IMPORTANT: Drop all items manually before death completes
        // This prevents graves plugin from creating a grave and avoids item duplication
        Location deathLocation = victim.getLocation();

        // Drop all inventory items
        for (ItemStack item : victim.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                deathLocation.getWorld().dropItemNaturally(deathLocation, item);
            }
        }

        // Drop all armor items
        for (ItemStack item : victim.getInventory().getArmorContents()) {
            if (item != null && item.getType() != Material.AIR) {
                deathLocation.getWorld().dropItemNaturally(deathLocation, item);
            }
        }

        // Drop off-hand item
        ItemStack offHand = victim.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR) {
            deathLocation.getWorld().dropItemNaturally(deathLocation, offHand);
        }

        // Clear all drops from the event (prevents graves plugin from seeing items)
        event.getDrops().clear();

        // Clear player's inventory
        victim.getInventory().clear();
        victim.getInventory().setArmorContents(null);
        victim.getInventory().setItemInOffHand(null);

        // Process death (sequence regression, char drop, rewards)
        deathHandler.handleZoneDeath(victim, killer, deathLocation);
    }

}
