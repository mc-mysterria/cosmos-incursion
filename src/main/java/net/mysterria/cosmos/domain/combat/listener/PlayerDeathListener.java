package net.mysterria.cosmos.domain.combat.listener;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.service.DeathHandler;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.combat.service.KillTracker;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import net.mysterria.cosmos.domain.incursion.model.PlayerZoneState;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Listens for player deaths in Incursion zones
 * Delegates to DeathHandler for processing and KillTracker for griefing detection
 */
public class PlayerDeathListener implements Listener {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;
    private final KillTracker killTracker;
    private final DeathHandler deathHandler;

    public PlayerDeathListener(CosmosIncursion plugin, PlayerStateManager playerStateManager,
                               KillTracker killTracker, DeathHandler deathHandler) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        this.killTracker = killTracker;
        this.deathHandler = deathHandler;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
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

        // Resolve zone tier to determine which items to drop
        ZoneTier tier = ZoneTier.DEATH; // default to harshest behavior if zone state is missing
        PlayerZoneState zoneState = playerStateManager.getState(victim);
        if (zoneState != null && zoneState.getIncursionZone() != null) {
            tier = zoneState.getIncursionZone().getTier();
        }
        double dropChance = plugin.getConfigLoader().getConfig().getTierConfigs().get(tier).dropChance();

        // IMPORTANT: Clear event drops FIRST to prevent any default drops.
        // This prevents graves plugins from creating graves and avoids item duplication.
        Location deathLocation = victim.getLocation();
        event.getDrops().clear();

        // Manually drop items according to tier drop chance, then clear inventory
        ItemStack[] keptItems = dropItemsWithChance(victim, deathLocation, dropChance);
        deathHandler.storeSavedItems(victim.getUniqueId(), keptItems);

        // Process death penalties (regression logic — only applies in DEATH tier)
        deathHandler.handleZoneDeath(victim, killer, deathLocation, tier);

        // Drop any permanent zone carry buffer as items
        PermanentZone pZone = plugin.getPermanentZoneManager().getZoneAt(deathLocation);
        if (pZone == null) {
            // Player might be in permanent zone but not incursion zone, check separately
            pZone = plugin.getPermanentZoneManager().getPlayerZone(victim.getUniqueId());
        }
        if (pZone != null) {
            dropBufferAsItems(victim, deathLocation);
            plugin.getPermanentZoneManager().clearBuffer(victim.getUniqueId());
        }
    }

    /** Called when player dies outside an incursion zone but inside a permanent zone. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDeathInPermanentZone(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim.hasMetadata("NPC")) return;
        // Already handled above if they were also in an incursion zone
        if (playerStateManager.isInZone(victim)) return;

        PermanentZone pZone = plugin.getPermanentZoneManager().getPlayerZone(victim.getUniqueId());
        if (pZone == null) return;

        // Apply inventory item drops based on permanent zone tier
        ExclusionZoneTier tier = pZone.getTier();
        double dropChance = plugin.getConfigLoader().getConfig().getExclusionTierConfigs()
                .getOrDefault(tier, plugin.getConfigLoader().getConfig().getExclusionTierConfigs().get(ExclusionZoneTier.MEDIUM))
                .dropChance();
        Location deathLocation = victim.getLocation();
        event.getDrops().clear();
        ItemStack[] keptItems = dropItemsWithChance(victim, deathLocation, dropChance);
        deathHandler.storeSavedItems(victim.getUniqueId(), keptItems);

        dropBufferAsItems(victim, deathLocation);
        plugin.getPermanentZoneManager().clearBuffer(victim.getUniqueId());
    }

    private void dropBufferAsItems(Player victim, Location loc) {
        PlayerResourceBuffer buffer = plugin.getPermanentZoneManager().getBuffer(victim.getUniqueId());
        if (buffer.isEmpty()) return;
        for (ResourceType type : ResourceType.values()) {
            double amount = buffer.get(type);
            if (amount < 1.0) continue;
            int count = (int) amount;
            if (loc.getWorld() != null) {
                org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(type.getDefaultMaterial(), Math.min(count, 64));
                loc.getWorld().dropItemNaturally(loc, stack);
            }
        }
    }

    /**
     * Drops items from the victim's inventory according to dropChance, clears the physical inventory,
     * and returns the items that were NOT dropped.
     *
     * @return Array of items to be restored on respawn
     */
    private ItemStack[] dropItemsWithChance(Player victim, Location loc, double dropChance) {
        var rng = ThreadLocalRandom.current();
        var inv = victim.getInventory();
        ItemStack[] contents = inv.getContents(); // Includes main, armor, and offhand

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() == Material.AIR) continue;

            if (dropChance >= 1.0 || (dropChance > 0.0 && rng.nextDouble() < dropChance)) {
                if (loc.getWorld() != null) {
                    loc.getWorld().dropItemNaturally(loc, item.clone());
                }
                contents[i] = null; // Mark as dropped, will not be restored
            }
        }

        // Always clear physical inventory to prevent other plugins (like Graves) from seeing items
        inv.clear();
        return contents;
    }
}
