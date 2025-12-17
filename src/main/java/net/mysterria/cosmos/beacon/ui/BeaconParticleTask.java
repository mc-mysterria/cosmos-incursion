package net.mysterria.cosmos.beacon.ui;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.beacon.BeaconCapture;
import net.mysterria.cosmos.beacon.BeaconManager;
import net.mysterria.cosmos.beacon.SpiritBeacon;
import net.mysterria.cosmos.config.CosmosConfig;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * Particle effect task for beacons
 * Runs every 0.5 seconds (10 ticks)
 * Spawns colored particles at beacon centers
 */
public class BeaconParticleTask extends BukkitRunnable {

    private static final double PARTICLE_RADIUS = 64.0;  // Client render distance consideration
    private static final int MAX_PARTICLES_PER_BEACON = 20;
    private final CosmosIncursion plugin;
    private final BeaconManager beaconManager;
    private final CosmosConfig config;
    private final BeaconUIManager uiManager;

    public BeaconParticleTask(CosmosIncursion plugin, BeaconManager beaconManager,
                              CosmosConfig config, BeaconUIManager uiManager) {
        this.plugin = plugin;
        this.beaconManager = beaconManager;
        this.config = config;
        this.uiManager = uiManager;
    }

    @Override
    public void run() {
        try {
            for (BeaconCapture capture : beaconManager.getAllCaptureStates()) {
                spawnBeaconParticles(capture);
            }
        } catch (Exception e) {
            plugin.log("Error in BeaconParticleTask: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Spawn particles for a single beacon
     */
    private void spawnBeaconParticles(BeaconCapture capture) {
        SpiritBeacon beacon = capture.getBeacon();
        Location beaconLoc = beacon.location();

        // Get nearby players
        List<Player> nearbyPlayers = uiManager.getNearbyPlayers(beaconLoc, PARTICLE_RADIUS);
        if (nearbyPlayers.isEmpty()) {
            return;  // No one to show particles to
        }

        // Calculate particle count based on capture progress
        double progress = capture.getCaptureProgress() / config.getBeaconCapturePoints();
        int baseParticles = 5;
        int progressParticles = (int) (progress * 10);
        int particleCount = Math.min(baseParticles + progressParticles, MAX_PARTICLES_PER_BEACON);

        // Get color based on ownership
        Color particleColor = getTownParticleColor(capture);

        // Spawn particles in spiral pattern
        for (int i = 0; i < particleCount; i++) {
            spawnSpiralParticle(beaconLoc, progress, i, particleColor, nearbyPlayers);
        }
    }

    /**
     * Spawn a single particle in spiral pattern
     */
    private void spawnSpiralParticle(Location center, double progress, int index,
                                     Color color, List<Player> viewers) {
        // Spiral pattern calculation
        long time = System.currentTimeMillis();
        double angle = ((time % 3000) / 3000.0 * Math.PI * 2) + (index * 0.3);
        double radius = 1.5;
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle) * radius;

        // Height rises with progress (0 to 3 blocks)
        double y = progress * 3.0 + (index * 0.1);

        Location particleLoc = center.clone().add(x, y + 1, z);

        // Create dust options
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);

        // Spawn particle for specific players only (not broadcast)
        for (Player player : viewers) {
            player.spawnParticle(Particle.DUST, particleLoc, 1, 0, 0, 0, 0, dust);
        }
    }

    /**
     * Get particle color based on town ownership
     */
    private Color getTownParticleColor(BeaconCapture capture) {
        if (capture.isContested()) {
            // Contested - alternating red/blue (flashing)
            long time = System.currentTimeMillis();
            boolean isRed = (time / 500) % 2 == 0;  // Flash every 0.5 seconds
            return isRed ? Color.RED : Color.BLUE;
        } else if (capture.getOwningTownId() != 0) {
            // Owned by a town - use town color
            return getTownColor(capture.getOwningTownId());
        } else {
            // Neutral - white
            return Color.WHITE;
        }
    }

    /**
     * Get consistent color for a town based on town ID
     */
    private Color getTownColor(int townId) {
        long hashLong = Math.abs(((long) townId) * 2654435761L);  // Knuth's multiplicative hash
        int hash = (int) hashLong;
        int r = (hash & 0xFF);
        int g = ((hash >> 8) & 0xFF);
        int b = ((hash >> 16) & 0xFF);

        // Ensure colors are reasonably bright (minimum 50)
        r = Math.max(r, 50);
        g = Math.max(g, 50);
        b = Math.max(b, 50);

        return Color.fromRGB(r, g, b);
    }
}
