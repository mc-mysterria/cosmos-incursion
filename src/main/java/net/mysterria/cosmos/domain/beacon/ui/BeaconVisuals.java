package net.mysterria.cosmos.domain.beacon.ui;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.beacon.SpiritBeacon;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages physical beacon blocks at beacon locations
 * Creates beacon block with colored glass ring
 * Stores and restores original blocks
 */
public class BeaconVisuals {

    private final CosmosIncursion plugin;
    private final SpiritBeacon beacon;
    private final Map<Location, BlockData> originalBlocks;
    private boolean beaconCreated;

    public BeaconVisuals(CosmosIncursion plugin, SpiritBeacon beacon) {
        this.plugin = plugin;
        this.beacon = beacon;
        this.originalBlocks = new HashMap<>();
        this.beaconCreated = false;
    }

    /**
     * Create physical beacon structure at beacon location
     */
    public void createBeacon() {
        if (beaconCreated) {
            return;
        }

        Location center = beacon.location();
        World world = center.getWorld();
        if (world == null) {
            plugin.log("Cannot create beacon - world is null for beacon: " + beacon.id());
            return;
        }

        List<Location> blocksToPlace = new ArrayList<>();

        // Level 0: Glass base (radius 1)
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;  // Skip center (will be beacon base)
                Location loc = center.clone().add(x, 0, z);
                blocksToPlace.add(loc);
            }
        }

        // Center at level 0: Base block for beacon
        blocksToPlace.add(center.clone());

        // Level 1: Beacon block at center
        Location beaconBlockLoc = center.clone().add(0, 1, 0);
        blocksToPlace.add(beaconBlockLoc);

        // Level 2: Colored glass ring (radius 2)
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) == 2 || Math.abs(z) == 2) {  // Only outer ring
                    Location loc = center.clone().add(x, 2, z);
                    blocksToPlace.add(loc);
                }
            }
        }

        // Store original blocks
        for (Location loc : blocksToPlace) {
            Block block = world.getBlockAt(loc);
            originalBlocks.put(loc.clone(), block.getBlockData().clone());
        }

        // Place beacon structure
        placeBeaconStructure(center, Material.WHITE_STAINED_GLASS);

        beaconCreated = true;
        plugin.log("Created physical beacon at " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ());
    }

    /**
     * Place beacon block structure
     */
    private void placeBeaconStructure(Location center, Material glassColor) {
        World world = center.getWorld();
        if (world == null) return;

        // Level 0: Glass base
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) {
                    // Center: Iron block base for beacon
                    world.getBlockAt(center).setType(Material.IRON_BLOCK);
                } else {
                    world.getBlockAt(center.clone().add(x, 0, z)).setType(glassColor);
                }
            }
        }

        // Level 1: Beacon block
        world.getBlockAt(center.clone().add(0, 1, 0)).setType(Material.BEACON);

        // Level 2: Colored glass ring
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) == 2 || Math.abs(z) == 2) {
                    world.getBlockAt(center.clone().add(x, 2, z)).setType(glassColor);
                }
            }
        }
    }

    /**
     * Update beacon colors based on town ownership
     * @param townId The owning town ID (0 for neutral)
     */
    public void updateColors(int townId) {
        if (!beaconCreated) {
            return;
        }

        Material glassColor = getGlassColorForTown(townId);
        Location center = beacon.location();
        World world = center.getWorld();
        if (world == null) return;

        // Update level 0 glass
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {  // Skip center
                    world.getBlockAt(center.clone().add(x, 0, z)).setType(glassColor);
                }
            }
        }

        // Update level 2 glass ring
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.abs(x) == 2 || Math.abs(z) == 2) {
                    world.getBlockAt(center.clone().add(x, 2, z)).setType(glassColor);
                }
            }
        }
    }

    /**
     * Remove beacon and restore original blocks
     */
    public void removeBeacon() {
        if (!beaconCreated) {
            return;
        }

        World world = beacon.location().getWorld();
        if (world == null) {
            plugin.log("Cannot remove beacon - world is null for beacon: " + beacon.id());
            return;
        }

        // Restore all original blocks
        for (Map.Entry<Location, BlockData> entry : originalBlocks.entrySet()) {
            Location loc = entry.getKey();
            BlockData originalData = entry.getValue();

            Block block = world.getBlockAt(loc);
            block.setBlockData(originalData, false);  // false = no physics update
        }

        originalBlocks.clear();
        beaconCreated = false;

        plugin.log("Removed physical beacon at " + beacon.location().getBlockX() + "," +
                   beacon.location().getBlockY() + "," + beacon.location().getBlockZ());
    }

    /**
     * Get glass color material for a town ID
     */
    private Material getGlassColorForTown(int townId) {
        if (townId == 0) {
            return Material.WHITE_STAINED_GLASS;  // Neutral
        }

        Material[] glasses = {
                Material.RED_STAINED_GLASS,
                Material.BLUE_STAINED_GLASS,
                Material.GREEN_STAINED_GLASS,
                Material.YELLOW_STAINED_GLASS,
                Material.PURPLE_STAINED_GLASS,
                Material.CYAN_STAINED_GLASS,
                Material.ORANGE_STAINED_GLASS,
                Material.LIME_STAINED_GLASS,
                Material.PINK_STAINED_GLASS,
                Material.LIGHT_BLUE_STAINED_GLASS,
                Material.MAGENTA_STAINED_GLASS,
                Material.LIGHT_GRAY_STAINED_GLASS,
                Material.BROWN_STAINED_GLASS,
                Material.GRAY_STAINED_GLASS,
                Material.BLACK_STAINED_GLASS
        };

        return glasses[Math.abs(townId % glasses.length)];
    }
}
