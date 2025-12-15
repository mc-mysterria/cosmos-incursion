package net.mysterria.cosmos.listener;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.combat.DeathHandler;
import net.mysterria.cosmos.player.KillTracker;
import net.mysterria.cosmos.player.PlayerStateManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Listens for player deaths in Incursion zones
 * Delegates to DeathHandler for processing and KillTracker for griefing detection
 */
public class PlayerDeathListener implements Listener {

    private final PlayerStateManager playerStateManager;
    private final KillTracker killTracker;
    private final DeathHandler deathHandler;

    public PlayerDeathListener(CosmosIncursion plugin, PlayerStateManager playerStateManager,
                               KillTracker killTracker) {
        this.playerStateManager = playerStateManager;
        this.killTracker = killTracker;
        this.deathHandler = new DeathHandler(plugin, playerStateManager, killTracker);
    }

    @EventHandler(priority = EventPriority.NORMAL)
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

        // Process death
        deathHandler.handleZoneDeath(victim, killer, victim.getLocation());
    }

}
