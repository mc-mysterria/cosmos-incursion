package net.mysterria.cosmos.integration.map;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.beacon.SpiritBeacon;
import net.mysterria.cosmos.domain.zone.IncursionZone;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import xyz.jpenilla.squaremap.api.*;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

import java.awt.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

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

            plugin.log("squaremap integration enabled");
        } catch (Exception e) {
            plugin.log("squaremap not available: " + e.getMessage());
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
            CosmosConfig.ZoneTierConfig cfg = plugin.getConfigManager().getConfig()
                    .getTierConfigs().get(zone.getTier());

            Color fill = new Color(cfg.particleR(), cfg.particleG(), cfg.particleB(), 38);   // ~15% opacity
            Color stroke = new Color(cfg.particleR(), cfg.particleG(), cfg.particleB(), 153);  // ~60% opacity

            MarkerOptions options = MarkerOptions.builder()
                    .fillColor(fill)
                    .strokeColor(stroke)
                    .strokeWeight(3)
                    .clickTooltip("[" + zone.getTier().name() + "] Cosmos Incursion – " + zone.getName())
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
}
