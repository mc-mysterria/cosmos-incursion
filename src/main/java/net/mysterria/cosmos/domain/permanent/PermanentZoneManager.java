package net.mysterria.cosmos.domain.permanent;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class PermanentZoneManager {

    private final CosmosIncursion plugin;
    private final File dataFile;
    private final Gson gson;

    // Zone registry
    private final Map<UUID, PermanentZone> zones = new ConcurrentHashMap<>();

    // Per-zone active PoIs
    private final Map<UUID, List<PointOfInterest>> activePoIs = new ConcurrentHashMap<>();

    // Per-zone active extraction points
    private final Map<UUID, List<ExtractionPoint>> extractionPoints = new ConcurrentHashMap<>();

    // Per-player carried resources
    private final Map<UUID, PlayerResourceBuffer> buffers = new ConcurrentHashMap<>();

    // Tracks which permanent zone each online player is in (null = not in zone)
    private final Map<UUID, PermanentZone> playerZones = new ConcurrentHashMap<>();

    // Town balance: townId -> resourceType -> accumulated amount
    private final Map<Integer, Map<ResourceType, Double>> townBalances = new ConcurrentHashMap<>();
    private final File balanceFile;

    public PermanentZoneManager(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "zones_permanent.json");
        this.balanceFile = new File(plugin.getDataFolder(), "permanent_zone_balances.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    // ── Zone CRUD ───────────────────────────────────────────────────────────────

    public void addZone(PermanentZone zone) {
        zones.put(zone.getId(), zone);
        saveZones();
        plugin.getMapIntegration().createPermanentZoneMarker(zone);
    }

    public void removeZone(UUID zoneId) {
        zones.remove(zoneId);
        activePoIs.remove(zoneId);
        extractionPoints.remove(zoneId);
        saveZones();
        plugin.getMapIntegration().removePermanentZoneMarker(zoneId);
    }

    public Optional<PermanentZone> getZone(UUID id) {
        return Optional.ofNullable(zones.get(id));
    }

    public Optional<PermanentZone> getZone(String name) {
        return zones.values().stream().filter(z -> z.getName().equalsIgnoreCase(name)).findFirst();
    }

    public Collection<PermanentZone> getAllZones() {
        return Collections.unmodifiableCollection(zones.values());
    }

    public PermanentZone getZoneAt(Location loc) {
        for (PermanentZone zone : zones.values()) {
            if (zone.isActive() && zone.contains(loc)) return zone;
        }
        return null;
    }

    // ── Persistence ─────────────────────────────────────────────────────────────

    public void saveZones() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PermanentZone zone : zones.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", zone.getId().toString());
            entry.put("name", zone.getName());
            List<Map<String, Object>> verts = new ArrayList<>();
            for (Location v : zone.getVertices()) {
                Map<String, Object> vEntry = new LinkedHashMap<>();
                vEntry.put("world", v.getWorld() != null ? v.getWorld().getName() : "world");
                vEntry.put("x", v.getX());
                vEntry.put("y", v.getY());
                vEntry.put("z", v.getZ());
                verts.add(vEntry);
            }
            entry.put("vertices", verts);
            list.add(entry);
        }
        try (FileWriter fw = new FileWriter(dataFile)) {
            gson.toJson(list, fw);
        } catch (IOException e) {
            plugin.log("Failed to save permanent zones: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void loadZones() {
        if (!dataFile.exists()) {
            plugin.log("No permanent zones file found, starting fresh");
            return;
        }
        try (FileReader fr = new FileReader(dataFile)) {
            Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> list = gson.fromJson(fr, listType);
            if (list == null) return;
            for (Map<String, Object> entry : list) {
                UUID id = UUID.fromString((String) entry.get("id"));
                String name = (String) entry.get("name");
                List<Map<String, Object>> vertData = (List<Map<String, Object>>) entry.get("vertices");
                List<Location> vertices = new ArrayList<>();
                boolean worldMissing = false;
                for (Map<String, Object> vEntry : vertData) {
                    String worldName = (String) vEntry.get("world");
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        plugin.log("Skipping vertex in zone '" + name + "' — world '" + worldName + "' not found");
                        worldMissing = true;
                        break;
                    }
                    double x = ((Number) vEntry.get("x")).doubleValue();
                    double y = ((Number) vEntry.get("y")).doubleValue();
                    double z = ((Number) vEntry.get("z")).doubleValue();
                    vertices.add(new Location(world, x, y, z));
                }
                if (worldMissing) continue;
                if (vertices.size() < 3) {
                    plugin.log("Skipping zone '" + name + "' — fewer than 3 vertices");
                    continue;
                }
                zones.put(id, new PermanentZone(id, name, vertices));
            }
            plugin.log("Loaded " + zones.size() + " permanent zone(s)");
        } catch (IOException e) {
            plugin.log("Failed to load permanent zones: " + e.getMessage());
        }
    }

    public void saveBalances() {
        try (FileWriter fw = new FileWriter(balanceFile)) {
            // Convert to string-keyed map for JSON
            Map<String, Map<String, Double>> serializable = new LinkedHashMap<>();
            for (Map.Entry<Integer, Map<ResourceType, Double>> entry : townBalances.entrySet()) {
                Map<String, Double> inner = new LinkedHashMap<>();
                for (Map.Entry<ResourceType, Double> res : entry.getValue().entrySet()) {
                    inner.put(res.getKey().name(), res.getValue());
                }
                serializable.put(String.valueOf(entry.getKey()), inner);
            }
            gson.toJson(serializable, fw);
        } catch (IOException e) {
            plugin.log("Failed to save permanent zone balances: " + e.getMessage());
        }
    }

    public void loadBalances() {
        if (!balanceFile.exists()) return;
        try (FileReader fr = new FileReader(balanceFile)) {
            Type type = new TypeToken<Map<String, Map<String, Double>>>() {}.getType();
            Map<String, Map<String, Double>> raw = gson.fromJson(fr, type);
            if (raw == null) return;
            for (Map.Entry<String, Map<String, Double>> entry : raw.entrySet()) {
                int townId = Integer.parseInt(entry.getKey());
                Map<ResourceType, Double> inner = new EnumMap<>(ResourceType.class);
                for (Map.Entry<String, Double> res : entry.getValue().entrySet()) {
                    try {
                        inner.put(ResourceType.valueOf(res.getKey()), res.getValue());
                    } catch (IllegalArgumentException ignored) {}
                }
                townBalances.put(townId, inner);
            }
        } catch (IOException e) {
            plugin.log("Failed to load permanent zone balances: " + e.getMessage());
        }
    }

    // ── PoI / Extraction management ──────────────────────────────────────────────

    public void spawnPoIsForZone(PermanentZone zone) {
        CosmosConfig config = plugin.getConfigManager().getConfig();
        int count = config.getPermanentZonePoiCount();
        long durationMillis = config.getPermanentZonePoiDurationSeconds() * 1000L;
        double poiRadius = config.getPermanentZonePoiCaptureRadius();
        ResourceType[] types = ResourceType.values();
        var rng = ThreadLocalRandom.current();

        List<PointOfInterest> pois = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location loc = randomLocationInsideZone(zone, rng);
            ResourceType type = types[rng.nextInt(types.length)];
            pois.add(new PointOfInterest(loc, type, poiRadius, durationMillis));
        }
        activePoIs.put(zone.getId(), pois);
        plugin.getMapIntegration().syncPermanentZonePoIs(zone, pois);
    }

    public void rotatePoIs(PermanentZone zone) {
        List<PointOfInterest> pois = activePoIs.computeIfAbsent(zone.getId(), k -> new ArrayList<>());
        CosmosConfig config = plugin.getConfigManager().getConfig();
        long durationMillis = config.getPermanentZonePoiDurationSeconds() * 1000L;
        double poiRadius = config.getPermanentZonePoiCaptureRadius();
        ResourceType[] types = ResourceType.values();
        var rng = ThreadLocalRandom.current();

        boolean anyExpired = pois.stream().anyMatch(poi -> !poi.isActive());
        pois.removeIf(poi -> !poi.isActive());

        int needed = config.getPermanentZonePoiCount() - pois.size();
        for (int i = 0; i < needed; i++) {
            Location loc = randomLocationInsideZone(zone, rng);
            ResourceType type = types[rng.nextInt(types.length)];
            pois.add(new PointOfInterest(loc, type, poiRadius, durationMillis));
        }
        if (anyExpired || needed > 0) {
            plugin.getMapIntegration().syncPermanentZonePoIs(zone, pois);
        }
    }

    public void spawnExtractionPoints(PermanentZone zone) {
        CosmosConfig config = plugin.getConfigManager().getConfig();
        int count = config.getPermanentZoneExtractionPointCount();
        long durationMillis = config.getPermanentZoneExtractionPointDurationSeconds() * 1000L;
        double radius = config.getPermanentZoneExtractionRadius();
        var rng = ThreadLocalRandom.current();

        List<ExtractionPoint> eps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location loc = randomLocationInsideZone(zone, rng);
            eps.add(new ExtractionPoint(loc, radius, durationMillis));
        }
        extractionPoints.put(zone.getId(), eps);
        plugin.getMapIntegration().syncPermanentZoneExtractionPoints(zone, eps);
    }

    public void rotateExtractionPoints(PermanentZone zone) {
        List<ExtractionPoint> eps = extractionPoints.computeIfAbsent(zone.getId(), k -> new ArrayList<>());
        CosmosConfig config = plugin.getConfigManager().getConfig();
        long durationMillis = config.getPermanentZoneExtractionPointDurationSeconds() * 1000L;
        double radius = config.getPermanentZoneExtractionRadius();
        var rng = ThreadLocalRandom.current();

        boolean anyExpired = eps.stream().anyMatch(ep -> !ep.isActive());
        eps.removeIf(ep -> !ep.isActive());

        int needed = config.getPermanentZoneExtractionPointCount() - eps.size();
        for (int i = 0; i < needed; i++) {
            Location loc = randomLocationInsideZone(zone, rng);
            eps.add(new ExtractionPoint(loc, radius, durationMillis));
        }
        if (anyExpired || needed > 0) {
            plugin.getMapIntegration().syncPermanentZoneExtractionPoints(zone, eps);
        }
    }

    public List<PointOfInterest> getActivePoIs(PermanentZone zone) {
        return Collections.unmodifiableList(
                activePoIs.getOrDefault(zone.getId(), Collections.emptyList()));
    }

    public List<ExtractionPoint> getActiveExtractionPoints(PermanentZone zone) {
        return Collections.unmodifiableList(
                extractionPoints.getOrDefault(zone.getId(), Collections.emptyList()));
    }

    // ── Player buffer access ─────────────────────────────────────────────────────

    public PlayerResourceBuffer getBuffer(UUID playerId) {
        return buffers.computeIfAbsent(playerId, PlayerResourceBuffer::new);
    }

    public void clearBuffer(UUID playerId) {
        buffers.remove(playerId);
    }

    /** Returns snapshot and removes the buffer entry. */
    public Map<ResourceType, Double> collectAndClearBuffer(UUID playerId) {
        PlayerResourceBuffer buf = buffers.remove(playerId);
        if (buf == null || buf.isEmpty()) return Collections.emptyMap();
        return buf.snapshot();
    }

    // ── Player zone tracking ─────────────────────────────────────────────────────

    public void updatePlayerZone(UUID playerId, PermanentZone zone) {
        if (zone == null) {
            playerZones.remove(playerId);
        } else {
            playerZones.put(playerId, zone);
        }
    }

    public PermanentZone getPlayerZone(UUID playerId) {
        return playerZones.get(playerId);
    }

    // ── Town balance ─────────────────────────────────────────────────────────────

    public void depositToTown(int townId, Map<ResourceType, Double> amounts) {
        Map<ResourceType, Double> balance = townBalances.computeIfAbsent(townId,
                k -> new EnumMap<>(ResourceType.class));
        for (Map.Entry<ResourceType, Double> entry : amounts.entrySet()) {
            balance.merge(entry.getKey(), entry.getValue(), Double::sum);
        }
    }

    public Map<ResourceType, Double> getTownBalance(int townId) {
        return Collections.unmodifiableMap(
                townBalances.getOrDefault(townId, Collections.emptyMap()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Returns a random location inside the polygon using rejection sampling within the bounding box.
     * Falls back to the centroid if no point is found within 50 attempts.
     */
    private Location randomLocationInsideZone(PermanentZone zone, Random rng) {
        List<Location> verts = zone.getVertices();
        // Compute bounding box
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Location v : verts) {
            minX = Math.min(minX, v.getX());
            maxX = Math.max(maxX, v.getX());
            minZ = Math.min(minZ, v.getZ());
            maxZ = Math.max(maxZ, v.getZ());
        }
        World world = zone.getWorld();
        for (int attempt = 0; attempt < 50; attempt++) {
            double x = minX + rng.nextDouble() * (maxX - minX);
            double z = minZ + rng.nextDouble() * (maxZ - minZ);
            Location candidate = new Location(world, x, 64, z);
            if (zone.contains(candidate)) {
                double y = world != null ? world.getHighestBlockYAt((int) x, (int) z) + 1.0 : 64;
                return new Location(world, x, y, z);
            }
        }
        // Fallback to centroid
        Location c = zone.getCentroid();
        return c != null ? c : verts.get(0);
    }
}
