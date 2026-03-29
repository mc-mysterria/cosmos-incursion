package net.mysterria.cosmos.domain.exclusion.task;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionPoint;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PointOfInterest;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Visualization for PoIs and extraction points.
 * Runs every 5 ticks (4 times per second).
 *
 * PoI:
 *   - Three stacked horizontal rings forming a cylinder (ground, +3, +6)
 *   - Thick central beacon beam up to BEAM_HEIGHT blocks (4 particles per level with spread)
 *   - END_ROD sparkle cap at the top of the beam
 *   - Rotating ItemDisplay entity
 *
 * Extraction point:
 *   - Three stacked rings at capture radius (exact "stand here" indicator)
 *   - Double helix column (two strands, 180° apart) up to BEAM_HEIGHT
 *   - SOUL_FIRE_FLAME cap at beam top (distinctive teal flame)
 *   - Wide floor disc to mark the landing zone
 */
public class PoIVisualizationTask extends BukkitRunnable {

    private static final Color EP_COLOR = Color.fromRGB(0, 220, 200);

    private static final double VIEW_DISTANCE = 80.0;

    // Ring geometry
    private static final int RING_POINTS = 36;
    /** Vertical gap between each stacked ring level. */
    private static final double RING_LEVEL_GAP = 3.0;
    /** Number of stacked rings to draw (creates a cylinder). */
    private static final int RING_LEVELS = 3;

    // Beam geometry
    private static final int BEAM_HEIGHT = 25;
    /** Spread applied to each beam-level spawn — gives the column thickness. */
    private static final double BEAM_SPREAD = 0.18;
    /** Particles emitted per height level in the beam. */
    private static final int BEAM_COUNT = 4;

    // Visuals
    private static final float RING_PARTICLE_SIZE = 1.8f;
    private static final float BEAM_PARTICLE_SIZE = 2.2f;

    private final CosmosIncursion plugin;
    private final PermanentZoneManager permanentZoneManager;
    private double ringAngleOffset = 0;

    public PoIVisualizationTask(CosmosIncursion plugin, PermanentZoneManager permanentZoneManager) {
        this.plugin = plugin;
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        if (!plugin.getConfigLoader().getConfig().isPermanentZoneParticlesEnabled()) return;

        ringAngleOffset = (ringAngleOffset + Math.PI / 18) % (2 * Math.PI);

        for (PermanentZone zone : permanentZoneManager.getAllZones()) {
            if (!zone.isActive()) continue;

            for (PointOfInterest poi : permanentZoneManager.getActivePoIs(zone)) {
                if (!poi.isActive()) continue;
                rotateDisplayEntity(permanentZoneManager.getPoIDisplayEntity(poi.getId()));
                spawnPoIParticles(poi);
            }

            for (ExtractionPoint ep : permanentZoneManager.getActiveExtractionPoints(zone)) {
                if (!ep.isActive()) continue;
                spawnExtractionPointParticles(ep);
            }
        }
    }

    // ── Item display rotation ─────────────────────────────────────────────────────

    private void rotateDisplayEntity(Entity entity) {
        if (entity == null || entity.isDead() || !(entity instanceof ItemDisplay display)) return;
        float angle = (float) ((System.currentTimeMillis() % 8000L) / 8000.0 * 2 * Math.PI);
        display.setInterpolationDelay(-1);
        display.setInterpolationDuration(5);
        display.setTransformation(new Transformation(
            new Vector3f(0, 0, 0),
            new Quaternionf().rotateY(angle),
            new Vector3f(0.8f, 0.8f, 0.8f),
            new Quaternionf()
        ));
    }

    // ── PoI particles ─────────────────────────────────────────────────────────────

    private void spawnPoIParticles(PointOfInterest poi) {
        Location center = poi.getLocation();
        World world = center.getWorld();
        if (world == null) return;
        if (world.getNearbyPlayers(center, VIEW_DISTANCE).isEmpty()) return;

        Color color = getResourceColor(poi.getResourceType());
        Particle.DustOptions ringDust = new Particle.DustOptions(color, RING_PARTICLE_SIZE);
        Particle.DustOptions beamDust = new Particle.DustOptions(color, BEAM_PARTICLE_SIZE);
        double radius = poi.getExtractionRadius();

        // Three stacked horizontal rings — creates a visible cylinder at any elevation
        for (int level = 0; level < RING_LEVELS; level++) {
            double ringY = center.getY() + 0.2 + level * RING_LEVEL_GAP;
            // Alternate rotation direction per level for visual depth
            double angleDir = (level % 2 == 0) ? 1 : -1;
            for (int i = 0; i < RING_POINTS; i++) {
                double angle = (2 * Math.PI * i / RING_POINTS) + angleDir * ringAngleOffset;
                double rx = center.getX() + radius * Math.cos(angle);
                double rz = center.getZ() + radius * Math.sin(angle);
                world.spawnParticle(Particle.DUST, rx, ringY, rz, 1, 0.03, 0.03, 0.03, 0, ringDust);
            }
        }

        // Thick central beacon beam — spread gives column width, high count gives density
        long time = System.currentTimeMillis();
        for (int h = 1; h <= BEAM_HEIGHT; h++) {
            double beamY = center.getY() + h;
            // Slow gentle rotation so the beam shimmers rather than staying static
            double twist = Math.sin((time / 400.0) + h * 0.4) * 0.08;
            world.spawnParticle(Particle.DUST,
                center.getX() + twist, beamY, center.getZ() + twist,
                BEAM_COUNT, BEAM_SPREAD, 0.05, BEAM_SPREAD, 0, beamDust);
        }

        // END_ROD sparkle cap — white sparkles at beam top; highly visible even far away
        for (int i = 0; i < 6; i++) {
            double capAngle = (2 * Math.PI * i / 6) + ringAngleOffset * 2;
            double capX = center.getX() + 0.4 * Math.cos(capAngle);
            double capZ = center.getZ() + 0.4 * Math.sin(capAngle);
            world.spawnParticle(Particle.END_ROD,
                capX, center.getY() + BEAM_HEIGHT, capZ,
                1, 0.1, 0.2, 0.1, 0.02);
        }
    }

    // ── Extraction point particles ─────────────────────────────────────────────────

    private void spawnExtractionPointParticles(ExtractionPoint ep) {
        Location center = ep.getLocation();
        World world = center.getWorld();
        if (world == null) return;
        if (world.getNearbyPlayers(center, VIEW_DISTANCE).isEmpty()) return;

        Particle.DustOptions ringDust = new Particle.DustOptions(EP_COLOR, RING_PARTICLE_SIZE);
        Particle.DustOptions beamDust = new Particle.DustOptions(EP_COLOR, BEAM_PARTICLE_SIZE);
        double radius = ep.getCaptureRadius();

        // Three stacked rings at capture radius — shows exactly where to stand
        for (int level = 0; level < RING_LEVELS; level++) {
            double ringY = center.getY() + 0.2 + level * RING_LEVEL_GAP;
            double angleDir = (level % 2 == 0) ? -1 : 1; // counter-rotation for EP
            for (int i = 0; i < RING_POINTS; i++) {
                double angle = (2 * Math.PI * i / RING_POINTS) + angleDir * ringAngleOffset;
                double rx = center.getX() + radius * Math.cos(angle);
                double rz = center.getZ() + radius * Math.sin(angle);
                world.spawnParticle(Particle.DUST, rx, ringY, rz, 1, 0.03, 0.03, 0.03, 0, ringDust);
            }
        }

        // Floor disc — fills the inner area at ground level so the "step here" zone is obvious
        double floorStep = 1.2;
        for (double r = floorStep; r < radius; r += floorStep) {
            int pointsAtRadius = Math.max(6, (int) (2 * Math.PI * r / floorStep));
            for (int i = 0; i < pointsAtRadius; i++) {
                double angle = (2 * Math.PI * i / pointsAtRadius) - ringAngleOffset;
                double fx = center.getX() + r * Math.cos(angle);
                double fz = center.getZ() + r * Math.sin(angle);
                world.spawnParticle(Particle.DUST, fx, center.getY() + 0.05, fz,
                    1, 0.02, 0.02, 0.02, 0, ringDust);
            }
        }

        // Double helix column — two strands 180° apart, clearly distinct from PoI beam
        long time = System.currentTimeMillis();
        double helixSpeed = time / 500.0;
        for (int h = 1; h <= BEAM_HEIGHT; h++) {
            double helixY = center.getY() + h;
            for (int strand = 0; strand < 2; strand++) {
                double helixAngle = helixSpeed + h * 0.45 + strand * Math.PI;
                double hx = center.getX() + 0.45 * Math.cos(helixAngle);
                double hz = center.getZ() + 0.45 * Math.sin(helixAngle);
                world.spawnParticle(Particle.DUST,
                    hx, helixY, hz,
                    BEAM_COUNT, BEAM_SPREAD * 0.6, 0.05, BEAM_SPREAD * 0.6, 0, beamDust);
            }
        }

        // SOUL_FIRE_FLAME cap — teal flame at column top; unmistakable extraction point marker
        world.spawnParticle(Particle.SOUL_FIRE_FLAME,
            center.getX(), center.getY() + BEAM_HEIGHT, center.getZ(),
            8, 0.3, 0.4, 0.3, 0.02);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private Color getResourceColor(ResourceType type) {
        return switch (type) {
            case GOLD   -> Color.fromRGB(255, 200, 0);
            case SILVER -> Color.fromRGB(200, 200, 220);
            case GEMS   -> Color.fromRGB(0, 210, 80);
        };
    }
}
