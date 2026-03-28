package net.mysterria.cosmos.integration.map;

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
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.beacon.SpiritBeacon;
import net.mysterria.cosmos.domain.permanent.ExtractionPoint;
import net.mysterria.cosmos.domain.permanent.PermanentZone;
import net.mysterria.cosmos.domain.permanent.PointOfInterest;
import net.mysterria.cosmos.domain.permanent.ResourceType;
import net.mysterria.cosmos.domain.zone.IncursionZone;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Handles BlueMap API integration for visualizing Incursion zones
 */
public class BlueMapIntegration implements MapIntegration {

    private final CosmosIncursion plugin;
    private final Map<UUID, Marker> zoneMarkers;
    private final Map<UUID, Marker> corruptedMonsterMarkers;
    private final Map<String, Marker> beaconMarkers;
    private final Map<UUID, Marker> permanentZoneMarkers;
    // zone UUID → set of marker IDs (PoI / extraction points) for bulk removal
    private final Map<UUID, Set<String>> zonePoiMarkerIds;
    private final Map<UUID, Set<String>> zoneExtractionMarkerIds;
    private BlueMapAPI api;
    private MarkerSet markerSet;

    public BlueMapIntegration(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.zoneMarkers = new HashMap<>();
        this.corruptedMonsterMarkers = new HashMap<>();
        this.beaconMarkers = new HashMap<>();
        this.permanentZoneMarkers = new HashMap<>();
        this.zonePoiMarkerIds = new HashMap<>();
        this.zoneExtractionMarkerIds = new HashMap<>();
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

            // Derive marker color from zone tier
            CosmosConfig.ZoneTierConfig tierCfg = plugin.getConfigManager().getConfig()
                    .getTierConfigs().get(incursionZone.getTier());
            int r = tierCfg.particleR(), g = tierCfg.particleG(), b = tierCfg.particleB();
            String tierLabel = incursionZone.getTier().name();

            // Create shape marker
            ShapeMarker marker = ShapeMarker.builder()
                    .label("[" + tierLabel + "] Cosmos Incursion - " + incursionZone.getName())
                    .shape(shape, (float) incursionZone.getCenter().getY())
                    .fillColor(new Color(r, g, b, 0.15f))
                    .lineColor(new Color(r, g, b, 0.6f))
                    .lineWidth(3)
                    .depthTestEnabled(false)
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

    // --- Permanent zone markers ---

    @Override
    public void createPermanentZoneMarker(PermanentZone zone) {
        if (api == null || markerSet == null) return;
        try {
            List<Vector2d> points = new ArrayList<>();
            for (org.bukkit.Location v : zone.getVertices()) {
                points.add(new Vector2d(v.getX(), v.getZ()));
            }
            if (points.size() < 3) return;

            double avgY = zone.getVertices().stream().mapToDouble(org.bukkit.Location::getY).average().orElse(64);
            Shape shape = new Shape(points);

            ShapeMarker marker = ShapeMarker.builder()
                    .label("⚔ Permanent Zone: " + zone.getName())
                    .shape(shape, (float) avgY)
                    .fillColor(new Color(220, 120, 0, 0.18f))
                    .lineColor(new Color(255, 160, 0, 0.78f))
                    .lineWidth(3)
                    .depthTestEnabled(false)
                    .build();

            String markerId = "pzone_" + zone.getId();
            markerSet.put(markerId, marker);
            permanentZoneMarkers.put(zone.getId(), marker);

            plugin.log("BlueMap: created permanent zone marker for " + zone.getName());
        } catch (Exception e) {
            plugin.log("BlueMap: error creating permanent zone marker: " + e.getMessage());
        }
    }

    @Override
    public void removePermanentZoneMarker(UUID zoneId) {
        if (markerSet == null) return;
        markerSet.remove("pzone_" + zoneId);
        permanentZoneMarkers.remove(zoneId);
        removeZoneSubMarkers(zoneId, zonePoiMarkerIds);
        removeZoneSubMarkers(zoneId, zoneExtractionMarkerIds);
    }

    @Override
    public void removeAllPermanentZoneMarkers() {
        if (markerSet == null) return;
        for (UUID id : new HashSet<>(permanentZoneMarkers.keySet())) {
            markerSet.remove("pzone_" + id);
        }
        permanentZoneMarkers.clear();
        for (UUID id : new HashSet<>(zonePoiMarkerIds.keySet())) {
            removeZoneSubMarkers(id, zonePoiMarkerIds);
        }
        for (UUID id : new HashSet<>(zoneExtractionMarkerIds.keySet())) {
            removeZoneSubMarkers(id, zoneExtractionMarkerIds);
        }
    }

    @Override
    public void syncPermanentZonePoIs(PermanentZone zone, List<PointOfInterest> pois) {
        if (api == null || markerSet == null) return;
        try {
            removeZoneSubMarkers(zone.getId(), zonePoiMarkerIds);
            Set<String> newIds = new HashSet<>();
            for (PointOfInterest poi : pois) {
                if (!poi.isActive()) continue;
                List<Vector2d> pts = createCirclePoints(
                        poi.getLocation().getX(), poi.getLocation().getZ(),
                        poi.getExtractionRadius(), 24);
                ShapeMarker marker = ShapeMarker.builder()
                        .label(poiLabel(poi.getResourceType()) + " — " + zone.getName())
                        .shape(new Shape(pts), (float) poi.getLocation().getY())
                        .fillColor(poiFillColor(poi.getResourceType()))
                        .lineColor(poiLineColor(poi.getResourceType()))
                        .lineWidth(2)
                        .depthTestEnabled(false)
                        .build();
                String id = "poi_" + poi.getId();
                markerSet.put(id, marker);
                newIds.add(id);
            }
            zonePoiMarkerIds.put(zone.getId(), newIds);
        } catch (Exception e) {
            plugin.log("BlueMap: error syncing PoI markers: " + e.getMessage());
        }
    }

    @Override
    public void syncPermanentZoneExtractionPoints(PermanentZone zone, List<ExtractionPoint> eps) {
        if (api == null || markerSet == null) return;
        try {
            removeZoneSubMarkers(zone.getId(), zoneExtractionMarkerIds);
            Set<String> newIds = new HashSet<>();
            for (ExtractionPoint ep : eps) {
                if (!ep.isActive()) continue;
                List<Vector2d> pts = createCirclePoints(
                        ep.getLocation().getX(), ep.getLocation().getZ(),
                        ep.getCaptureRadius(), 24);
                ShapeMarker marker = ShapeMarker.builder()
                        .label("⬆ Extraction Point — " + zone.getName())
                        .shape(new Shape(pts), (float) ep.getLocation().getY())
                        .fillColor(new Color(0, 200, 220, 0.20f))
                        .lineColor(new Color(0, 220, 255, 0.78f))
                        .lineWidth(2)
                        .depthTestEnabled(false)
                        .build();
                String id = "ep_" + ep.getId();
                markerSet.put(id, marker);
                newIds.add(id);
            }
            zoneExtractionMarkerIds.put(zone.getId(), newIds);
        } catch (Exception e) {
            plugin.log("BlueMap: error syncing extraction point markers: " + e.getMessage());
        }
    }

    private void removeZoneSubMarkers(UUID zoneId, Map<UUID, Set<String>> idMap) {
        Set<String> ids = idMap.remove(zoneId);
        if (ids != null && markerSet != null) {
            for (String id : ids) markerSet.remove(id);
        }
    }

    private static Color poiFillColor(ResourceType type) {
        return switch (type) {
            case GOLD -> new Color(255, 200, 0, 0.24f);
            case SILVER -> new Color(180, 180, 210, 0.24f);
            case GEMS -> new Color(0, 200, 100, 0.24f);
        };
    }

    private static Color poiLineColor(ResourceType type) {
        return switch (type) {
            case GOLD -> new Color(255, 215, 0, 0.86f);
            case SILVER -> new Color(192, 192, 220, 0.86f);
            case GEMS -> new Color(0, 201, 87, 0.86f);
        };
    }

    private static String poiLabel(ResourceType type) {
        return switch (type) {
            case GOLD -> "⬛ Gold";
            case SILVER -> "◈ Silver";
            case GEMS -> "◆ Gems";
        };
    }

    /**
     * Check if BlueMap API is available
     */
    public boolean isAvailable() {
        return api != null;
    }

}
