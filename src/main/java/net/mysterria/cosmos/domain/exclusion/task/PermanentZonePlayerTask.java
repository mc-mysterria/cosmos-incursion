package net.mysterria.cosmos.domain.exclusion.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runs every 5 ticks to detect player entry/exit into permanent PvP zones.
 */
public class PermanentZonePlayerTask extends BukkitRunnable {

    private final PermanentZoneManager permanentZoneManager;

    public PermanentZonePlayerTask(PermanentZoneManager permanentZoneManager) {
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasMetadata("NPC")) continue;

            PermanentZone currentZone = permanentZoneManager.getZoneAt(player.getLocation());
            PermanentZone previousZone = permanentZoneManager.getPlayerZone(player.getUniqueId());

            if (!zonesEqual(currentZone, previousZone)) {
                if (previousZone != null) {
                    onExit(player, previousZone);
                }
                if (currentZone != null) {
                    onEnter(player, currentZone);
                }
                permanentZoneManager.updatePlayerZone(player.getUniqueId(), currentZone);
            }
        }
    }

    private void onEnter(Player player, PermanentZone zone) {
        player.sendActionBar(
            Component.text("Entered permanent zone: ", NamedTextColor.DARK_RED)
                .append(Component.text(zone.getName(), NamedTextColor.RED))
                .append(Component.text(" | PvP is enabled here!", NamedTextColor.DARK_RED))
        );
    }

    private void onExit(Player player, PermanentZone zone) {
        player.sendActionBar(
            Component.text("You have left ", NamedTextColor.GRAY)
                .append(Component.text(zone.getName(), NamedTextColor.WHITE))
        );
        // Buffer stays until extraction or death — no auto-clear on exit
    }

    private boolean zonesEqual(PermanentZone a, PermanentZone b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.getId().equals(b.getId());
    }
}
