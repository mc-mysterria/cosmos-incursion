package net.mysterria.cosmos.toolkit;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ZonePlacerToolkit {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;

    public ZonePlacerToolkit(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigLoader().getConfig();
    }

    /**
     * Calculate number of zones to create based on online players
     */
    public int calculateZoneCount() {
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        int baseCount = config.getZoneBaseCount();
        int playersPerZone = config.getPlayersPerZone();
        int maxCount = config.getZoneMaxCount();

        // Calculate: baseCount + (additional players / playersPerZone), minimum 1
        int calculatedCount = baseCount + ((onlinePlayers - config.getMinPlayers()) / playersPerZone);

        return Math.max(1, Math.min(calculatedCount, maxCount));
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

        plugin.log("Zone generation: " + townLocations.size() + " town location(s), targeting " + count + " zone(s)");

        // Get claimed chunks to avoid
        Set<TownsToolkit.ChunkPosition> claimedChunks = TownsToolkit.getClaimedChunks(overworld);
        plugin.log("Zone generation: " + claimedChunks.size() + " claimed chunks to avoid");

        // Calculate zone center candidates
        List<Location> candidates = generateCandidateLocations(overworld, townLocations, count);
        plugin.log("Zone generation: " + candidates.size() + " surface candidates after terrain filtering");

        // Build an ordered list of tiers to assign (GREEN first, DEATH last)
        List<ZoneTier> tierQueue = buildTierQueue();

        // Filter and validate candidates
        int rejectedByTown = 0;
        int rejectedBySeparation = 0;
        int zoneNumber = 1;
        for (Location candidate : candidates) {
            if (incursionZones.size() >= count) {
                break;
            }

            int rejectReason = validationRejectReason(candidate, claimedChunks, incursionZones);
            if (rejectReason == 0) {
                ZoneTier tier = (zoneNumber - 1) < tierQueue.size()
                        ? tierQueue.get(zoneNumber - 1)
                        : ZoneTier.GREEN;

                IncursionZone incursionZone = new IncursionZone("Zone-" + zoneNumber, candidate, config.getZoneRadius(), tier);
                incursionZones.add(incursionZone);
                plugin.log("Generated " + tier + " zone at: " + String.format("(%.0f, %.0f, %.0f)",
                        candidate.getX(), candidate.getY(), candidate.getZ()));
                zoneNumber++;
            } else if (rejectReason == 1) {
                rejectedByTown++;
            } else {
                rejectedBySeparation++;
            }
        }

        plugin.log("Zone generation: " + rejectedByTown + " rejected (town buffer), "
                + rejectedBySeparation + " rejected (zone separation)");

        if (incursionZones.size() < count) {
            plugin.log("Warning: Could only generate " + incursionZones.size() + " out of " + count + " requested zones");
        }

        return incursionZones;
    }

    /**
     * Build the ordered list of tiers to assign to zones.
     * Iterates GREEN → YELLOW → RED → DEATH, repeating each tier as many times
     * as configured in tier-distribution.
     */
    private List<ZoneTier> buildTierQueue() {
        List<ZoneTier> queue = new ArrayList<>();
        Map<ZoneTier, Integer> distribution = config.getTierDistribution();
        for (ZoneTier tier : ZoneTier.values()) {
            int count = distribution.getOrDefault(tier, 0);
            for (int i = 0; i < count; i++) {
                queue.add(tier);
            }
        }
        return queue;
    }

    /** Returns 0=ok, 1=town buffer, 2=separation */
    private int validationRejectReason(Location location, Set<TownsToolkit.ChunkPosition> claimedChunks, List<IncursionZone> existingIncursionZones) {
        double radius = config.getZoneRadius();
        double townBuffer = config.getTownBuffer();
        double minSeparation = config.getMinZoneSeparation();

        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;
        int chunkRadius = (int) Math.ceil(radius / 16.0) + (int) Math.ceil(townBuffer / 16.0);

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                TownsToolkit.ChunkPosition chunkPos = new TownsToolkit.ChunkPosition(
                        centerChunkX + dx, centerChunkZ + dz);
                if (claimedChunks.contains(chunkPos)) {
                    double distance = chunkPos.distanceTo(location.getX(), location.getZ());
                    if (distance < radius + townBuffer) return 1;
                }
            }
        }

        for (IncursionZone existing : existingIncursionZones) {
            if (existing.isTooClose(new IncursionZone("temp", location, radius), minSeparation)) return 2;
        }

        return 0;
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

        // Calculate average distance from center to towns.
        // When there is only one town, avgDistance collapses to 0, so enforce a floor so
        // candidates are placed far enough away from town claims to pass validation.
        double avgDistance = townLocations.stream()
                .mapToDouble(loc -> {
                    double dx = loc.getX() - centerX;
                    double dz = loc.getZ() - centerZ;
                    return Math.sqrt(dx * dx + dz * dz);
                })
                .average()
                .orElse(1000.0);
        double effectiveDistance = Math.max(avgDistance, 1000.0);

        // Generate candidates with randomness
        int candidateCount = count * 20; // Generate more candidates for better variety
        double minRadius = effectiveDistance * 0.5;
        double maxRadius = effectiveDistance * 1.5;

        plugin.log("Zone generation: radius range " + String.format("%.0f–%.0f", minRadius, maxRadius)
                + " from center (" + String.format("%.0f,%.0f", centerX, centerZ) + ")");

        int rejOcean = 0, rejWater = 0, rejAreaWater = 0;
        for (int i = 0; i < candidateCount; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double radius = minRadius + random.nextDouble() * (maxRadius - minRadius);

            double x = centerX + radius * Math.cos(angle);
            double z = centerZ + radius * Math.sin(angle);

            x += (random.nextDouble() - 0.5) * 200;
            z += (random.nextDouble() - 0.5) * 200;

            int[] rejection = new int[1];
            Location candidate = findSuitableSurfaceLocation(world, (int) x, (int) z, rejection);
            if (candidate != null) {
                candidates.add(candidate);
            } else if (rejection[0] == 1) rejOcean++;
            else if (rejection[0] == 2) rejWater++;
            else rejAreaWater++;
        }
        plugin.log("Zone generation: terrain rejected — " + rejOcean + " ocean biome, "
                + rejWater + " surface water, " + rejAreaWater + " area water coverage");

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

    /** rejection[0]: 1=ocean biome, 2=surface water/lava, 3=area water coverage */
    private Location findSuitableSurfaceLocation(World world, int x, int z, int[] rejection) {
        int y = world.getHighestBlockYAt(x, z);
        Location location = new Location(world, x, y, z);

        Biome biome = world.getBiome(location);
        if (isOceanBiome(biome)) {
            rejection[0] = 1;
            return null;
        }

        Block surfaceBlock = world.getBlockAt(x, y, z);
        Block aboveBlock = world.getBlockAt(x, y + 1, z);

        if (surfaceBlock.getType() == Material.WATER || surfaceBlock.getType() == Material.LAVA
                || aboveBlock.getType() == Material.WATER || aboveBlock.getType() == Material.LAVA) {
            rejection[0] = 2;
            return null;
        }

        if (!isAreaSuitableForCombat(world, x, y, z)) {
            rejection[0] = 3;
            return null;
        }

        return location;
    }

    /**
     * Check if a biome is an ocean biome
     */
    private boolean isOceanBiome(Biome biome) {
        String key = biome.translationKey();
        return key.contains("ocean") || key.contains("deep") || key.contains("river");
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


}
