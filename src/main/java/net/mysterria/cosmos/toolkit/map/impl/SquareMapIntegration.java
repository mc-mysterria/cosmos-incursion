package net.mysterria.cosmos.toolkit.map.impl;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.beacon.model.SpiritBeacon;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionPoint;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PointOfInterest;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.toolkit.map.MapIntegration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jpenilla.squaremap.api.*;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * squaremap integration for visualizing Incursion zones, beacons, and player markers.
 * Uses a single shared SimpleLayerProvider registered on all enabled worlds.
 */
public class SquareMapIntegration implements MapIntegration {

    private static final Key LAYER_KEY = Key.of("cosmos_incursion");

    private final CosmosIncursion plugin;
    private Squaremap api;
    private SimpleLayerProvider layerProvider;

    // Track keys so we can remove markers later
    private final Map<UUID, Key> zoneMarkerKeys = new HashMap<>();
    private final Map<UUID, Key> corruptedMonsterKeys = new HashMap<>();
    private final Map<String, Key> beaconMarkerKeys = new HashMap<>();
    private final Map<UUID, Key> permanentZoneMarkerKeys = new HashMap<>();
    // zone UUID → set of PoI/EP marker keys currently on the map
    private final Map<UUID, Set<Key>> zonePoiKeys = new HashMap<>();
    private final Map<UUID, Set<Key>> zoneExtractionKeys = new HashMap<>();
    // resource type → squaremap icon key (populated if icons are configured)
    private final Map<ResourceType, Key> registeredPoiIconKeys = new EnumMap<>(ResourceType.class);

    public SquareMapIntegration(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @Override
    public void initialize() {
        try {
            this.api = SquaremapProvider.get();

            this.layerProvider = SimpleLayerProvider.builder("Cosmos Incursion")
                    .showControls(true)
                    .defaultHidden(false)
                    .build();

            // Register the layer on every world squaremap is tracking
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                api.getWorldIfEnabled(BukkitAdapter.worldIdentifier(world))
                        .ifPresent(mapWorld -> {
                            try {
                                mapWorld.layerRegistry().register(LAYER_KEY, layerProvider);
                            } catch (Exception e) {
                                // Layer may already be registered (e.g. after reload)
                            }
                        });
            }

            registerPoiIcons();
            plugin.log("squaremap integration enabled");
        } catch (Exception e) {
            plugin.log("squaremap not available: " + e.getMessage());
        }
    }

    private void registerPoiIcons() {
        Map<ResourceType, String> paths = plugin.getConfigLoader().getConfig().getPoiIconPaths();
        int size = plugin.getConfigLoader().getConfig().getPoiIconSize();
        for (ResourceType type : ResourceType.values()) {
            String path = paths.get(type);
            if (path == null || path.isBlank()) continue;
            File iconFile = new File(plugin.getDataFolder(), path);
            if (!iconFile.exists()) {
                plugin.log("squaremap: PoI icon not found: " + iconFile.getPath());
                continue;
            }
            try {
                BufferedImage img = ImageIO.read(iconFile);
                Key iconKey = Key.of("cosmos_poi_" + type.name().toLowerCase());
                api.iconRegistry().register(iconKey, img);
                registeredPoiIconKeys.put(type, iconKey);
                plugin.log("squaremap: registered PoI icon for " + type.name());
            } catch (Exception e) {
                plugin.log("squaremap: failed to register PoI icon for " + type.name() + ": " + e.getMessage());
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return api != null && layerProvider != null;
    }

    // --- Zone markers ---

    @Override
    public void createZoneMarker(IncursionZone zone) {
        if (!isAvailable()) return;
        try {
            Color fill = incursionTierFillColor(zone.getTier());
            Color stroke = incursionTierStrokeColor(zone.getTier());

            MarkerOptions options = MarkerOptions.builder()
                    .fillColor(fill)
                    .strokeColor(stroke)
                    .strokeWeight(3)
                    .clickTooltip("[" + zone.getTier().name() + "] " + formatZoneName(zone.getName())
                            + " | " + incursionTierDescription(zone.getTier()))
                    .build();

            var circle = Marker.circle(
                    Point.of(zone.getCenter().getX(), zone.getCenter().getZ()),
                    zone.getRadius()
            ).markerOptions(options);

            Key key = zoneKey(zone.getId());
            layerProvider.addMarker(key, circle);
            zoneMarkerKeys.put(zone.getId(), key);

            plugin.log("squaremap: created zone marker for " + zone.getName());
        } catch (Exception e) {
            plugin.log("squaremap: error creating zone marker: " + e.getMessage());
        }
    }

    @Override
    public void removeZoneMarker(IncursionZone zone) {
        if (!isAvailable()) return;
        Key key = zoneMarkerKeys.remove(zone.getId());
        if (key != null) {
            try {
                layerProvider.removeMarker(key);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void removeAllZoneMarkers() {
        if (!isAvailable()) return;
        for (Key key : new HashSet<>(zoneMarkerKeys.values())) {
            try {
                layerProvider.removeMarker(key);
            } catch (Exception ignored) {
            }
        }
        zoneMarkerKeys.clear();
    }

    // --- Beacon markers ---

    @Override
    public void createBeaconMarker(SpiritBeacon beacon, double captureRadius) {
        if (!isAvailable()) return;
        try {
            Color fill = new Color(0, 100, 255, 64);   // blue ~25%
            Color stroke = new Color(0, 150, 255, 204);  // bright blue ~80%

            MarkerOptions options = MarkerOptions.builder()
                    .fillColor(fill)
                    .strokeColor(stroke)
                    .strokeWeight(2)
                    .clickTooltip("⚡ " + beacon.name())
                    .build();

            var circle = Marker.circle(
                    Point.of(beacon.location().getX(), beacon.location().getZ()),
                    captureRadius
            ).markerOptions(options);

            Key key = beaconKey(beacon.id());
            layerProvider.addMarker(key, circle);
            beaconMarkerKeys.put(beacon.id(), key);

            plugin.log("squaremap: created beacon marker for " + beacon.name());
        } catch (Exception e) {
            plugin.log("squaremap: error creating beacon marker: " + e.getMessage());
        }
    }

    @Override
    public void removeBeaconMarker(SpiritBeacon beacon) {
        removeBeaconMarker(beacon.id());
    }

    @Override
    public void removeBeaconMarker(String beaconId) {
        if (!isAvailable()) return;
        Key key = beaconMarkerKeys.remove(beaconId);
        if (key != null) {
            try {
                layerProvider.removeMarker(key);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void removeAllBeaconMarkers() {
        if (!isAvailable()) return;
        for (Key key : new HashSet<>(beaconMarkerKeys.values())) {
            try {
                layerProvider.removeMarker(key);
            } catch (Exception ignored) {
            }
        }
        beaconMarkerKeys.clear();
    }

    // --- Player markers ---

    @Override
    public void markCorruptedMonster(Player player) {
        if (!isAvailable()) return;
        try {
            Color fill = new Color(180, 0, 0, 200);
            Color stroke = new Color(255, 0, 0, 255);

            MarkerOptions options = MarkerOptions.builder()
                    .fillColor(fill)
                    .strokeColor(stroke)
                    .strokeWeight(2)
                    .clickTooltip("⚠ Corrupted Monster: " + player.getName())
                    .build();

            // Small 5-block circle as a POI stand-in
            var circle = Marker.circle(
                    Point.of(player.getLocation().getX(), player.getLocation().getZ()),
                    5.0
            ).markerOptions(options);

            Key key = corruptedKey(player.getUniqueId());
            layerProvider.addMarker(key, circle);
            corruptedMonsterKeys.put(player.getUniqueId(), key);

            plugin.log("squaremap: marked " + player.getName() + " as Corrupted Monster");
        } catch (Exception e) {
            plugin.log("squaremap: error marking corrupted monster: " + e.getMessage());
        }
    }

    @Override
    public void removeCorruptedMonsterMarker(Player player) {
        removeCorruptedMonsterMarker(player.getUniqueId());
    }

    @Override
    public void removeCorruptedMonsterMarker(UUID playerId) {
        if (!isAvailable()) return;
        Key key = corruptedMonsterKeys.remove(playerId);
        if (key != null) {
            try {
                layerProvider.removeMarker(key);
            } catch (Exception ignored) {
            }
        }
    }

    // --- Permanent zone markers ---

    @Override
    public void createPermanentZoneMarker(PermanentZone zone) {
        if (!isAvailable()) return;
        try {
            List<Point> points = new ArrayList<>();
            for (org.bukkit.Location v : zone.getVertices()) {
                points.add(Point.of(v.getX(), v.getZ()));
            }
            if (points.size() < 3) return;

            Color fill = exclusionTierFillColor(zone.getTier());
            Color stroke = exclusionTierStrokeColor(zone.getTier());

            MarkerOptions options = MarkerOptions.builder()
                    .fillColor(fill)
                    .strokeColor(stroke)
                    .strokeWeight(3)
                    .clickTooltip("⚔ " + formatZoneName(zone.getName())
                            + " [" + zone.getTier().name() + "] | " + exclusionTierDescription(zone.getTier()))
                    .build();

            var polygon = Marker.polygon(points).markerOptions(options);
            Key key = permanentZoneKey(zone.getId());
            layerProvider.addMarker(key, polygon);
            permanentZoneMarkerKeys.put(zone.getId(), key);

            plugin.log("squaremap: created permanent zone marker for " + zone.getName());
        } catch (Exception e) {
            plugin.log("squaremap: error creating permanent zone marker: " + e.getMessage());
        }
    }

    @Override
    public void removePermanentZoneMarker(UUID zoneId) {
        if (!isAvailable()) return;
        Key key = permanentZoneMarkerKeys.remove(zoneId);
        if (key != null) {
            try { layerProvider.removeMarker(key); } catch (Exception ignored) {}
        }
        // Also remove PoI and EP markers for this zone
        removeZoneSubMarkers(zoneId, zonePoiKeys);
        removeZoneSubMarkers(zoneId, zoneExtractionKeys);
    }

    @Override
    public void removeAllPermanentZoneMarkers() {
        if (!isAvailable()) return;
        for (Key key : new HashSet<>(permanentZoneMarkerKeys.values())) {
            try { layerProvider.removeMarker(key); } catch (Exception ignored) {}
        }
        permanentZoneMarkerKeys.clear();
        for (UUID zoneId : new HashSet<>(zonePoiKeys.keySet())) {
            removeZoneSubMarkers(zoneId, zonePoiKeys);
        }
        for (UUID zoneId : new HashSet<>(zoneExtractionKeys.keySet())) {
            removeZoneSubMarkers(zoneId, zoneExtractionKeys);
        }
    }

    @Override
    public void syncPermanentZonePoIs(PermanentZone zone, List<PointOfInterest> pois) {
        if (!isAvailable()) return;
        try {
            removeZoneSubMarkers(zone.getId(), zonePoiKeys);
            Set<Key> newKeys = new HashSet<>();
            for (PointOfInterest poi : pois) {
                if (!poi.isActive()) continue;
                String tooltip = poiLabel(poi.getResourceType()) + " — " + formatZoneName(zone.getName());
                Color fill = poiFillColor(poi.getResourceType());
                Color stroke = poiStrokeColor(poi.getResourceType());
                MarkerOptions opts = MarkerOptions.builder()
                        .fillColor(fill)
                        .strokeColor(stroke)
                        .strokeWeight(2)
                        .clickTooltip(tooltip)
                        .build();
                var circle = Marker.circle(
                        Point.of(poi.getLocation().getX(), poi.getLocation().getZ()),
                        poi.getExtractionRadius()
                ).markerOptions(opts);
                Key key = poiKey(poi.getId());
                layerProvider.addMarker(key, circle);
                newKeys.add(key);

                // Add icon marker at PoI center if a custom icon is configured for this resource type
                Key iconKey = registeredPoiIconKeys.get(poi.getResourceType());
                if (iconKey != null) {
                    int iconSize = plugin.getConfigLoader().getConfig().getPoiIconSize();
                    MarkerOptions iconOpts = MarkerOptions.builder().clickTooltip(tooltip).build();
                    var iconMarker = Marker.icon(
                            Point.of(poi.getLocation().getX(), poi.getLocation().getZ()),
                            iconKey, iconSize, iconSize
                    ).markerOptions(iconOpts);
                    Key iKey = poiIconKey(poi.getId());
                    layerProvider.addMarker(iKey, iconMarker);
                    newKeys.add(iKey);
                }
            }
            zonePoiKeys.put(zone.getId(), newKeys);
        } catch (Exception e) {
            plugin.log("squaremap: error syncing PoI markers: " + e.getMessage());
        }
    }

    @Override
    public void syncPermanentZoneExtractionPoints(PermanentZone zone, List<ExtractionPoint> eps) {
        if (!isAvailable()) return;
        try {
            removeZoneSubMarkers(zone.getId(), zoneExtractionKeys);
            Set<Key> newKeys = new HashSet<>();
            for (ExtractionPoint ep : eps) {
                if (!ep.isActive()) continue;
                Color fill = new Color(0, 200, 220, 50);
                Color stroke = new Color(0, 220, 255, 200);
                MarkerOptions opts = MarkerOptions.builder()
                        .fillColor(fill)
                        .strokeColor(stroke)
                        .strokeWeight(2)
                        .clickTooltip("⬆ Extraction Point — " + formatZoneName(zone.getName()))
                        .build();
                var circle = Marker.circle(
                        Point.of(ep.getLocation().getX(), ep.getLocation().getZ()),
                        ep.getCaptureRadius()
                ).markerOptions(opts);
                Key key = extractionKey(ep.getId());
                layerProvider.addMarker(key, circle);
                newKeys.add(key);
            }
            zoneExtractionKeys.put(zone.getId(), newKeys);
        } catch (Exception e) {
            plugin.log("squaremap: error syncing extraction point markers: " + e.getMessage());
        }
    }

    private void removeZoneSubMarkers(UUID zoneId, Map<UUID, Set<Key>> keyMap) {
        Set<Key> keys = keyMap.remove(zoneId);
        if (keys != null) {
            for (Key k : keys) {
                try { layerProvider.removeMarker(k); } catch (Exception ignored) {}
            }
        }
    }

    private static String formatZoneName(String name) {
        return name.replace('_', ' ');
    }

    private static Color incursionTierFillColor(ZoneTier tier) {
        return switch (tier) {
            case GREEN  -> new Color(0, 200, 0, 40);
            case YELLOW -> new Color(220, 180, 0, 40);
            case RED    -> new Color(200, 0, 0, 40);
            case DEATH  -> new Color(20, 0, 20, 60);
        };
    }

    private static Color incursionTierStrokeColor(ZoneTier tier) {
        return switch (tier) {
            case GREEN  -> new Color(0, 220, 0, 200);
            case YELLOW -> new Color(255, 215, 0, 200);
            case RED    -> new Color(255, 0, 0, 200);
            case DEATH  -> new Color(40, 0, 40, 230);
        };
    }

    private static String incursionTierDescription(ZoneTier tier) {
        return switch (tier) {
            case GREEN  -> "No item drops on death";
            case YELLOW -> "~33% of items drop on death";
            case RED    -> "All items drop on death";
            case DEATH  -> "All items lost + sequence regression";
        };
    }

    private static Color exclusionTierFillColor(ExclusionZoneTier tier) {
        return switch (tier) {
            case SAFE   -> new Color(0, 200, 0, 40);
            case MEDIUM -> new Color(220, 180, 0, 40);
            case HARD   -> new Color(200, 0, 0, 40);
        };
    }

    private static Color exclusionTierStrokeColor(ExclusionZoneTier tier) {
        return switch (tier) {
            case SAFE   -> new Color(0, 220, 0, 200);
            case MEDIUM -> new Color(255, 215, 0, 200);
            case HARD   -> new Color(255, 0, 0, 200);
        };
    }

    private static String exclusionTierDescription(ExclusionZoneTier tier) {
        return switch (tier) {
            case SAFE   -> "No inventory drops on death";
            case MEDIUM -> "~33% item drop rate on death";
            case HARD   -> "All items drop on death";
        };
    }

    private static Color poiFillColor(ResourceType type) {
        return switch (type) {
            case GOLD -> new Color(255, 200, 0, 60);
            case SILVER -> new Color(180, 180, 210, 60);
            case GEMS -> new Color(0, 200, 100, 60);
        };
    }

    private static Color poiStrokeColor(ResourceType type) {
        return switch (type) {
            case GOLD -> new Color(255, 215, 0, 220);
            case SILVER -> new Color(192, 192, 220, 220);
            case GEMS -> new Color(0, 201, 87, 220);
        };
    }

    private static String poiLabel(ResourceType type) {
        return switch (type) {
            case GOLD -> "⬛ Gold";
            case SILVER -> "◈ Silver";
            case GEMS -> "◆ Gems";
        };
    }

    // --- Key helpers ---
    // squaremap keys must be lowercase alphanumeric + underscore; strip UUID hyphens

    private static Key zoneKey(UUID id) {
        return Key.of("zone_" + id.toString().replace("-", ""));
    }

    private static Key beaconKey(String id) {
        return Key.of("beacon_" + id.replace("-", "").toLowerCase());
    }

    private static Key corruptedKey(UUID id) {
        return Key.of("corrupted_" + id.toString().replace("-", ""));
    }

    private static Key permanentZoneKey(UUID id) {
        return Key.of("pzone_" + id.toString().replace("-", ""));
    }

    private static Key poiKey(UUID id) {
        return Key.of("poi_" + id.toString().replace("-", ""));
    }

    private static Key poiIconKey(UUID id) {
        return Key.of("poi_icon_" + id.toString().replace("-", ""));
    }

    private static Key extractionKey(UUID id) {
        return Key.of("ep_" + id.toString().replace("-", ""));
    }
}
