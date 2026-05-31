package net.mysterria.cosmos.domain.combat.listener;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.service.CombatLogHandler;
import net.mysterria.cosmos.toolkit.BuffToolkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Listens for player joins to:
 * - Check for combat log penalties
 * - Reapply territory reward buffs
 * - Clean up cosmos items if player joins outside the zone
 */
public class PlayerJoinListener implements Listener {

    private final CosmosIncursion plugin;
    private final CombatLogHandler combatLogHandler;
    private final BuffToolkit buffToolkit;

    public PlayerJoinListener(CosmosIncursion plugin, CombatLogHandler combatLogHandler, BuffToolkit buffToolkit) {
        this.plugin = plugin;
        this.combatLogHandler = combatLogHandler;
        this.buffToolkit = buffToolkit;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Check if player has a Hollow Body that was killed
        combatLogHandler.handleReconnect(player);

        // Reapply territory reward buff if applicable
        buffToolkit.handlePlayerJoin(player);

        // Clean up cosmos items if player joins outside the zone
        if (!plugin.getPermanentZoneManager().isInsideAnyZone(player.getLocation())) {
            ItemStack[] contents = player.getInventory().getContents();
            boolean changed = false;
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                if (item != null && item.hasItemMeta()) {
                    var pdc = item.getItemMeta().getPersistentDataContainer();
                    if (pdc.has(plugin.getKey("cosmos_zone_compass"), PersistentDataType.BOOLEAN)
                            || pdc.has(plugin.getKey("cosmos_incursion_saddle"), PersistentDataType.BOOLEAN)) {
                        player.getInventory().setItem(i, null);
                        changed = true;
                    }
                }
            }
            if (changed) {
                plugin.log("Cleaned up cosmos items from " + player.getName() + "'s inventory on join outside zone");
            }
        }
    }

}
