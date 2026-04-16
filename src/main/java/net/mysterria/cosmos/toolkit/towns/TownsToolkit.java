package net.mysterria.cosmos.toolkit.towns;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import net.william278.husktowns.api.HuskTownsAPI;
import net.william278.husktowns.claim.Claim;
import net.william278.husktowns.claim.TownClaim;
import net.william278.husktowns.claim.World;
import net.william278.husktowns.town.Member;
import net.william278.husktowns.town.Town;
import net.william278.husktowns.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class TownsToolkit {

    private static HuskTownsAPI huskTownsApi;
    private static LandsIntegration landsIntegration;

    /**
     * Initialise TownsToolkit with the available town-plugin APIs.
     * At least one of the parameters must be non-null; both may be provided.
     */
    public static void init(HuskTownsAPI huskTowns, LandsIntegration lands) {
        huskTownsApi = huskTowns;
        landsIntegration = lands;
    }

    private static boolean hasHuskTowns() {
        return huskTownsApi != null;
    }

    private static boolean hasLands() {
        return landsIntegration != null;
    }

    // ── Lands-specific accessors (retained for direct Land access if needed) ───

    public static @NotNull Collection<Land> getLands() {
        if (!hasLands()) return Collections.emptyList();
        return landsIntegration.getLands();
    }

    public static Optional<Land> getLand(String name) {
        if (!hasLands()) return Optional.empty();
        return Optional.ofNullable(landsIntegration.getLandByName(name));
    }

    // ── Unified town-data API ────────────────────────────────────────────────────

    public static List<TownData> getTowns() {
        List<TownData> result = new ArrayList<>();
        if (hasHuskTowns()) {
            huskTownsApi.getTowns().stream()
                    .map(TownsToolkit::toTownData)
                    .forEach(result::add);
        }
        if (hasLands()) {
            landsIntegration.getLands().stream()
                    .map(TownsToolkit::toLandTownData)
                    .forEach(result::add);
        }
        return result;
    }

    public static Optional<TownData> getTown(String name) {
        if (hasHuskTowns()) {
            Optional<TownData> found = huskTownsApi.getTown(name).map(TownsToolkit::toTownData);
            if (found.isPresent()) return found;
        }
        if (hasLands()) {
            Land exact = landsIntegration.getLandByName(name);
            if (exact != null) return Optional.of(toLandTownData(exact));
            // getLandByName is case-sensitive; try a case-insensitive scan as fallback
            return landsIntegration.getLands().stream()
                    .filter(l -> l.getName().equalsIgnoreCase(name))
                    .findFirst()
                    .map(TownsToolkit::toLandTownData);
        }
        return Optional.empty();
    }

    public static Optional<TownData> getTownByPlayer(Player player) {
        if (hasHuskTowns()) {
            Optional<TownData> found = huskTownsApi
                    .getUserTown(User.of(player.getUniqueId(), player.getName()))
                    .map(Member::town)
                    .filter(Objects::nonNull)
                    .map(TownsToolkit::toTownData);
            if (found.isPresent()) return found;
        }
        if (hasLands()) {
            UUID uuid = player.getUniqueId();
            LandPlayer lp = landsIntegration.getLandPlayer(uuid);
            if (lp != null) {
                Land owning = lp.getOwningLand();
                if (owning != null) return Optional.of(toLandTownData(owning));
                Collection<? extends Land> trusted = lp.getLands();
                if (trusted != null && !trusted.isEmpty()) {
                    return Optional.of(toLandTownData(trusted.iterator().next()));
                }
            }
            // getLands() only returns *trusted* lands, not owned ones —
            // scan all lands and match by owner UUID so land creators are always found.
            for (Land land : landsIntegration.getLands()) {
                if (uuid.equals(land.getOwnerUID()) || land.isTrusted(uuid)) {
                    return Optional.of(toLandTownData(land));
                }
            }
        }
        return Optional.empty();
    }

    /** Alias for {@link #getTownByPlayer(Player)}. */
    public static Optional<TownData> getPlayerTown(Player player) {
        return getTownByPlayer(player);
    }

    /**
     * Look up a town/land by its numeric ID.
     * For Lands, the ID is the stable hash derived via {@link #landId(Land)}.
     */
    public static Optional<TownData> getTownById(int id) {
        if (hasHuskTowns()) {
            Optional<TownData> found = huskTownsApi.getTowns().stream()
                    .filter(t -> t.getId() == id)
                    .findFirst()
                    .map(TownsToolkit::toTownData);
            if (found.isPresent()) return found;
        }
        if (hasLands()) {
            return landsIntegration.getLands().stream()
                    .filter(l -> landId(l) == id)
                    .findFirst()
                    .map(TownsToolkit::toLandTownData);
        }
        return Optional.empty();
    }

    public static List<Location> getTownLocations() {
        List<Location> locations = new ArrayList<>();

        if (hasHuskTowns()) {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                List<TownClaim> claims = huskTownsApi.getClaims(
                        World.of(world.getUID(), world.getName(), world.getEnvironment().toString()));
                Map<Town, List<TownClaim>> byTown = new HashMap<>();
                for (TownClaim claim : claims) {
                    byTown.computeIfAbsent(claim.town(), k -> new ArrayList<>()).add(claim);
                }
                for (List<TownClaim> townClaims : byTown.values()) {
                    TownClaim center = townClaims.get(townClaims.size() / 2);
                    Claim chunk = center.claim();
                    int x = (chunk.getChunk().getX() << 4) + 8;
                    int z = (chunk.getChunk().getZ() << 4) + 8;
                    locations.add(new Location(world, x, world.getHighestBlockYAt(x, z), z));
                }
            }
        }

        if (hasLands()) {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (Land land : landsIntegration.getLands()) {
                    Location spawn = land.getSpawn();
                    if (spawn != null) {
                        locations.add(spawn);
                        continue;
                    }
                    Collection<me.angeschossen.lands.api.land.ChunkCoordinate> chunks = land.getChunks(world);
                    if (chunks.isEmpty()) continue;
                    int sumX = 0, sumZ = 0;
                    for (me.angeschossen.lands.api.land.ChunkCoordinate cc : chunks) {
                        sumX += cc.getX();
                        sumZ += cc.getZ();
                    }
                    int avgX = (sumX / chunks.size() << 4) + 8;
                    int avgZ = (sumZ / chunks.size() << 4) + 8;
                    locations.add(new Location(world, avgX, world.getHighestBlockYAt(avgX, avgZ), avgZ));
                }
            }
        }

        return locations;
    }

    /** Returns {@code true} if the given location falls inside a claimed town or land. */
    public static boolean isLocationInTownClaim(Location location) {
        if (location.getWorld() == null) return false;

        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        if (hasHuskTowns()) {
            World huskWorld = World.of(location.getWorld().getUID(),
                    location.getWorld().getName(),
                    location.getWorld().getEnvironment().toString());
            if (huskTownsApi.getClaimAt(
                    net.william278.husktowns.claim.Chunk.at(chunkX, chunkZ), huskWorld).isPresent()) {
                return true;
            }
        }
        if (hasLands()) {
            return landsIntegration.getLandByChunk(location.getWorld(), chunkX, chunkZ) != null;
        }
        return false;
    }

    /** Returns the set of all claimed chunk coordinates in the given world. */
    public static Set<ChunkPosition> getClaimedChunks(org.bukkit.World world) {
        Set<ChunkPosition> result = new HashSet<>();

        if (hasHuskTowns()) {
            World huskWorld = World.of(world.getUID(), world.getName(), world.getEnvironment().toString());
            for (TownClaim claim : huskTownsApi.getClaims(huskWorld)) {
                result.add(new ChunkPosition(
                        claim.claim().getChunk().getX(),
                        claim.claim().getChunk().getZ()));
            }
        }

        if (hasLands()) {
            for (Land land : landsIntegration.getLands()) {
                for (me.angeschossen.lands.api.land.ChunkCoordinate cc : land.getChunks(world)) {
                    result.add(new ChunkPosition(cc.getX(), cc.getZ()));
                }
            }
        }

        return result;
    }

    /** Returns online members of the given town as Bukkit {@link Player} objects. */
    public static List<Player> getMembers(TownData town) {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : town.memberUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) result.add(p);
        }
        return result;
    }

    // ── Conversion helpers ───────────────────────────────────────────────────────

    private static TownData toTownData(Town town) {
        return new TownData(
                town.getId(),
                town.getName(),
                Collections.unmodifiableSet(town.getMembers().keySet()));
    }

    private static TownData toLandTownData(Land land) {
        Set<UUID> members = new HashSet<>(land.getTrustedPlayers());
        UUID owner = land.getOwnerUID();
        if (owner != null) members.add(owner);
        return new TownData(landId(land), land.getName(), Collections.unmodifiableSet(members));
    }

    /**
     * Derives a stable, positive, non-zero int ID from a Lands {@link Land}.
     * Based on the land's name hash, which is deterministic across JVM restarts.
     */
    static int landId(Land land) {
        int hash = land.getName().hashCode();
        if (hash == Integer.MIN_VALUE) return Integer.MAX_VALUE; // avoid Math.abs overflow
        int id = Math.abs(hash);
        return id == 0 ? 1 : id; // 0 is the "no owner" sentinel
    }

    // ── Chunk-position helper ────────────────────────────────────────────────────

    public record ChunkPosition(int x, int z) {
        public double distanceTo(double blockX, double blockZ) {
            double chunkCenterX = (x << 4) + 8;
            double chunkCenterZ = (z << 4) + 8;
            double dx = blockX - chunkCenterX;
            double dz = blockZ - chunkCenterZ;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }
}
