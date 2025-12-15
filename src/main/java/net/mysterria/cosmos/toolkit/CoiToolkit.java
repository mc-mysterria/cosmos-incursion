package net.mysterria.cosmos.toolkit;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;

public class CoiToolkit {

    private static final CircleOfImaginationAPI coiApi = CosmosIncursion.getInstance().getCoiAPI();

    public static boolean turnOffSafeMode(Player player) {
        if (coiApi.isBeyonder(player)) {
            return coiApi.setSafeMode(player, false);
        }

        return false;
    }

    public static boolean turnOnSafeMode(Player player) {
        if (coiApi.isBeyonder(player)) {
            return coiApi.setSafeMode(player, true);
        }

        return false;
    }

    public static boolean isBeyonder(Player player) {
        return coiApi.isBeyonder(player);
    }

    public static int getBeyonderSequence(Player player) {
        return coiApi.getLowestSequence(player);
    }

    public static boolean lowerByOneSequence(Player player) {
        if (coiApi.isBeyonder(player)) {
            Optional<String> primaryLowestPathway = getPrimaryPathway(player);
            int lowestSequence = coiApi.getLowestSequence(player);

            if (primaryLowestPathway.isPresent()) {
                coiApi.destroyBeyonder(player);
                // Regression means going to a higher sequence number (weaker)
                // Seq 4 -> Seq 5, NOT Seq 4 -> Seq 3
                return coiApi.createBeyonder(player, primaryLowestPathway.get(), lowestSequence + 1);
            } else {
                return false;
            }
        }
        return false;
    }

    public static int getActing(Player player) {
        if (coiApi.isBeyonder(player)) {
            Optional<String> primaryPathway = getPrimaryPathway(player);
            return primaryPathway.map(s -> coiApi.getActing(player, s)).orElse(-1);
        }
        return -1;
    }

    public static boolean removeActing(Player player, int acting) {
        if (coiApi.isBeyonder(player)) {
            Optional<String> primaryPathway = getPrimaryPathway(player);
            primaryPathway.ifPresent(s -> coiApi.addActing(player, s, -acting));
        }
        return false;
    }

    public static boolean addActing(Player player, int acting) {
        if (coiApi.isBeyonder(player)) {
            Optional<String> primaryPathway = getPrimaryPathway(player);
            primaryPathway.ifPresent(s -> coiApi.addActing(player, s, acting));
        }
        return false;
    }

    private static Optional<String> getPrimaryPathway(Player player) {
        if (coiApi.isBeyonder(player)) {
            Map<String, Integer> pathwaysToSequences = coiApi.getPathways(player.getName());

            int lowestSequence = 9;
            String pathwayWithLowestSequence = "";
            for (Map.Entry<String, Integer> entry : pathwaysToSequences.entrySet()) {
                if (entry.getValue() < lowestSequence) {
                    lowestSequence = entry.getValue();
                    pathwayWithLowestSequence = entry.getKey();
                }
            }

            if (pathwayWithLowestSequence.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(pathwayWithLowestSequence);
        }

        return Optional.empty();
    }

    public static ItemStack getBeyonderChar(String pathway, int sequence) {
        return coiApi.getIngredient("char-" + pathway + "-" + sequence);
    }
}