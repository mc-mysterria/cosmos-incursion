package net.mysterria.cosmos.integration;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.beacon.SpiritBeacon;
import net.mysterria.cosmos.domain.zone.IncursionZone;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Handles BlueMap API integration for visualizing Incursion zones
 */
public class BlueMapIntegration {

    private final CosmosIncursion plugin;
    private final Map<UUID, Marker> zoneMarkers;
    private final Map<UUID, Marker> corruptedMonsterMarkers;
    private final Map<String, Marker> beaconMarkers;
    private BlueMapAPI api;
    private MarkerSet markerSet;

    public BlueMapIntegration(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.zoneMarkers = new HashMap<>();
        this.corruptedMonsterMarkers = new HashMap<>();
        this.beaconMarkers = new HashMap<>();
    }

    /**
     * Initialize BlueMap API connection
     */
    public void initialize() {
        BlueMapAPI.onEnable(api -> {
            this.api = api;
            plugin.log("BlueMap API integration enabled");
            createMarkerSet();
        });

        BlueMapAPI.onDisable(api -> {
            this.api = null;
            plugin.log("BlueMap API integration disabled");
        });
    }

    /**
     * Create or get the marker set for Cosmos Incursion
     */
    private void createMarkerSet() {
        if (api == null) {
            plugin.log("Warning: BlueMap API not available for marker set creation");
            return;
        }

        try {
            // Create a new marker set
            markerSet = MarkerSet.builder()
                    .label("Cosmos Incursion")
                    .toggleable(true)
                    .defaultHidden(false)
                    .build();

            // Add marker set to all maps
            for (BlueMapWorld world : api.getWorlds()) {
                for (BlueMapMap map : world.getMaps()) {
                    map.getMarkerSets().put("cosmos_incursion", markerSet);
                }
            }

            plugin.log("Created BlueMap marker set: Cosmos Incursion");
        } catch (Exception e) {
            plugin.log("Error creating BlueMap marker set: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Create a circular zone marker on the map
     */
    public void createZoneMarker(IncursionZone incursionZone) {
        if (api == null || markerSet == null) {
            plugin.log("Warning: BlueMap API not available, cannot create zone marker");
            return;
        }

        try {
            // Get the world
            Optional<BlueMapWorld> worldOpt = api.getWorld(incursionZone.getCenter().getWorld().getUID());
            if (worldOpt.isEmpty()) {
                plugin.log("Warning: BlueMap world not found for zone " + incursionZone.getName());
                return;
            }

            // Create circle shape
            List<Vector2d> circlePoints = createCirclePoints(
                    incursionZone.getCenter().getX(),
                    incursionZone.getCenter().getZ(),
                    incursionZone.getRadius(),
                    64  // Number of segments for smooth circle
            );

            Shape shape = new Shape(circlePoints);

            // Create shape marker
            ShapeMarker marker = ShapeMarker.builder()
                    .label("Cosmos Incursion (Active) - " + incursionZone.getName())
                    .shape(shape, (float) incursionZone.getCenter().getY())
                    .fillColor(new Color(255, 0, 0, 0.15f))      // Very transparent red fill (5% opacity)
                    .lineColor(new Color(255, 0, 0, 0.6f))     // Semi-transparent red border (60% opacity)
                    .lineWidth(3)
                    .depthTestEnabled(false)  // Show through terrain
                    .build();

            String markerId = "zone_" + incursionZone.getId();
            markerSet.put(markerId, marker);
            zoneMarkers.put(incursionZone.getId(), marker);

            plugin.log("Created BlueMap marker for zone: " + incursionZone.getName());
        } catch (Exception e) {
            plugin.log("Error creating zone marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Remove a zone marker from the map
     */
    public void removeZoneMarker(IncursionZone incursionZone) {
        if (markerSet == null) {
            return;
        }

        try {
            String markerId = "zone_" + incursionZone.getId();
            markerSet.remove(markerId);
            zoneMarkers.remove(incursionZone.getId());

            plugin.log("Removed BlueMap marker for zone: " + incursionZone.getName());
        } catch (Exception e) {
            plugin.log("Error removing zone marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Remove all zone markers
     */
    public void removeAllZoneMarkers() {
        if (markerSet == null) {
            return;
        }

        try {
            // Remove all zone markers
            for (UUID zoneId : new HashSet<>(zoneMarkers.keySet())) {
                String markerId = "zone_" + zoneId;
                markerSet.remove(markerId);
            }
            zoneMarkers.clear();

            plugin.log("Removed all BlueMap zone markers");
        } catch (Exception e) {
            plugin.log("Error removing all zone markers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Mark a player as a Corrupted Monster on the map
     */
    public void markCorruptedMonster(Player player) {
        if (api == null || markerSet == null) {
            plugin.log("Warning: BlueMap API not available, cannot mark corrupted monster");
            return;
        }

        try {
            // Create POI marker at player location
            POIMarker marker = POIMarker.builder()
                    .label("⚠ Corrupted Monster: " + player.getName())
                    .position(
                            player.getLocation().getX(),
                            player.getLocation().getY(),
                            player.getLocation().getZ()
                    )
                    .icon("assets/poi.svg", 0, 0)  // Default BlueMap POI icon
                    .build();

            // Add marker to the set
            String markerId = "corrupted_" + player.getUniqueId();
            markerSet.put(markerId, marker);
            corruptedMonsterMarkers.put(player.getUniqueId(), marker);

            plugin.log("Marked player as Corrupted Monster on BlueMap: " + player.getName());
        } catch (Exception e) {
            plugin.log("Error marking corrupted monster: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Remove Corrupted Monster marker for a player
     */
    public void removeCorruptedMonsterMarker(Player player) {
        removeCorruptedMonsterMarker(player.getUniqueId());
    }

    /**
     * Remove Corrupted Monster marker by UUID
     */
    public void removeCorruptedMonsterMarker(UUID playerId) {
        if (markerSet == null) {
            return;
        }

        try {
            String markerId = "corrupted_" + playerId;
            markerSet.remove(markerId);
            corruptedMonsterMarkers.remove(playerId);

            plugin.log("Removed Corrupted Monster marker for player: " + playerId);
        } catch (Exception e) {
            plugin.log("Error removing corrupted monster marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Create points for a circle shape
     */
    private List<Vector2d> createCirclePoints(double centerX, double centerZ, double radius, int segments) {
        List<Vector2d> points = new ArrayList<>();

        for (int i = 0; i < segments; i++) {
            double angle = (2 * Math.PI * i) / segments;
            double x = centerX + radius * Math.cos(angle);
            double z = centerZ + radius * Math.sin(angle);
            points.add(new Vector2d(x, z));
        }

        return points;
    }

    /**
     * Create a beacon marker on the map as a blue circle
     */
    public void createBeaconMarker(SpiritBeacon beacon, double captureRadius) {
        if (api == null || markerSet == null) {
            plugin.log("Warning: BlueMap API not available, cannot create beacon marker");
            return;
        }

        try {
            // Create circle shape for beacon capture radius
            List<Vector2d> circlePoints = createCirclePoints(
                    beacon.location().getX(),
                    beacon.location().getZ(),
                    captureRadius,
                    32  // Number of segments for smooth circle
            );

            Shape shape = new Shape(circlePoints);

            // Create shape marker with blue color
            ShapeMarker marker = ShapeMarker.builder()
                    .label("⚡ " + beacon.name())
                    .shape(shape, (float) beacon.location().getY())
                    .fillColor(new Color(0, 100, 255, 0.25f))      // Blue fill (15% opacity)
                    .lineColor(new Color(0, 150, 255, 0.8f))       // Bright blue border (80% opacity)
                    .lineWidth(2)
                    .depthTestEnabled(false)  // Show through terrain
                    .build();

            // Add marker to the set
            String markerId = "beacon_" + beacon.id();
            markerSet.put(markerId, marker);
            beaconMarkers.put(beacon.id(), marker);

            plugin.log("Created BlueMap marker for beacon: " + beacon.name());
        } catch (Exception e) {
            plugin.log("Error creating beacon marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Remove a beacon marker from the map
     */
    public void removeBeaconMarker(SpiritBeacon beacon) {
        removeBeaconMarker(beacon.id());
    }

    /**
     * Remove a beacon marker by ID
     */
    public void removeBeaconMarker(String beaconId) {
        if (markerSet == null) {
            return;
        }

        try {
            String markerId = "beacon_" + beaconId;
            markerSet.remove(markerId);
            beaconMarkers.remove(beaconId);

            plugin.log("Removed BlueMap marker for beacon: " + beaconId);
        } catch (Exception e) {
            plugin.log("Error removing beacon marker: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Remove all beacon markers
     */
    public void removeAllBeaconMarkers() {
        if (markerSet == null) {
            return;
        }

        try {
            // Remove all beacon markers
            for (String beaconId : new HashSet<>(beaconMarkers.keySet())) {
                String markerId = "beacon_" + beaconId;
                markerSet.remove(markerId);
            }
            beaconMarkers.clear();

            plugin.log("Removed all BlueMap beacon markers");
        } catch (Exception e) {
            plugin.log("Error removing all beacon markers: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check if BlueMap API is available
     */
    public boolean isAvailable() {
        return api != null;
    }

}
