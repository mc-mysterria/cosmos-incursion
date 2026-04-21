package net.mysterria.cosmos.domain.exclusion.model;

import lombok.Getter;
import lombok.Setter;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A permanent PvP extraction zone defined by an arbitrary polygon.
 * Vertices are set via admin commands; containment uses the ray-casting algorithm.
 */
@Getter
public class PermanentZone {

    private final UUID id;
    private final String name;
    /** Ordered list of polygon vertices (XZ plane; Y is ignored for containment). */
    private final List<Location> vertices;

    @Setter
    private boolean active = true;

    @Setter
    private ExclusionZoneTier tier = ExclusionZoneTier.MEDIUM;

    public PermanentZone(UUID id, String name, List<Location> vertices) {
        this.id = id;
        this.name = name;
        this.vertices = new ArrayList<>(vertices);
    }

    public PermanentZone(String name, List<Location> vertices) {
        this(UUID.randomUUID(), name, vertices);
    }

    /**
     * Returns a read-only view of the polygon vertices.
     */
    public List<Location> getVertices() {
        return Collections.unmodifiableList(vertices);
    }

    /**
     * Appends a new vertex to the polygon.
     */
    public void addVertex(Location loc) {
        vertices.add(loc);
    }

    /**
     * Replaces the vertex at the given index.
     */
    public void setVertex(int index, Location loc) {
        vertices.set(index, loc);
    }

    /**
     * Removes the vertex at the given index.
     */
    public void removeVertex(int index) {
        vertices.remove(index);
    }

    /**
     * 2D point-in-polygon test using the ray-casting algorithm (XZ plane).
     * Returns false if the zone has fewer than 3 vertices or the location is in a different world.
     */
    public boolean contains(Location loc) {
        if (vertices.size() < 3) return false;
        World world = getWorld();
        if (world == null || loc.getWorld() == null || !world.equals(loc.getWorld())) return false;

        double px = loc.getX();
        double pz = loc.getZ();
        boolean inside = false;
        int n = vertices.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = vertices.get(i).getX(), zi = vertices.get(i).getZ();
            double xj = vertices.get(j).getX(), zj = vertices.get(j).getZ();
            if (((zi > pz) != (zj > pz)) && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Returns the geometric centroid of the polygon (used for PoI/extraction-point spawning).
     */
    public Location getCentroid() {
        if (vertices.isEmpty()) return null;
        double sumX = 0, sumY = 0, sumZ = 0;
        for (Location v : vertices) {
            sumX += v.getX();
            sumY += v.getY();
            sumZ += v.getZ();
        }
        int n = vertices.size();
        return new Location(getWorld(), sumX / n, sumY / n, sumZ / n);
    }

    /**
     * Returns the approximate bounding-circle radius from the centroid.
     * Used for random-point generation inside the polygon.
     */
    public double getApproximateRadius() {
        Location centroid = getCentroid();
        if (centroid == null) return 0;
        double maxDistSq = 0;
        for (Location v : vertices) {
            double dx = v.getX() - centroid.getX();
            double dz = v.getZ() - centroid.getZ();
            maxDistSq = Math.max(maxDistSq, dx * dx + dz * dz);
        }
        return Math.sqrt(maxDistSq);
    }

    /** World of the first vertex, or null if no vertices. */
    public World getWorld() {
        return vertices.isEmpty() ? null : vertices.get(0).getWorld();
    }
}
