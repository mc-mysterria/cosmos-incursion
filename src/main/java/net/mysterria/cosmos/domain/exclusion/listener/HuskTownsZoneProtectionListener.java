package net.mysterria.cosmos.domain.exclusion.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.william278.husktowns.events.ClaimEvent;
import net.william278.husktowns.events.TownCreateEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Prevents players from claiming chunks or founding a town inside a permanent extraction zone
 * when HuskTowns is the active towns plugin.
 */
public class HuskTownsZoneProtectionListener implements Listener {

    private static final Component CLAIM_DENIED = Component.text(
            "[Cosmos] You cannot claim land inside a permanent extraction zone.", NamedTextColor.RED);
    private static final Component CREATE_DENIED = Component.text(
            "[Cosmos] You cannot found a town inside a permanent extraction zone.", NamedTextColor.RED);

    private final PermanentZoneManager permanentZoneManager;

    public HuskTownsZoneProtectionListener(PermanentZoneManager permanentZoneManager) {
        this.permanentZoneManager = permanentZoneManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClaim(ClaimEvent event) {
        World world = event.getPlayer().getWorld();
        int chunkX = event.getTownClaim().claim().getChunk().getX();
        int chunkZ = event.getTownClaim().claim().getChunk().getZ();
        Location center = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);

        if (permanentZoneManager.isInsideAnyZone(center)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(CLAIM_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTownCreate(TownCreateEvent event) {
        Location location = event.getPlayer().getLocation();

        if (permanentZoneManager.isInsideAnyZone(location)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(CREATE_DENIED);
        }
    }
}
