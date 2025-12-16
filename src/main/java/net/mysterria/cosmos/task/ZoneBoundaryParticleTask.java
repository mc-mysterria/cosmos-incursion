package net.mysterria.cosmos.task;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.zone.IncursionZone;
import net.mysterria.cosmos.zone.ZoneManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;

/**
 * Spawns particles around zone boundaries for visual indication
 * Only shows particles to nearby players for performance
 */
public class ZoneBoundaryParticleTask extends BukkitRunnable {

    private final ZoneManager zoneManager;
    private final CosmosConfig config;

    // Rotate angle for animated effect
    private double angleOffset = 0;

    public ZoneBoundaryParticleTask(CosmosIncursion plugin, ZoneManager zoneManager) {
        this.zoneManager = zoneManager;
        this.config = plugin.getConfigManager().getConfig();
    }

    @Override
    public void run() {
        if (!config.isZoneBoundaryParticlesEnabled()) {
            return;
        }

        Collection<IncursionZone> activeZones = zoneManager.getActiveZones();
        if (activeZones.isEmpty()) {
            return;
        }

        // Update rotation angle for animation
        angleOffset += Math.PI / 16; // Rotate slowly
        if (angleOffset >= 2 * Math.PI) {
            angleOffset = 0;
        }

        // Process each active zone
        for (IncursionZone zone : activeZones) {
            spawnBoundaryParticles(zone);
        }
    }

    /**
     * Spawn particles around a zone's boundary
     */
    private void spawnBoundaryParticles(IncursionZone zone) {
        Location center = zone.getCenter();
        double radius = zone.getRadius();
        double viewDistance = config.getZoneBoundaryParticleViewDistance();

        // Get nearby players who can see these particles
        Collection<Player> nearbyPlayers = center.getWorld().getNearbyPlayers(center, radius + viewDistance);
        if (nearbyPlayers.isEmpty()) {
            return;
        }

        // Calculate number of points based on radius (more points for denser boundary)
        int pointCount = (int) (radius / 2); // 1 point every 2 blocks (increased from 5)
        pointCount = Math.max(32, Math.min(pointCount, 128)); // Between 32 and 128 points

        // Create particle options (larger, brighter particles)
        Particle.DustOptions dustOptions = new Particle.DustOptions(
                org.bukkit.Color.fromRGB(255, 50, 50), // Red color
                2.0f // Increased size from 1.0 to 2.0
        );

        // Spawn particles in a circle with multiple height levels
        for (int i = 0; i < pointCount; i++) {
            double angle = (2 * Math.PI * i / pointCount) + angleOffset;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);

            // Get ground level at this position
            int groundY = center.getWorld().getHighestBlockYAt((int) x, (int) z);

            // Spawn particles at multiple heights (create vertical columns)
            for (int heightOffset = 0; heightOffset <= 4; heightOffset++) {
                double y = groundY + 1 + (heightOffset * 0.5); // Every 0.5 blocks vertically
                Location particleLocation = new Location(center.getWorld(), x, y, z);

                // Only show to nearby players (client-side rendering)
                for (Player player : nearbyPlayers) {
                    // Check if player is close enough to see this specific particle
                    if (player.getLocation().distance(particleLocation) <= viewDistance) {
                        player.spawnParticle(
                                Particle.DUST,
                                particleLocation,
                                3, // Increased particle count per position
                                0.1, 0.1, 0.1, // Small random offset for fullness
                                0, // extra speed
                                dustOptions
                        );
                    }
                }
            }
        }
    }

}
