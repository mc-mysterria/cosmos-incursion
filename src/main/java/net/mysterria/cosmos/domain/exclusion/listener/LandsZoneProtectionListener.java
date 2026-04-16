package net.mysterria.cosmos.domain.exclusion.listener;

import me.angeschossen.lands.api.events.ChunkPreClaimEvent;
import me.angeschossen.lands.api.events.land.create.LandPreCreateEvent;
import me.angeschossen.lands.api.player.LandPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Prevents players from claiming chunks or creating a land inside a permanent extraction zone
 * when Lands is the active towns plugin.
 */
public class LandsZoneProtectionListener implements Listener {

    private static final Component CLAIM_DENIED = Component.text(
            "[Cosmos] You cannot claim land inside a permanent extraction zone.", NamedTextColor.RED);
    private static final Component CREATE_DENIED = Component.text(
            "[Cosmos] You cannot create a land inside a permanent extraction zone.", NamedTextColor.RED);

    private final PermanentZoneManager permanentZoneManager;

    public LandsZoneProtectionListener(PermanentZoneManager permanentZoneManager) {
        this.permanentZoneManager = permanentZoneManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChunkClaim(ChunkPreClaimEvent event) {
        World world = Bukkit.getWorld(event.getWorldName());
        if (world == null) return;

        Location center = new Location(world, (event.getX() << 4) + 8, 64, (event.getZ() << 4) + 8);

        if (permanentZoneManager.isInsideAnyZone(center)) {
            event.setCancelled(true);
            Player player = getPlayer(event.getLandPlayer());
            if (player != null) player.sendMessage(CLAIM_DENIED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLandCreate(LandPreCreateEvent event) {
        Player player = getPlayer(event.getLandPlayer());
        if (player == null) return;

        if (permanentZoneManager.isInsideAnyZone(player.getLocation())) {
            event.setCancelled(true);
            player.sendMessage(CREATE_DENIED);
        }
    }

    private Player getPlayer(LandPlayer landPlayer) {
        if (landPlayer == null) return null;
        return landPlayer.getPlayer();
    }
}
