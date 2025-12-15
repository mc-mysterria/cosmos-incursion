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

    public List<Player> getMembers(Town town) {
        Map<UUID, Integer> membersList = town.getMembers();
        List<Player> members = new ArrayList<>();

        for (UUID uuid : membersList.keySet()) {
            members.add(Bukkit.getPlayer(uuid));
        }

        return members;
    }

}
