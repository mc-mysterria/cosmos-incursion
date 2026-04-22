package net.mysterria.cosmos.domain.exclusion.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Runs every 5 ticks. Handles zone entry/exit tracking and acts as a
 * last-resort backup to return players who escaped with resources.
 *
 * Primary enforcement is in ExclusionZoneListener (event-based).
 */
public class PermanentZonePlayerTask extends BukkitRunnable {

    private final PermanentZoneManager permanentZoneManager;
    private final Map<UUID, BossBar> zoneBossBars = new HashMap<>();

    public PermanentZonePlayerTask(PermanentZoneManager permanentZoneManager) {
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasMetadata("NPC")) continue;

            PermanentZone currentZone = permanentZoneManager.getZoneAt(player.getLocation());
            PermanentZone trackedZone = permanentZoneManager.getPlayerZone(player.getUniqueId());

            // Backup enforcement: player escaped with resources — return them immediately
            if (trackedZone != null && currentZone == null) {
                PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
                if (!buffer.isEmpty()) {
                    forceTeleportBack(player, trackedZone);
                    continue; // Leave zone tracking unchanged until they're actually out legitimately
                }
            }

            // Normal zone entry/exit tracking
            if (!zonesEqual(currentZone, trackedZone)) {
                if (trackedZone != null) onExit(player, trackedZone);
                if (currentZone != null) onEnter(player, currentZone);
                permanentZoneManager.updatePlayerZone(player.getUniqueId(), currentZone);
            }
        }
    }

    /**
     * Pushes the player just inside the zone boundary — walk from their current position
     * toward the centroid in small steps until we hit a point inside the polygon.
     * This creates a natural "bounce" effect rather than an abrupt centroid warp.
     */
    private void forceTeleportBack(Player player, PermanentZone zone) {
        Location outside = player.getLocation();
        Location centroid = zone.getCentroid();
        if (centroid == null || centroid.getWorld() == null) return;

        double dx = centroid.getX() - outside.getX();
        double dz = centroid.getZ() - outside.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) return;
        dx /= dist; // normalize
        dz /= dist;

        // Step inward 1 block at a time until we're back inside
        for (double step = 1.0; step <= dist + 1; step += 1.0) {
            double x = outside.getX() + dx * step;
            double z = outside.getZ() + dz * step;
            if (zone.contains(new Location(outside.getWorld(), x, 64, z))) {
                double y = outside.getWorld().getHighestBlockYAt((int) x, (int) z) + 1.0;
                player.teleport(new Location(outside.getWorld(), x, y, z,
                    outside.getYaw(), outside.getPitch()));
                player.sendActionBar(Component.text("You cannot leave ", NamedTextColor.RED)
                    .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.YELLOW))
                    .append(Component.text(" while carrying resources!", NamedTextColor.RED)));
                return;
            }
        }

        // Fallback: centroid (should be unreachable in practice)
        double y = centroid.getWorld().getHighestBlockYAt(centroid) + 1.0;
        player.teleport(new Location(centroid.getWorld(), centroid.getX(), y, centroid.getZ()));
        player.sendActionBar(Component.text("You cannot leave ", NamedTextColor.RED)
            .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.YELLOW))
            .append(Component.text(" while carrying resources!", NamedTextColor.RED)));
    }

    private void onEnter(Player player, PermanentZone zone) {
        showZoneBossBar(player, zone);
        player.sendActionBar(
            Component.text("Entered extraction zone: ", NamedTextColor.DARK_RED)
                .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.RED))
                .append(Component.text(" | You cannot leave while carrying resources!", NamedTextColor.DARK_RED))
        );
    }

    private void onExit(Player player, PermanentZone zone) {
        permanentZoneManager.cancelExtractionChannel(player.getUniqueId());
        removeZoneBossBar(player);
        player.sendActionBar(
            Component.text("You have left ", NamedTextColor.GRAY)
                .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.WHITE))
        );
    }

    private void showZoneBossBar(Player player, PermanentZone zone) {
        removeZoneBossBar(player);

        BarColor barColor = switch (zone.getTier()) {
            case SAFE   -> BarColor.GREEN;
            case MEDIUM -> BarColor.YELLOW;
            case HARD   -> BarColor.RED;
        };
        String barTitle = switch (zone.getTier()) {
            case SAFE   -> "Safe PvP Zone — " + formatZoneName(zone.getName()) + " | No inventory drops on death";
            case MEDIUM -> "⚠ Medium PvP Zone — " + formatZoneName(zone.getName()) + " | ~33% item drop rate";
            case HARD   -> "⚠ Hardcore PvP Zone — " + formatZoneName(zone.getName()) + " | ALL items drop on death!";
        };

        BossBar bar = Bukkit.createBossBar(barTitle, barColor, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        zoneBossBars.put(player.getUniqueId(), bar);
    }

    private void removeZoneBossBar(Player player) {
        BossBar bar = zoneBossBars.remove(player.getUniqueId());
        if (bar != null) bar.removePlayer(player);
    }

    private static String formatZoneName(String name) {
        return name.replace('_', ' ');
    }

    private boolean zonesEqual(PermanentZone a, PermanentZone b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.getId().equals(b.getId());
    }
}
