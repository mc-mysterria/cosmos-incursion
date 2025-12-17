package net.mysterria.cosmos.zone;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

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
     * Generate candidate locations around towns with randomness
     */
    private List<Location> generateCandidateLocations(World world, List<Location> townLocations, int count) {
        List<Location> candidates = new ArrayList<>();
        java.util.Random random = new java.util.Random();

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

        // Generate candidates with randomness
        int candidateCount = count * 8; // Generate more candidates for better variety
        double minRadius = avgDistance * 0.5; // Minimum 50% of distance to towns
        double maxRadius = avgDistance * 0.9; // Maximum 90% of distance to towns

        for (int i = 0; i < candidateCount; i++) {
            // Random angle instead of evenly distributed
            double angle = random.nextDouble() * 2 * Math.PI;

            // Random radius within range
            double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);

            // Calculate base position
            double x = centerX + radius * Math.cos(angle);
            double z = centerZ + radius * Math.sin(angle);

            // Add additional random offset (±100 blocks)
            x += (random.nextDouble() - 0.5) * 200;
            z += (random.nextDouble() - 0.5) * 200;

            Location candidate = findSuitableSurfaceLocation(world, (int) x, (int) z);
            if (candidate != null) {
                candidates.add(candidate);
            }
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
     * Find a suitable surface location for a zone (avoid oceans and underwater areas)
     */
    private Location findSuitableSurfaceLocation(World world, int x, int z) {
        // Get the highest block
        int y = world.getHighestBlockYAt(x, z);
        Location location = new Location(world, x, y, z);

        // Check biome - reject ocean biomes
        Biome biome = world.getBiome(location);
        if (isOceanBiome(biome)) {
            return null;
        }

        // Check if the surface is underwater
        Block surfaceBlock = world.getBlockAt(x, y, z);
        Block aboveBlock = world.getBlockAt(x, y + 1, z);

        // Reject if water or lava at or above surface
        if (surfaceBlock.getType() == Material.WATER || surfaceBlock.getType() == Material.LAVA) {
            return null;
        }
        if (aboveBlock.getType() == Material.WATER || aboveBlock.getType() == Material.LAVA) {
            return null;
        }

        // Check if there's a significant amount of water/lava in the zone area
        if (!isAreaSuitableForCombat(world, x, y, z)) {
            return null;
        }

        return location;
    }

    /**
     * Check if a biome is an ocean biome
     */
    private boolean isOceanBiome(Biome biome) {
        return biome.translationKey().contains("OCEAN") ||
               biome.translationKey().contains("DEEP") ||
               biome == Biome.RIVER;
    }

    /**
     * Check if the area around a location is suitable for combat (mostly land, not water)
     */
    private boolean isAreaSuitableForCombat(World world, int centerX, int centerY, int centerZ) {
        int radius = 30; // Check a 60x60 area
        int waterCount = 0;
        int totalChecked = 0;
        int checkInterval = 10; // Check every 10 blocks to avoid lag

        for (int x = centerX - radius; x <= centerX + radius; x += checkInterval) {
            for (int z = centerZ - radius; z <= centerZ + radius; z += checkInterval) {
                totalChecked++;

                // Check if this location has water at surface level
                int y = world.getHighestBlockYAt(x, z);
                Block block = world.getBlockAt(x, y, z);
                Block above = world.getBlockAt(x, y + 1, z);

                if (block.getType() == Material.WATER || above.getType() == Material.WATER) {
                    waterCount++;
                }
            }
        }

        // Reject if more than 30% of the area is water
        double waterPercentage = (double) waterCount / totalChecked;
        return waterPercentage <= 0.3;
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
