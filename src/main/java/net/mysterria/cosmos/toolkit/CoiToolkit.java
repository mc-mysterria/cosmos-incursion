package net.mysterria.cosmos.toolkit;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.BeyonderData;
import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Optional;

public class CoiToolkit {

    private static final CircleOfImaginationAPI coiApi = CosmosIncursion.getInstance().getCoiAPI();

    public static @Nullable NamespacedKey getActingMultiplierExpiryNamespacedKey() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CircleOfImagination");
        if (plugin != null) {
            return new NamespacedKey(plugin, "growth_expiry");
        }

        return null;
    }

    public static @Nullable NamespacedKey getActingMultiplierPercentageNamespacedKey() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("CircleOfImagination");
        if (plugin != null) {
            return new NamespacedKey(plugin, "growth_percentage");
        }

        return null;
    }

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
                BeyonderData beyonderData = coiApi.getBeyonderData(player);
                double actingPercentage = beyonderData.getPathway(primaryLowestPathway.get()).getActingPercentage();
                double actingBefore = beyonderData.getPathway(primaryLowestPathway.get()).neededActing();

                // Get config values
                var config = CosmosIncursion.getInstance().getConfigManager().getConfig();
                int threshold = config.getRegressionActingThreshold();
                double restoredPercentage = config.getRegressionActingRestored();
                double penaltyPercentage = config.getRegressionActingPenalty();

                if (actingPercentage < threshold) {
                    // Full sequence regression
                    coiApi.destroyBeyonder(player);

                    boolean created = coiApi.createBeyonder(player, primaryLowestPathway.get(), lowestSequence + 1);
                    if (created) {
                        BeyonderData newBeyonderData = coiApi.getBeyonderData(player);
                        int neededActing = newBeyonderData.getPathway(primaryLowestPathway.get()).neededActing();
                        coiApi.addActing(player, primaryLowestPathway.get(), (int) (neededActing * restoredPercentage));
                    }

                    return created;
                } else {
                    // Acting penalty only (no sequence regression)
                    return coiApi.addActing(player, primaryLowestPathway.get(), (int) -(actingBefore * penaltyPercentage));
                }
            }
            return false;
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

    public static void setActingSpeedMultiplier(Player player, double multiplier, long durationMillis) {
        if (coiApi.isBeyonder(player)) {
            if (coiApi.getActingSpeedMultiplier(player) < multiplier) {
                coiApi.setActingSpeedMultiplier(player, multiplier, durationMillis);
            }
        }
    }

    public static ItemStack getBeyonderChar(String pathway, int sequence) {
        return coiApi.getIngredient("char-" + pathway + "-" + sequence);
    }
}