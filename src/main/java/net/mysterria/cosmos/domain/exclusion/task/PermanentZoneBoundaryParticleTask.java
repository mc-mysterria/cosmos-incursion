package net.mysterria.cosmos.domain.exclusion.task;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.List;

/**
 * Spawns a tall particle wall along permanent zone polygon boundaries.
 *
 * Each edge is sampled at SAMPLE_INTERVAL block intervals. At each sample point a
 * vertical column of particles is drawn from the ground up to WALL_HEIGHT blocks
 * above it, so the boundary is visible regardless of whether the player is in a
 * valley or on top of a cliff.
 *
 * Runs every 40 ticks (2 seconds). Uses per-player checks so particles only
 * render for nearby players.
 */
public class PermanentZoneBoundaryParticleTask extends BukkitRunnable {

    private static final Color BOUNDARY_COLOR = Color.fromRGB(180, 0, 0);
    /** Horizontal distance between sample points along each edge (blocks). */
    private static final double SAMPLE_INTERVAL = 2.5;
    /** How many blocks above ground the wall extends. */
    private static final int WALL_HEIGHT = 60;
    /** Vertical spacing between particle levels in the wall (blocks). */
    private static final int WALL_STEP = 3;
    private static final float PARTICLE_SIZE = 1.5f;
    private static final double VIEW_DISTANCE = 80.0;

    private final CosmosIncursion plugin;
    private final PermanentZoneManager permanentZoneManager;
    private double tOffset = 0;

    public PermanentZoneBoundaryParticleTask(CosmosIncursion plugin, PermanentZoneManager permanentZoneManager) {
        this.plugin = plugin;
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        if (!plugin.getConfigLoader().getConfig().isPermanentZoneParticlesEnabled()) return;

        tOffset = (tOffset + 0.12) % 1.0;

        for (PermanentZone zone : permanentZoneManager.getAllZones()) {
            if (!zone.isActive()) continue;
            List<Location> verts = zone.getVertices();
            if (verts.size() < 3) continue;
            spawnBoundaryParticles(zone, verts);
        }
    }

    private void spawnBoundaryParticles(PermanentZone zone, List<Location> verts) {
        World world = zone.getWorld();
        if (world == null) return;

        Particle.DustOptions dust = new Particle.DustOptions(BOUNDARY_COLOR, PARTICLE_SIZE);
        int n = verts.size();

        for (int i = 0; i < n; i++) {
            Location a = verts.get(i);
            Location b = verts.get((i + 1) % n);

            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.1) continue;

            int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_INTERVAL));

            for (int s = 0; s < steps; s++) {
                double t = ((double) s / steps + tOffset) % 1.0;
                double x = a.getX() + t * dx;
                double z = a.getZ() + t * dz;

                // Never force-load chunks — getHighestBlockYAt causes syncLoad on unloaded chunks
                if (!world.isChunkLoaded((int) x >> 4, (int) z >> 4)) continue;

                // Check for nearby players before the expensive ground-Y lookup
                double roughY = a.getY() + t * (b.getY() - a.getY());
                Collection<Player> nearby = world.getNearbyPlayers(
                    new Location(world, x, roughY, z), VIEW_DISTANCE);
                if (nearby.isEmpty()) continue;

                int groundY = world.getHighestBlockYAt((int) x, (int) z);

                // Spawn a full vertical wall from ground up to WALL_HEIGHT.
                // This ensures the boundary is visible from any elevation: valley, cliff, or sky.
                for (int h = 0; h <= WALL_HEIGHT; h += WALL_STEP) {
                    double y = groundY + 1.0 + h;
                    // Only render to players who are actually within vertical range to see this level
                    for (Player player : nearby) {
                        double eyeY = player.getEyeLocation().getY();
                        if (Math.abs(eyeY - y) > VIEW_DISTANCE) continue;
                        player.spawnParticle(Particle.DUST, x, y, z, 2, 0.05, 0.05, 0.05, 0, dust);
                    }
                }
            }
        }
    }
}
