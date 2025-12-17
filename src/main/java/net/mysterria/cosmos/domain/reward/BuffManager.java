package net.mysterria.cosmos.domain.reward;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import net.william278.husktowns.town.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Acting Speed buffs for territory rewards
 * Tracks buff expiry and persists data across restarts
 */
public class BuffManager {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
    private final Map<Integer, TownBuff> activeTownBuffs;
    private final Map<UUID, Long> activePlayerBuffs;  // Player UUID -> expiry time
    private final Gson gson;
    private final File buffDataFile;

    public BuffManager(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager().getConfig();
        this.activeTownBuffs = new ConcurrentHashMap<>();
        this.activePlayerBuffs = new ConcurrentHashMap<>();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.buffDataFile = new File(plugin.getDataFolder(), "buff_data.json");
    }

    /**
     * Load buff data from JSON file
     */
    public void loadBuffData() {
        if (!buffDataFile.exists()) {
            plugin.log("No buff data file found, starting fresh");
            return;
        }

        try (FileReader reader = new FileReader(buffDataFile)) {
            Type type = new TypeToken<Map<Integer, TownBuff>>() {
            }.getType();
            Map<Integer, TownBuff> loadedBuffs = gson.fromJson(reader, type);

            if (loadedBuffs != null) {
                activeTownBuffs.putAll(loadedBuffs);
                plugin.log("Loaded " + loadedBuffs.size() + " town buffs from file");

                // Remove expired buffs
                cleanupExpired();
            }
        } catch (IOException e) {
            plugin.log("Error loading buff data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Save buff data to JSON file
     */
    public void saveBuffData() {
        try (FileWriter writer = new FileWriter(buffDataFile)) {
            gson.toJson(activeTownBuffs, writer);
            plugin.log("Saved " + activeTownBuffs.size() + " town buffs to file");
        } catch (IOException e) {
            plugin.log("Error saving buff data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Award Acting Speed buff to a town
     */
    public void awardBuffToTown(int townId) {
        // Get town
        Optional<Town> townOpt = TownsToolkit.getTownById(townId);
        if (townOpt.isEmpty()) {
            plugin.log("Cannot award buff - town not found: " + townId);
            return;
        }

        Town town = townOpt.get();

        // Calculate expiry time
        long durationMillis = config.getBuffDurationHours() * 60 * 60 * 1000L;
        long expiryTime = System.currentTimeMillis() + durationMillis;

        // Create buff
        TownBuff buff = new TownBuff(townId, town.getName(), expiryTime);
        activeTownBuffs.put(townId, buff);

        // Apply to all online members
        int appliedCount = 0;
        for (UUID memberId : town.getMembers().keySet()) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null && player.isOnline()) {
                applyBuffToPlayer(player, expiryTime);
                appliedCount++;
            }
        }

        // Broadcast to server
        Component message = Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                .append(Component.text(town.getName() + " has won the territory control! ", NamedTextColor.WHITE))
                .append(Component.text("Members receive Acting Speed bonus for " +
                                       config.getBuffDurationHours() + " hours!", NamedTextColor.GREEN));
        Bukkit.getServer().sendMessage(message);

        plugin.log("Awarded Acting Speed buff to town " + town.getName() +
                   " (" + appliedCount + " online members)");

        // Save to file
        saveBuffData();
    }

    /**
     * Apply Acting Speed buff to a player
     */
    private void applyBuffToPlayer(Player player, long expiryTime) {
        CoiToolkit.setActingSpeedMultiplier(player, config.getActingSpeedBonus(), expiryTime);

        activePlayerBuffs.put(player.getUniqueId(), expiryTime);

        player.sendMessage(
                Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                        .append(Component.text("You received an Acting Speed bonus! ", NamedTextColor.GREEN))
                        .append(Component.text("(+" + (int) ((config.getActingSpeedBonus() - 1.0) * 100) + "%)", NamedTextColor.YELLOW))
        );

        plugin.log("Applied Acting Speed buff to player " + player.getName());
    }

    /**
     * Remove Acting Speed buff from a player
     */
    private void removeBuffFromPlayer(Player player) {
        if (CoiToolkit.getActingMultiplierPercentageNamespacedKey() != null && CoiToolkit.getActingMultiplierExpiryNamespacedKey() != null) {
            player.getPersistentDataContainer().remove(CoiToolkit.getActingMultiplierExpiryNamespacedKey());
            player.getPersistentDataContainer().remove(CoiToolkit.getActingMultiplierPercentageNamespacedKey());
        }

        activePlayerBuffs.remove(player.getUniqueId());

        player.sendMessage(
                Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                        .append(Component.text("Your Acting Speed bonus has expired.", NamedTextColor.GRAY))
        );

        plugin.log("Removed Acting Speed buff from player " + player.getName());
    }

    /**
     * Check and reapply buff to a player on join
     */
    public void handlePlayerJoin(Player player) {
        // Check if player's town has an active buff
        Optional<Town> townOpt = TownsToolkit.getPlayerTown(player);
        if (townOpt.isEmpty()) {
            return;
        }

        TownBuff buff = activeTownBuffs.get(townOpt.get().getId());
        if (buff != null && !buff.isExpired()) {
            // Reapply buff
            applyBuffToPlayer(player, buff.expiryTime());
            plugin.log("Reapplied Acting Speed buff to " + player.getName() + " (town: " + buff.townName() + ")");
        }
    }

    /**
     * Handle player quit - cleanup tracking
     */
    public void handlePlayerQuit(Player player) {
        activePlayerBuffs.remove(player.getUniqueId());
    }

    /**
     * Clean up expired buffs
     */
    public void cleanupExpired() {
        long now = System.currentTimeMillis();

        // Remove expired town buffs
        activeTownBuffs.entrySet().removeIf(entry -> {
            TownBuff buff = entry.getValue();
            if (buff.isExpired()) {
                plugin.log("Town buff expired for " + buff.townName());

                // Remove buff from all online members
                Optional<Town> townOpt = TownsToolkit.getTownById(entry.getKey());
                if (townOpt.isPresent()) {
                    for (UUID memberId : townOpt.get().getMembers().keySet()) {
                        Player player = Bukkit.getPlayer(memberId);
                        if (player != null && player.isOnline()) {
                            removeBuffFromPlayer(player);
                        }
                    }
                }

                return true;
            }
            return false;
        });

        // Remove expired player buffs
        activePlayerBuffs.entrySet().removeIf(entry -> {
            if (now >= entry.getValue()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    removeBuffFromPlayer(player);
                }
                return true;
            }
            return false;
        });

        // Save if anything was removed
        saveBuffData();
    }

    /**
     * Check if a player has an active buff
     */
    public boolean hasPlayerBuff(UUID playerId) {
        Long expiryTime = activePlayerBuffs.get(playerId);
        return expiryTime != null && System.currentTimeMillis() < expiryTime;
    }

    /**
     * Data class for town buff tracking
     */
    private record TownBuff(int townId, String townName, long expiryTime) {

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }
    }

}
