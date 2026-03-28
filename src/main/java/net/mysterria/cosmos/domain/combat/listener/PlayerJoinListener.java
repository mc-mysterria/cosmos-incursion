package net.mysterria.cosmos.domain.combat.listener;

import net.mysterria.cosmos.domain.combat.service.CombatLogHandler;
import net.mysterria.cosmos.toolkit.BuffToolkit;
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
    private final BuffToolkit buffToolkit;

    public PlayerJoinListener(CombatLogHandler combatLogHandler, BuffToolkit buffToolkit) {
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
    }

}
