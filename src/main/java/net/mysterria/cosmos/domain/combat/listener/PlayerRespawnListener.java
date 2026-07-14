package net.mysterria.cosmos.domain.combat.listener;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.service.DeathHandler;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Listens for player respawns and joins to restore items saved during death,
 * and prevents players from respawning inside a PvP zone (bed/anchor set inside a
 * zone would otherwise let them bypass the zone entry cooldown on every death).
 */
public class PlayerRespawnListener implements Listener {

    private final CosmosIncursion plugin;
    private final DeathHandler deathHandler;

    public PlayerRespawnListener(CosmosIncursion plugin, DeathHandler deathHandler) {
        this.plugin = plugin;
        this.deathHandler = deathHandler;
    }

    /**
     * Runs late enough to override respawn locations set by other plugins (beds, anchors,
     * warp-on-death plugins, etc.) before the location is actually used.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawnZoneCheck(PlayerRespawnEvent event) {
        Location respawnLocation = event.getRespawnLocation();
        if (respawnLocation.getWorld() == null) return;

        IncursionZone incursionZone = plugin.getZoneManager().getZoneAt(respawnLocation);
        if (incursionZone != null) {
            Location safe = plugin.getZoneManager().findSafeLocationOutsideZone(respawnLocation, incursionZone);
            if (safe != null) {
                event.setRespawnLocation(safe);
            }
            return;
        }

        PermanentZone permanentZone = plugin.getPermanentZoneManager().getZoneAt(respawnLocation);
        if (permanentZone != null) {
            double exitBuffer = plugin.getConfigLoader().getConfig().getPermanentZoneExtractionExitBuffer();
            Location safe = plugin.getPermanentZoneManager().findExitPoint(permanentZone, respawnLocation, exitBuffer);
            if (safe != null) {
                event.setRespawnLocation(safe);
            }
        }
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
