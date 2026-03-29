package net.mysterria.cosmos.domain.exclusion.manager;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionChannelState;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionPoint;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PointOfInterest;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

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

    // Per-player active extraction channel
    private final Map<UUID, ExtractionChannelState> extractionChannels = new ConcurrentHashMap<>();

    // PoI UUID → live ItemDisplay entity (for rotation and cleanup)
    private final Map<UUID, Entity> poiDisplayEntities = new ConcurrentHashMap<>();

    // Per-zone queue of scheduled PoI respawn timestamps (epoch ms)
    private final Map<UUID, List<Long>> poiRespawnSchedule = new ConcurrentHashMap<>();

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
        List<PointOfInterest> pois = activePoIs.getOrDefault(zoneId, Collections.emptyList());
        for (PointOfInterest poi : pois) {
            removeDisplayEntity(poi.getId());
        }
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
        // Remove existing display entities first
        List<PointOfInterest> existing = activePoIs.getOrDefault(zone.getId(), Collections.emptyList());
        for (PointOfInterest poi : existing) {
            removeDisplayEntity(poi.getId());
        }

        // Clear any stale respawn schedule so fresh PoIs spawn immediately on zone init
        poiRespawnSchedule.remove(zone.getId());

        CosmosConfig config = plugin.getConfigLoader().getConfig();
        int count = config.getPermanentZonePoiCount();
        long durationMillis = config.getPermanentZonePoiDurationSeconds() * 1000L;
        double poiRadius = config.getPermanentZonePoiCaptureRadius();
        double resourceCap = config.getPermanentZonePoiResourceCap();
        ResourceType[] types = ResourceType.values();
        var rng = ThreadLocalRandom.current();

        List<PointOfInterest> pois = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location loc = randomLocationInsideZone(zone, rng);
            ResourceType type = types[rng.nextInt(types.length)];
            PointOfInterest poi = new PointOfInterest(loc, type, poiRadius, durationMillis, resourceCap);
            pois.add(poi);
            spawnDisplayEntityForPoI(poi);
        }
        activePoIs.put(zone.getId(), pois);
        plugin.getMapIntegration().syncPermanentZonePoIs(zone, pois);
    }

    /**
     * Called every second. Removes inactive (depleted/expired) PoIs, schedules delayed
     * respawns for each one, then spawns any scheduled PoIs whose delay has elapsed.
     */
    public void rotatePoIs(PermanentZone zone) {
        List<PointOfInterest> pois = activePoIs.computeIfAbsent(zone.getId(), k -> new ArrayList<>());
        List<Long> schedule = poiRespawnSchedule.computeIfAbsent(zone.getId(), k -> new ArrayList<>());

        CosmosConfig config = plugin.getConfigLoader().getConfig();
        long durationMillis = config.getPermanentZonePoiDurationSeconds() * 1000L;
        double poiRadius = config.getPermanentZonePoiCaptureRadius();
        double resourceCap = config.getPermanentZonePoiResourceCap();
        int minDelay = config.getPermanentZonePoiRespawnMinSeconds();
        int maxDelay = config.getPermanentZonePoiRespawnMaxSeconds();
        ResourceType[] types = ResourceType.values();
        var rng = ThreadLocalRandom.current();

        boolean changed = false;

        // Remove inactive PoIs and schedule their replacements
        for (PointOfInterest poi : new ArrayList<>(pois)) {
            if (!poi.isActive()) {
                removeDisplayEntity(poi.getId());
                pois.remove(poi);
                long delay = (minDelay + rng.nextInt(Math.max(1, maxDelay - minDelay))) * 1000L;
                schedule.add(System.currentTimeMillis() + delay);
                changed = true;
            }
        }

        // Spawn scheduled PoIs whose delay has elapsed
        long now = System.currentTimeMillis();
        boolean spawned = false;
        List<Long> ready = schedule.stream().filter(t -> now >= t).toList();
        if (!ready.isEmpty()) {
            schedule.removeAll(ready);
            for (int i = 0; i < ready.size(); i++) {
                Location loc = randomLocationInsideZone(zone, rng);
                ResourceType type = types[rng.nextInt(types.length)];
                PointOfInterest poi = new PointOfInterest(loc, type, poiRadius, durationMillis, resourceCap);
                pois.add(poi);
                spawnDisplayEntityForPoI(poi);
                announcePoISpawned(zone, poi);
                spawned = true;
            }
            changed = true;
        }

        if (changed) {
            plugin.getMapIntegration().syncPermanentZonePoIs(zone, pois);
        }
    }

    private void announcePoISpawned(PermanentZone zone, PointOfInterest poi) {
        NamedTextColor color = switch (poi.getResourceType()) {
            case GOLD -> NamedTextColor.GOLD;
            case SILVER -> NamedTextColor.WHITE;
            case GEMS -> NamedTextColor.GREEN;
        };
        Component msg = Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
            .append(Component.text("A ", NamedTextColor.WHITE))
            .append(Component.text(poi.getResourceType().name(), color))
            .append(Component.text(" Point of Interest has emerged in ", NamedTextColor.WHITE))
            .append(Component.text(zone.getName(), NamedTextColor.YELLOW))
            .append(Component.text("! Contest it before it runs dry.", NamedTextColor.WHITE));
        Bukkit.broadcast(msg);
    }

    public void spawnExtractionPoints(PermanentZone zone) {
        CosmosConfig config = plugin.getConfigLoader().getConfig();
        int count = config.getPermanentZoneExtractionPointCount();
        long durationMillis = config.getPermanentZoneExtractionPointDurationSeconds() * 1000L;
        double radius = config.getPermanentZoneExtractionRadius();
        var rng = ThreadLocalRandom.current();

        List<ExtractionPoint> eps = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location loc = randomLocationNearBoundary(zone, rng);
            eps.add(new ExtractionPoint(loc, radius, durationMillis));
        }
        extractionPoints.put(zone.getId(), eps);
        plugin.getMapIntegration().syncPermanentZoneExtractionPoints(zone, eps);
    }

    public void rotateExtractionPoints(PermanentZone zone) {
        List<ExtractionPoint> eps = extractionPoints.computeIfAbsent(zone.getId(), k -> new ArrayList<>());
        CosmosConfig config = plugin.getConfigLoader().getConfig();
        long durationMillis = config.getPermanentZoneExtractionPointDurationSeconds() * 1000L;
        double radius = config.getPermanentZoneExtractionRadius();
        var rng = ThreadLocalRandom.current();

        boolean anyExpired = eps.stream().anyMatch(ep -> !ep.isActive());
        eps.removeIf(ep -> !ep.isActive());

        int needed = config.getPermanentZoneExtractionPointCount() - eps.size();
        for (int i = 0; i < needed; i++) {
            Location loc = randomLocationNearBoundary(zone, rng);
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

    // ── ItemDisplay entity management ────────────────────────────────────────────

    private void spawnDisplayEntityForPoI(PointOfInterest poi) {
        Location loc = poi.getLocation().clone().add(0, 1.5, 0);
        World world = loc.getWorld();
        if (world == null) return;
        if (!loc.getChunk().isLoaded()) loc.getChunk().load();

        ItemDisplay display = (ItemDisplay) world.spawnEntity(loc, EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(poi.getResourceType().getDefaultMaterial()));
        display.setBillboard(Display.Billboard.FIXED);
        display.setGravity(false);
        display.setInterpolationDuration(10);
        display.getPersistentDataContainer().set(
            plugin.getKey("poi_display"),
            PersistentDataType.STRING,
            poi.getId().toString()
        );

        poiDisplayEntities.put(poi.getId(), display);
    }

    private void removeDisplayEntity(UUID poiId) {
        Entity entity = poiDisplayEntities.remove(poiId);
        if (entity != null && !entity.isDead()) {
            entity.remove();
        }
    }

    /** Returns the live display entity for a PoI, or null if none. */
    public Entity getPoIDisplayEntity(UUID poiId) {
        return poiDisplayEntities.get(poiId);
    }

    /**
     * Removes any orphaned poi_display entities left over from a previous server session.
     * Call this on plugin enable before spawning fresh PoIs.
     */
    public void cleanupOrphanedDisplayEntities() {
        NamespacedKey key = plugin.getKey("poi_display");
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ItemDisplay
                        && entity.getPersistentDataContainer().has(key)) {
                    entity.remove();
                }
            }
        }
    }

    /** Removes all live display entities. Call on plugin disable. */
    public void cleanup() {
        for (Entity entity : poiDisplayEntities.values()) {
            if (!entity.isDead()) entity.remove();
        }
        poiDisplayEntities.clear();
    }

    // ── Extraction channel tracking ──────────────────────────────────────────────

    public void startExtractionChannel(UUID playerId, ExtractionPoint ep) {
        extractionChannels.put(playerId, new ExtractionChannelState(playerId, ep));
    }

    public ExtractionChannelState getExtractionChannel(UUID playerId) {
        return extractionChannels.get(playerId);
    }

    public void cancelExtractionChannel(UUID playerId) {
        extractionChannels.remove(playerId);
    }

    // ── Player buffer access ─────────────────────────────────────────────────────

    public PlayerResourceBuffer getBuffer(UUID playerId) {
        return buffers.computeIfAbsent(playerId, PlayerResourceBuffer::new);
    }

    public void clearBuffer(UUID playerId) {
        buffers.remove(playerId);
        extractionChannels.remove(playerId);
    }

    /** Returns snapshot and removes the buffer entry. */
    public Map<ResourceType, Double> collectAndClearBuffer(UUID playerId) {
        PlayerResourceBuffer buf = buffers.remove(playerId);
        extractionChannels.remove(playerId);
        if (buf == null || buf.isEmpty()) return Collections.emptyMap();
        return buf.snapshot();
    }

    // ── Player zone tracking ─────────────────────────────────────────────────────

    public void updatePlayerZone(UUID playerId, PermanentZone zone) {
        if (zone == null) {
            playerZones.remove(playerId);
            extractionChannels.remove(playerId);
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

    // ── Exit point calculation ────────────────────────────────────────────────────

    /**
     * Finds a safe location just outside the zone boundary.
     * Casts a ray from the centroid through {@code from}, finds where it exits the polygon,
     * then adds {@code bufferDistance} extra blocks so the player lands clearly outside.
     */
    public Location findExitPoint(PermanentZone zone, Location from, double bufferDistance) {
        Location centroid = zone.getCentroid();
        if (centroid == null) return null;
        World world = zone.getWorld();
        if (world == null) return null;

        double dx = from.getX() - centroid.getX();
        double dz = from.getZ() - centroid.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < 0.5) {
            // Player is at centroid — pick direction of nearest vertex
            List<Location> verts = zone.getVertices();
            if (!verts.isEmpty()) {
                dx = verts.get(0).getX() - centroid.getX();
                dz = verts.get(0).getZ() - centroid.getZ();
                dist = Math.sqrt(dx * dx + dz * dz);
            }
        }
        if (dist < 0.1) return null;

        // Normalize
        dx /= dist;
        dz /= dist;

        // March outward 0.5 blocks at a time until outside the polygon
        double approxRadius = zone.getApproximateRadius();
        double maxSearch = approxRadius + bufferDistance + 60;
        for (double r = dist; r <= maxSearch; r += 0.5) {
            double x = centroid.getX() + dx * r;
            double z = centroid.getZ() + dz * r;
            if (!zone.contains(new Location(world, x, 64, z))) {
                // Found the exit boundary — step one more buffer distance outward
                double exitX = centroid.getX() + dx * (r + bufferDistance);
                double exitZ = centroid.getZ() + dz * (r + bufferDistance);
                double exitY = world.getHighestBlockYAt((int) exitX, (int) exitZ) + 1.0;
                return new Location(world, exitX, exitY, exitZ, from.getYaw(), 0);
            }
        }

        // Fallback: guaranteed-outside location
        double x = centroid.getX() + dx * (approxRadius + bufferDistance + 10);
        double z = centroid.getZ() + dz * (approxRadius + bufferDistance + 10);
        double y = world.getHighestBlockYAt((int) x, (int) z) + 1.0;
        return new Location(world, x, y, z, from.getYaw(), 0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private Location randomLocationInsideZone(PermanentZone zone, Random rng) {
        List<Location> verts = zone.getVertices();
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
        Location c = zone.getCentroid();
        return c != null ? c : verts.get(0);
    }

    /**
     * Returns a random location near a polygon edge, inset by {@code insetDistance} blocks.
     * Extraction points are placed near boundaries so the exit teleport after extraction is short.
     * Falls back to a random interior location if no valid inset position is found.
     */
    private Location randomLocationNearBoundary(PermanentZone zone, Random rng) {
        List<Location> verts = zone.getVertices();
        int n = verts.size();
        World world = zone.getWorld();
        Location centroid = zone.getCentroid();
        // Keep extraction point far enough from the boundary that the capture radius fits inside
        double insetDistance = plugin.getConfigLoader().getConfig().getPermanentZoneExtractionRadius() + 10.0;

        for (int attempt = 0; attempt < n * 4; attempt++) {
            int edgeIdx = rng.nextInt(n);
            Location a = verts.get(edgeIdx);
            Location b = verts.get((edgeIdx + 1) % n);

            double edgeDX = b.getX() - a.getX();
            double edgeDZ = b.getZ() - a.getZ();
            double edgeLen = Math.sqrt(edgeDX * edgeDX + edgeDZ * edgeDZ);
            if (edgeLen < 1.0) continue;

            // Random point along the edge
            double t = rng.nextDouble();
            double edgeX = a.getX() + t * edgeDX;
            double edgeZ = a.getZ() + t * edgeDZ;

            // Perpendicular inward normal
            double normalX = -edgeDZ / edgeLen;
            double normalZ = edgeDX / edgeLen;

            // Flip to point toward centroid
            if (centroid != null) {
                double cx = centroid.getX() - edgeX;
                double cz = centroid.getZ() - edgeZ;
                if (normalX * cx + normalZ * cz < 0) {
                    normalX = -normalX;
                    normalZ = -normalZ;
                }
            }

            double x = edgeX + normalX * insetDistance;
            double z = edgeZ + normalZ * insetDistance;
            Location candidate = new Location(world, x, 64, z);

            if (zone.contains(candidate)) {
                double y = world != null ? world.getHighestBlockYAt((int) x, (int) z) + 1.0 : 64;
                return new Location(world, x, y, z);
            }
        }

        // Fallback: random interior
        return randomLocationInsideZone(zone, rng);
    }
}
