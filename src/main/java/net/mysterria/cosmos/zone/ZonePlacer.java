package net.mysterria.cosmos.zone;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class ZonePlacer {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;

    public ZonePlacer(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager().getConfig();
    }

    /**
     * Calculate number of zones to create based on online players
     */
    public int calculateZoneCount() {
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int baseCount = config.getZoneBaseCount();
        int playersPerZone = config.getPlayersPerZone();
        int maxCount = config.getZoneMaxCount();

        // Calculate: baseCount + (additional players / playersPerZone)
        int calculatedCount = baseCount + ((onlinePlayers - config.getMinPlayers()) / playersPerZone);

        return Math.min(calculatedCount, maxCount);
    }

    /**
     * Generate zone locations that are equidistant from towns and avoid town claims
     */
    public List<IncursionZone> generateZones(int count) {
        List<IncursionZone> incursionZones = new ArrayList<>();
        World overworld = Bukkit.getWorlds().getFirst(); // Primary world

        if (overworld == null) {
            plugin.log("Warning: Could not find overworld for zone generation");
            return incursionZones;
        }

        // Get town locations
        List<Location> townLocations = TownsToolkit.getTownLocations();
        if (townLocations.isEmpty()) {
            plugin.log("Warning: No towns found. Using world spawn for zone generation");
            townLocations.add(overworld.getSpawnLocation());
        }

        // Get claimed chunks to avoid
        Set<TownsToolkit.ChunkPosition> claimedChunks = TownsToolkit.getClaimedChunks(overworld);

        // Calculate zone center candidates
        List<Location> candidates = generateCandidateLocations(overworld, townLocations, count);

        // Filter and validate candidates
        int zoneNumber = 1;
        for (Location candidate : candidates) {
            if (incursionZones.size() >= count) {
                break;
            }

            // Validate this candidate
            if (isValidZoneLocation(candidate, claimedChunks, incursionZones)) {
                IncursionZone incursionZone = new IncursionZone("Zone-" + zoneNumber, candidate, config.getZoneRadius());
                incursionZones.add(incursionZone);
                plugin.log("Generated zone at: " + String.format("(%.0f, %.0f, %.0f)",
                        candidate.getX(), candidate.getY(), candidate.getZ()));
                zoneNumber++;
            }
        }

        if (incursionZones.size() < count) {
            plugin.log("Warning: Could only generate " + incursionZones.size() + " out of " + count + " requested zones");
        }

        return incursionZones;
    }

    /**
     * Generate candidate locations around towns
     */
    private List<Location> generateCandidateLocations(World world, List<Location> townLocations, int count) {
        List<Location> candidates = new ArrayList<>();

        // Calculate center point of all towns
        double centerX = townLocations.stream().mapToDouble(Location::getX).average().orElse(0);
        double centerZ = townLocations.stream().mapToDouble(Location::getZ).average().orElse(0);

        // Calculate average distance from center to towns
        double avgDistance = townLocations.stream()
                .mapToDouble(loc -> {
                    double dx = loc.getX() - centerX;
                    double dz = loc.getZ() - centerZ;
                    return Math.sqrt(dx * dx + dz * dz);
                })
                .average()
                .orElse(1000.0);

        // Generate candidates in a circle around the center, between towns
        double radius = avgDistance * 0.7; // Place zones 70% of the way from center to towns
        int candidateCount = count * 4; // Generate more candidates than needed

        for (int i = 0; i < candidateCount; i++) {
            double angle = (2 * Math.PI * i) / candidateCount;
            double x = centerX + radius * Math.cos(angle);
            double z = centerZ + radius * Math.sin(angle);
            int y = world.getHighestBlockYAt((int) x, (int) z);

            candidates.add(new Location(world, x, y, z));
        }

        // Sort by how far they are from nearest town (prefer locations between towns)
        candidates.sort(Comparator.comparingDouble(loc ->
                townLocations.stream()
                        .mapToDouble(town -> {
                            if (loc instanceof Location location) {
                                double dx = location.getX() - town.getX();
                                double dz = location.getZ() - town.getZ();
                                return Math.sqrt(dx * dx + dz * dz);
                            } else {
                                return Double.MAX_VALUE;
                            }
                        })
                        .min()
                        .orElse(Double.MAX_VALUE)
        ).reversed());

        return candidates;
    }

    /**
     * Validate if a location is suitable for a zone
     */
    private boolean isValidZoneLocation(Location location, Set<TownsToolkit.ChunkPosition> claimedChunks, List<IncursionZone> existingIncursionZones) {
        double radius = config.getZoneRadius();
        double townBuffer = config.getTownBuffer();
        double minSeparation = config.getMinZoneSeparation();

        // Check if zone overlaps with any town claims
        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;
        int chunkRadius = (int) Math.ceil(radius / 16.0) + (int) Math.ceil(townBuffer / 16.0);

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                TownsToolkit.ChunkPosition chunkPos = new TownsToolkit.ChunkPosition(
                        centerChunkX + dx,
                        centerChunkZ + dz
                );

                // Check if this chunk is claimed and within the buffer distance
                if (claimedChunks.contains(chunkPos)) {
                    double distance = chunkPos.distanceTo(location.getX(), location.getZ());
                    if (distance < radius + townBuffer) {
                        return false; // Too close to a town claim
                    }
                }
            }
        }

        // Check minimum separation from existing zones
        for (IncursionZone existing : existingIncursionZones) {
            if (existing.isTooClose(new IncursionZone("temp", location, radius), minSeparation)) {
                return false;
            }
        }

        return true;
    }

}
