package net.mysterria.cosmos.toolkit;

import net.mysterria.cosmos.CosmosIncursion;
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

import java.util.*;

public class TownsToolkit {

    private static final HuskTownsAPI huskTownsApi = CosmosIncursion.getInstance().getHuskTownsAPI();

    public static List<Town> getTowns() {
        return huskTownsApi.getTowns();
    }

    public static Optional<Town> getTown(String name) {
        return huskTownsApi.getTown(name);
    }

    public static Optional<Town> getTownByPlayer(Player player) {
        Optional<Member> member = huskTownsApi.getUserTown(User.of(player.getUniqueId(), player.getName()));

        if (member.isPresent()) {
            Town town = member.get().town();
            return town == null ? Optional.empty() : Optional.of(town);
        }

        return Optional.empty();
    }

    /**
     * Alias for getTownByPlayer
     */
    public static Optional<Town> getPlayerTown(Player player) {
        return getTownByPlayer(player);
    }

    /**
     * Get a town by its UUID
     */
    public static Optional<Town> getTownById(int townId) {
        return huskTownsApi.getTowns().stream()
                .filter(town -> town.getId() == townId)
                .findFirst();
    }

    public static List<Location> getTownLocations() {
        List<Location> locations = new ArrayList<>();
        List<org.bukkit.World> worlds = Bukkit.getWorlds();

        for (org.bukkit.World world : worlds) {
            List<TownClaim> claims = huskTownsApi.getClaims(World.of(world.getUID(), world.getName(), world.getEnvironment().toString()));
            Map<Town, List<TownClaim>> townClaimsMap = new HashMap<>();
            for (TownClaim claim : claims) {
                townClaimsMap.computeIfAbsent(claim.town(), k -> new ArrayList<>()).add(claim);
            }

            for (Map.Entry<Town, List<TownClaim>> entry : townClaimsMap.entrySet()) {
                List<TownClaim> townClaims = entry.getValue();
                TownClaim centerClaim = townClaims.get(townClaims.size() / 2);
                Claim chunk = centerClaim.claim();
                int x = (chunk.getChunk().getX() << 4) + 8;
                int z = (chunk.getChunk().getZ() << 4) + 8;
                Location loc = new Location(world, x, world.getHighestBlockYAt(x, z), z);
                locations.add(loc);
            }
        }

        return locations;
    }

    /**
     * Check if a location is within a town claim
     */
    public static boolean isLocationInTownClaim(Location location) {
        if (location.getWorld() == null) {
            return false;
        }

        World huskWorld = World.of(
                location.getWorld().getUID(),
                location.getWorld().getName(),
                location.getWorld().getEnvironment().toString()
        );

        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;

        Optional<TownClaim> claim = huskTownsApi.getClaimAt(
                net.william278.husktowns.claim.Chunk.at(chunkX, chunkZ),
                huskWorld
        );

        return claim.isPresent();
    }

    /**
     * Get all claimed chunk positions for a specific world
     */
    public static Set<ChunkPosition> getClaimedChunks(org.bukkit.World world) {
        Set<ChunkPosition> claimedChunks = new HashSet<>();

        World huskWorld = World.of(
                world.getUID(),
                world.getName(),
                world.getEnvironment().toString()
        );

        List<TownClaim> claims = huskTownsApi.getClaims(huskWorld);
        for (TownClaim claim : claims) {
            claimedChunks.add(new ChunkPosition(
                    claim.claim().getChunk().getX(),
                    claim.claim().getChunk().getZ()
            ));
        }

        return claimedChunks;
    }

    public List<Player> getMembers(Town town) {
        Map<UUID, Integer> membersList = town.getMembers();
        List<Player> members = new ArrayList<>();

        for (UUID uuid : membersList.keySet()) {
            members.add(Bukkit.getPlayer(uuid));
        }

        return members;
    }

    /**
     * Simple record to hold chunk coordinates
     */
    public record ChunkPosition(int x, int z) {
        public double distanceTo(double blockX, double blockZ) {
            // Convert chunk to block coordinates (center of chunk)
            double chunkCenterX = (x << 4) + 8;
            double chunkCenterZ = (z << 4) + 8;

            double dx = blockX - chunkCenterX;
            double dz = blockZ - chunkCenterZ;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

}
