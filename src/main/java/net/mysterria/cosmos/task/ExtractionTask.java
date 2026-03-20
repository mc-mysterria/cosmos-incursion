package net.mysterria.cosmos.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.permanent.*;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import net.william278.husktowns.town.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs every 20 ticks (1 second). For each player near an active extraction point,
 * drains their resource buffer and deposits it into the town balance.
 */
public class ExtractionTask extends BukkitRunnable {

    private final CosmosIncursion plugin;
    private final PermanentZoneManager permanentZoneManager;

    public ExtractionTask(CosmosIncursion plugin, PermanentZoneManager permanentZoneManager) {
        this.plugin = plugin;
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        double ratePerSecond = plugin.getConfigManager().getConfig().getPermanentZoneExtractionRatePerSecond();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasMetadata("NPC")) continue;

            PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
            if (zone == null) continue;

            PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
            if (buffer.isEmpty()) continue;

            List<ExtractionPoint> eps = permanentZoneManager.getActiveExtractionPoints(zone);
            for (ExtractionPoint ep : eps) {
                if (!ep.isActive()) continue;
                if (ep.isPlayerInRange(player.getLocation())) {
                    Map<ResourceType, Double> drained = buffer.drain(ratePerSecond);
                    if (!drained.isEmpty()) {
                        depositToTown(player, drained);
                        notifyExtracting(player, drained);
                    }
                    break;
                }
            }
        }
    }

    private void depositToTown(Player player, Map<ResourceType, Double> amounts) {
        Optional<Town> townOpt = TownsToolkit.getPlayerTown(player);
        if (townOpt.isEmpty()) return;
        int townId = townOpt.get().getId();
        permanentZoneManager.depositToTown(townId, amounts);
    }

    private void notifyExtracting(Player player, Map<ResourceType, Double> drained) {
        Component msg = Component.text("Depositing: ", NamedTextColor.GREEN);
        boolean first = true;
        for (Map.Entry<ResourceType, Double> entry : drained.entrySet()) {
            if (!first) msg = msg.append(Component.text(" | ", NamedTextColor.DARK_GRAY));
            msg = msg.append(Component.text(
                entry.getKey().name() + " +" + String.format("%.1f", entry.getValue()),
                resourceColor(entry.getKey())
            ));
            first = false;
        }
        player.sendActionBar(msg);
    }

    private NamedTextColor resourceColor(ResourceType type) {
        return switch (type) {
            case GOLD -> NamedTextColor.GOLD;
            case SILVER -> NamedTextColor.GRAY;
            case GEMS -> NamedTextColor.GREEN;
        };
    }
}
