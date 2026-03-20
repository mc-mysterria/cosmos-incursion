package net.mysterria.cosmos.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.permanent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * Runs every 20 ticks (1 second). Checks if players inside permanent zones are
 * standing near an active PoI and credits their resource buffer.
 */
public class ResourceAccumulationTask extends BukkitRunnable {

    private final CosmosIncursion plugin;
    private final PermanentZoneManager permanentZoneManager;

    public ResourceAccumulationTask(CosmosIncursion plugin, PermanentZoneManager permanentZoneManager) {
        this.plugin = plugin;
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasMetadata("NPC")) continue;

            PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
            if (zone == null) continue;

            List<PointOfInterest> pois = permanentZoneManager.getActivePoIs(zone);
            for (PointOfInterest poi : pois) {
                if (!poi.isActive()) continue;
                if (poi.isPlayerInRange(player.getLocation())) {
                    double rate = getRate(poi.getResourceType());
                    PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
                    buffer.add(poi.getResourceType(), rate);
                    sendBufferActionBar(player, buffer);
                    break; // Only one PoI per tick
                }
            }
        }
    }

    private double getRate(ResourceType type) {
        // Rates are defined in config; defaults used here
        return switch (type) {
            case GOLD -> 1.0;
            case SILVER -> 2.0;
            case GEMS -> 0.5;
        };
    }

    private void sendBufferActionBar(Player player, PlayerResourceBuffer buffer) {
        Component msg = Component.text("Carrying: ", NamedTextColor.YELLOW)
            .append(Component.text("Gold: " + format(buffer.get(ResourceType.GOLD)), NamedTextColor.GOLD))
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
            .append(Component.text("Silver: " + format(buffer.get(ResourceType.SILVER)), NamedTextColor.GRAY))
            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
            .append(Component.text("Gems: " + format(buffer.get(ResourceType.GEMS)), NamedTextColor.GREEN));
        player.sendActionBar(msg);
    }

    private String format(double value) {
        return String.format("%.1f", value);
    }
}
