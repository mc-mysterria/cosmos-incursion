package net.mysterria.cosmos.domain.combat.listener;

import net.mysterria.cosmos.domain.combat.service.DeathHandler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Listens for player respawns and joins to restore items saved during death.
 */
public class PlayerRespawnListener implements Listener {

    private final DeathHandler deathHandler;

    public PlayerRespawnListener(DeathHandler deathHandler) {
        this.deathHandler = deathHandler;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        deathHandler.restoreSavedItems(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Backup restore in case they disconnected before respawning
        deathHandler.restoreSavedItems(event.getPlayer());
    }
}
