package net.mysterria.cosmos.toolkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
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
public class BuffToolkit {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
    private final Map<Integer, TownBuff> activeTownBuffs;
    private final Map<UUID, Long> activePlayerBuffs;  // Player UUID -> expiry time
    private final Gson gson;
    private final File buffDataFile;

    public BuffToolkit(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigLoader().getConfig();
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
                // Backward compat: buff_data.json written before the per-rank buff rework has no
                // "multiplier" field, which Gson leaves at the primitive default (0.0). Patch
                // those in with the configured base bonus rather than applying a 0% buff.
                loadedBuffs.replaceAll((townId, buff) -> buff.multiplier() > 0
                        ? buff
                        : new TownBuff(buff.townId(), buff.townName(), config.getActingSpeedBonus(), buff.expiryTime()));

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
     * Award an Acting Speed buff to a town at the given rank multiplier/duration.
     * Silent at the town level — RewardDistributor is responsible for announcing event
     * standings as a whole, so this only applies the buff and notifies the members directly.
     */
    public void awardBuff(int townId, double multiplier, int durationHours) {
        // Get town
        Optional<TownData> townOpt = TownsToolkit.getTownById(townId);
        if (townOpt.isEmpty()) {
            plugin.log("Cannot award buff - town not found: " + townId);
            return;
        }

        TownData town = townOpt.get();

        // Calculate expiry time
        long durationMillis = durationHours * 60 * 60 * 1000L;
        long expiryTime = System.currentTimeMillis() + durationMillis;

        // Create buff
        TownBuff buff = new TownBuff(townId, town.name(), multiplier, expiryTime);
        activeTownBuffs.put(townId, buff);

        // Apply to all online members
        int appliedCount = 0;
        for (UUID memberId : town.memberUuids()) {
            Player player = Bukkit.getPlayer(memberId);
            if (player != null && player.isOnline()) {
                applyBuffToPlayer(player, multiplier, expiryTime);
                appliedCount++;
            }
        }

        plugin.log("Awarded +" + Math.round(multiplier * 100) + "% Acting Speed buff to town " + town.name() +
                   " for " + durationHours + "h (" + appliedCount + " online members)");

        // Save to file
        saveBuffData();
    }

    /**
     * Apply Acting Speed buff to a player
     */
    private void applyBuffToPlayer(Player player, double multiplier, long expiryTime) {
        long remainingMillis = Math.max(0L, expiryTime - System.currentTimeMillis());
        CoiToolkit.setActingSpeedMultiplier(player, multiplier, remainingMillis);

        activePlayerBuffs.put(player.getUniqueId(), expiryTime);

        player.sendMessage(
                Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                        .append(Component.text("You received an Acting Speed bonus! ", NamedTextColor.GREEN))
                        .append(Component.text("(+" + Math.round(multiplier * 100) + "%)", NamedTextColor.YELLOW))
        );

        plugin.log("Applied +" + Math.round(multiplier * 100) + "% Acting Speed buff to player " + player.getName());
    }

    /**
     * Remove Acting Speed buff from a player.
     *
     * <p>Must use {@link CoiToolkit#forceActingSpeedMultiplier} rather than the guarded setter —
     * {@code setActingSpeedMultiplier} only ever raises the value, so a guarded call here would
     * silently do nothing whenever the buff being removed is the largest one the player has ever
     * had (which, for a single-source buff like this, is always).
     */
    private void removeBuffFromPlayer(Player player) {
        CoiToolkit.forceActingSpeedMultiplier(player, 0.0, 0L);

        activePlayerBuffs.remove(player.getUniqueId());

        player.sendMessage(
                Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                        .append(Component.text("Your Acting Speed bonus has expired.", NamedTextColor.GRAY))
        );

        plugin.log("Removed Acting Speed buff from player " + player.getName());
    }

    /**
     * Check and reapply buff to a player on join. Also clears any multiplier stranded by a past
     * bug in {@link #removeBuffFromPlayer} — it used to pass a delta of {@code 1.0} (+100%)
     * instead of {@code 0.0} when "removing" a buff, and that call went through the guarded
     * setter, which never lowers a value — so removal could silently fail to apply at all.
     * A player rejoining with no tracked active buff but a multiplier at or above 1.0 is almost
     * certainly stranded by that bug: this plugin never grants a delta anywhere near that large.
     */
    public void handlePlayerJoin(Player player) {
        if (!activePlayerBuffs.containsKey(player.getUniqueId())
                && CoiToolkit.getActingSpeedMultiplier(player) >= 1.0) {
            CoiToolkit.forceActingSpeedMultiplier(player, 0.0, 0L);
            plugin.log("Cleared a stranded legacy Acting Speed multiplier for " + player.getName());
        }

        // Check if player's town has an active buff
        Optional<TownData> townOpt = TownsToolkit.getPlayerTown(player);
        if (townOpt.isEmpty()) {
            return;
        }

        TownBuff buff = activeTownBuffs.get(townOpt.get().id());
        if (buff != null && !buff.isExpired()) {
            // Reapply buff
            applyBuffToPlayer(player, buff.multiplier(), buff.expiryTime());
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
                Optional<TownData> townOpt = TownsToolkit.getTownById(entry.getKey());
                if (townOpt.isPresent()) {
                    for (UUID memberId : townOpt.get().memberUuids()) {
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
     * Data class for town buff tracking. {@code multiplier} is the acting-speed delta granted
     * (e.g. {@code 0.10} = +10%) — ranks below 1st can carry a smaller value than
     * {@code config.getActingSpeedBonus()}.
     */
    private record TownBuff(int townId, String townName, double multiplier, long expiryTime) {

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }
    }

}
