package net.mysterria.cosmos.domain.combat.listener;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.beacon.service.BeaconUIManager;
import net.mysterria.cosmos.domain.combat.service.CombatLogHandler;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.toolkit.BuffToolkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listens for player disconnects to:
 * - Spawn Hollow Body NPCs for combat logging
 * - Clean up buff tracking
 */
public class PlayerQuitListener implements Listener {

    private final CosmosIncursion plugin;
    private final CombatLogHandler combatLogHandler;
    private final BuffToolkit buffToolkit;
    private final BeaconUIManager beaconUIManager;

    public PlayerQuitListener(CosmosIncursion plugin, CombatLogHandler combatLogHandler,
                              BuffToolkit buffToolkit,
                              BeaconUIManager beaconUIManager) {
        this.plugin = plugin;
        this.combatLogHandler = combatLogHandler;
        this.buffToolkit = buffToolkit;
        this.beaconUIManager = beaconUIManager;
    }

    private void dropPermanentZoneBuffer(Player player) {
        PermanentZone pZone = plugin.getPermanentZoneManager().getPlayerZone(player.getUniqueId());
        if (pZone == null) return;
        PlayerResourceBuffer buffer = plugin.getPermanentZoneManager().getBuffer(player.getUniqueId());
        if (buffer.isEmpty()) return;
        Location loc = player.getLocation();
        for (ResourceType type : ResourceType.values()) {
            double amount = buffer.get(type);
            if (amount < 1.0) continue;
            int count = Math.min((int) amount, 64);
            if (loc.getWorld() != null) {
                loc.getWorld().dropItemNaturally(loc, new ItemStack(type.getDefaultMaterial(), count));
            }
        }
        plugin.getPermanentZoneManager().clearBuffer(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Handle potential combat logging
        combatLogHandler.handleDisconnect(player);

        // Drop permanent zone resource buffer at disconnect location
        dropPermanentZoneBuffer(player);

        // Clean up buff tracking
        buffToolkit.handlePlayerQuit(player);

        // Clean up beacon UI
        beaconUIManager.handlePlayerQuit(player);

        // Clean up permanent zone tracking
        plugin.getPermanentZoneManager().updatePlayerZone(player.getUniqueId(), null);
    }

}
