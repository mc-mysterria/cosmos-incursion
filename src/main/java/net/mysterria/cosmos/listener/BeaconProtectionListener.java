package net.mysterria.cosmos.listener;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.beacon.SpiritBeacon;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Protects physical beacon structures from being broken or modified
 */
public class BeaconProtectionListener implements Listener {

    private final CosmosIncursion plugin;

    public BeaconProtectionListener(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Check if block is part of a beacon structure
        if (isBeaconBlock(block)) {
            event.setCancelled(true);
            // No message - silent cancellation to avoid spam
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();

        // Check if trying to place a block where a beacon structure exists
        if (isBeaconLocationOccupied(block.getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * Check if a block is part of any active beacon structure
     */
    private boolean isBeaconBlock(Block block) {
        Material type = block.getType();

        // Only check beacon-related blocks
        if (type != Material.BEACON && type != Material.IRON_BLOCK && !isStainedGlass(type)) {
            return false;
        }

        Location blockLoc = block.getLocation();

        // Check all active beacons
        for (SpiritBeacon beacon : plugin.getBeaconManager().getAllBeacons()) {
            if (isPartOfBeaconStructure(blockLoc, beacon.location())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if a location is occupied by a beacon structure
     */
    private boolean isBeaconLocationOccupied(Location loc) {
        for (SpiritBeacon beacon : plugin.getBeaconManager().getAllBeacons()) {
            if (isPartOfBeaconStructure(loc, beacon.location())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a location is part of a beacon structure
     */
    private boolean isPartOfBeaconStructure(Location blockLoc, Location beaconCenter) {
        if (!blockLoc.getWorld().equals(beaconCenter.getWorld())) {
            return false;
        }

        int dx = blockLoc.getBlockX() - beaconCenter.getBlockX();
        int dy = blockLoc.getBlockY() - beaconCenter.getBlockY();
        int dz = blockLoc.getBlockZ() - beaconCenter.getBlockZ();

        // Level 0: Glass base (radius 1) + iron block center
        if (dy == 0) {
            return Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
        }

        // Level 1: Beacon block at center
        if (dy == 1) {
            return dx == 0 && dz == 0;
        }

        // Level 2: Colored glass ring (radius 2, outer ring only)
        if (dy == 2) {
            return (Math.abs(dx) == 2 || Math.abs(dz) == 2) &&
                   Math.abs(dx) <= 2 && Math.abs(dz) <= 2;
        }

        return false;
    }

    /**
     * Check if material is any stained glass
     */
    private boolean isStainedGlass(Material type) {
        return type.name().contains("STAINED_GLASS") && !type.name().contains("PANE");
    }
}
