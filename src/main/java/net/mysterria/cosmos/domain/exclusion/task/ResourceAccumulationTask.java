package net.mysterria.cosmos.domain.exclusion.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.PointOfInterest;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
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
            if (zone == null) {
                permanentZoneManager.updatePoIStay(player.getUniqueId(), null);
                continue;
            }

            List<PointOfInterest> pois = permanentZoneManager.getActivePoIs(zone);
            PointOfInterest activePoi = null;
            for (PointOfInterest poi : pois) {
                if (!poi.isActive()) continue;
                if (poi.isPlayerInRange(player.getLocation())) {
                    activePoi = poi;
                    break;
                }
            }

            permanentZoneManager.updatePoIStay(player.getUniqueId(), activePoi != null ? activePoi.getId() : null);

            if (activePoi != null) {
                double rate = calculateRate(player);
                double actual = activePoi.consumeResource(rate);
                if (actual <= 0) continue; // depleted mid-tick, skip
                PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
                buffer.add(activePoi.getResourceType(), actual);
                sendBufferActionBar(player, buffer);
            }
        }
    }

    /**
     * Calculates the per-second extraction rate based on base config and stay duration.
     */
    private double calculateRate(Player player) {
        var config = plugin.getConfigLoader().getConfig();
        double baseAmount = config.getPermanentZonePoiBaseAmount();
        int baseInterval = Math.max(1, config.getPermanentZonePoiBaseInterval());
        
        double baseRate = baseAmount / baseInterval;
        
        long stayMillis = permanentZoneManager.getPoIStayMillis(player.getUniqueId());
        int staySeconds = (int) (stayMillis / 1000);
        
        double bonusAmount = config.getPermanentZonePoiBonusAmount();
        int bonusInterval = config.getPermanentZonePoiBonusInterval();
        
        double totalRate = baseRate;
        if (bonusInterval > 0 && staySeconds >= bonusInterval) {
            int tiers = staySeconds / bonusInterval;
            double bonusRate = (bonusAmount * tiers) / baseInterval;
            totalRate += bonusRate;
        }
        
        return totalRate;
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
