package net.mysterria.cosmos.listener;

import net.mysterria.cosmos.combat.CombatLogHandler;
import net.mysterria.cosmos.reward.BuffManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Listens for player joins to:
 * - Check for combat log penalties
 * - Reapply territory reward buffs
 */
public class PlayerJoinListener implements Listener {

    private final CombatLogHandler combatLogHandler;
    private final BuffManager buffManager;

    public PlayerJoinListener(CombatLogHandler combatLogHandler, BuffManager buffManager) {
        this.combatLogHandler = combatLogHandler;
        this.buffManager = buffManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Check if player has a Hollow Body that was killed
        combatLogHandler.handleReconnect(player);

        // Reapply territory reward buff if applicable
        buffManager.handlePlayerJoin(player);
    }

}
