package net.mysterria.cosmos.listener;

import net.mysterria.cosmos.combat.CombatLogHandler;
import net.mysterria.cosmos.reward.BuffManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for player disconnects to:
 * - Spawn Hollow Body NPCs for combat logging
 * - Clean up buff tracking
 */
public class PlayerQuitListener implements Listener {

    private final CombatLogHandler combatLogHandler;
    private final BuffManager buffManager;

    public PlayerQuitListener(CombatLogHandler combatLogHandler, BuffManager buffManager) {
        this.combatLogHandler = combatLogHandler;
        this.buffManager = buffManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Handle potential combat logging
        combatLogHandler.handleDisconnect(player);

        // Clean up buff tracking
        buffManager.handlePlayerQuit(player);
    }

}
