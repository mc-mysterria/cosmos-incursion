package net.mysterria.cosmos.domain.incursion.listener;

import dev.geco.gsit.api.GSitAPI;
import dev.geco.gsit.api.event.PreEntitySitEvent;
import dev.geco.gsit.api.event.PrePlayerPlayerSitEvent;
import dev.geco.gsit.model.StopReason;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class GSitZoneListener implements Listener {

    private static final Component DENIED_MSG = Component.text("You cannot sit in a PvP zone!", NamedTextColor.RED);
    private static final Component DISMOUNTED_MSG = Component.text("You were dismounted — PvP zone entered!", NamedTextColor.RED);

    private final PlayerStateManager playerStateManager;
    private final PermanentZoneManager permanentZoneManager;

    public GSitZoneListener(PlayerStateManager playerStateManager, PermanentZoneManager permanentZoneManager) {
        this.playerStateManager = playerStateManager;
        this.permanentZoneManager = permanentZoneManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySit(PreEntitySitEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        if (isInPvpZone(player)) {
            event.setCancelled(true);
            player.sendActionBar(DENIED_MSG);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityRide(PrePlayerPlayerSitEvent event) {
        Player rider = event.getPlayer();
        Player target = event.getTarget();

        if (isInPvpZone(rider)) {
            event.setCancelled(true);
            rider.sendActionBar(Component.text("You cannot ride players in a PvP zone!", NamedTextColor.RED));
            return;
        }
        if (isInPvpZone(target)) {
            event.setCancelled(true);
            rider.sendActionBar(Component.text("Cannot ride players in a PvP zone!", NamedTextColor.RED));
        }
    }

    /**
     * Force-unsit/unride a player who just entered a PvP zone.
     */
    public void dismountIfSitting(Player player) {
        GSitAPI.stopPlayerSit(player, StopReason.PLUGIN);
        player.sendActionBar(DISMOUNTED_MSG);
    }

    private boolean isInPvpZone(Player player) {
        return playerStateManager.isInZone(player) || permanentZoneManager.getPlayerZone(player.getUniqueId()) != null;
    }
}
