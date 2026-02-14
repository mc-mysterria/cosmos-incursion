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

        // Skip Citizens NPCs (they have their own death handling via NPCDeathEvent)
        if (victim.hasMetadata("NPC")) {
            return;
        }

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

        // Clear event drops FIRST to prevent any default drops
        // This is critical - we must clear before manually dropping to avoid duplication
        event.getDrops().clear();

        // Now manually drop all items explicitly by slot
        // Drop hotbar (slots 0-8)
        for (int i = 0; i < 9; i++) {
            ItemStack item = victim.getInventory().getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                deathLocation.getWorld().dropItemNaturally(deathLocation, item.clone());
            }
        }

        // Drop main inventory (slots 9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack item = victim.getInventory().getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                deathLocation.getWorld().dropItemNaturally(deathLocation, item.clone());
            }
        }

        // Drop armor (helmet, chestplate, leggings, boots)
        ItemStack helmet = victim.getInventory().getHelmet();
        if (helmet != null && helmet.getType() != Material.AIR) {
            deathLocation.getWorld().dropItemNaturally(deathLocation, helmet.clone());
        }

        ItemStack chestplate = victim.getInventory().getChestplate();
        if (chestplate != null && chestplate.getType() != Material.AIR) {
            deathLocation.getWorld().dropItemNaturally(deathLocation, chestplate.clone());
        }

        ItemStack leggings = victim.getInventory().getLeggings();
        if (leggings != null && leggings.getType() != Material.AIR) {
            deathLocation.getWorld().dropItemNaturally(deathLocation, leggings.clone());
        }

        ItemStack boots = victim.getInventory().getBoots();
        if (boots != null && boots.getType() != Material.AIR) {
            deathLocation.getWorld().dropItemNaturally(deathLocation, boots.clone());
        }

        // Drop off-hand item
        ItemStack offHand = victim.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() != Material.AIR) {
            deathLocation.getWorld().dropItemNaturally(deathLocation, offHand.clone());
        }

        // Clear player's inventory completely
        victim.getInventory().clear();
        victim.getInventory().setHelmet(null);
        victim.getInventory().setChestplate(null);
        victim.getInventory().setLeggings(null);
        victim.getInventory().setBoots(null);
        victim.getInventory().setItemInOffHand(null);

        // Process death (sequence regression, char drop, rewards)
        deathHandler.handleZoneDeath(victim, killer, deathLocation);
    }

}
