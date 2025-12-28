package net.mysterria.cosmos.domain.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.player.KillTracker;
import net.mysterria.cosmos.domain.player.PlayerStateManager;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles death penalties in Incursion zones
 * - Sequence regression for Seq 4 deaths
 * - Paper Angel insurance check
 * - Characteristic item drops
 */
public class DeathHandler {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;
    private final RewardHandler rewardHandler;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;

    private final ConcurrentHashMap<UUID, Long> lastDeathPenaltyTime = new ConcurrentHashMap<>();

    public DeathHandler(CosmosIncursion plugin, PlayerStateManager playerStateManager, KillTracker killTracker) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        this.rewardHandler = new RewardHandler(plugin, killTracker);
        this.config = plugin.getConfigManager().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
    }

    /**
     * Handle player death in incursion zone
     * @param victim The player who died
     * @param killer The killer (nullable)
     * @param deathLocation Death location for item drops
     */
    public void handleZoneDeath(Player victim, Player killer, Location deathLocation) {
        // Only process if victim is actually in a zone
        if (!playerStateManager.isInZone(victim)) {
            return;
        }

        plugin.log("Processing zone death for " + victim.getName() +
                   (killer != null ? " (killed by " + killer.getName() + ")" : " (natural death)"));

        // Check if player is a beyonder
        if (!CoiToolkit.isBeyonder(victim)) {
            plugin.log("Victim is not a beyonder, skipping death penalties");
            return;
        }

        int victimSequence = CoiToolkit.getBeyonderSequence(victim);

        // Check if sequence qualifies for regression (Seq 4 by default)
        if (victimSequence > config.getRegressionSequence()) {
            plugin.log("Victim sequence " + victimSequence + " does not trigger regression (requires Seq " +
                       config.getRegressionSequence() + " or lower)");
            return;
        }

        // Check death penalty cooldown
        if (isOnDeathPenaltyCooldown(victim)) {
            long remainingSeconds = getRemainingCooldownSeconds(victim);
            plugin.log("Death penalty cooldown active for " + victim.getName() +
                      " (" + remainingSeconds + " seconds remaining)");

            Component message = miniMessage.deserialize(
                "<red>[Cosmos Incursion]</red> <yellow>Death penalty on cooldown (" +
                remainingSeconds + "s remaining). You are safe from regression.</yellow>"
            );
            victim.sendMessage(message);
            return;
        }

        // Check Paper Angel protection
        if (hasPaperAngel(victim)) {
            plugin.log("Paper Angel protected " + victim.getName() + " from regression");

            // Send protection message
            Component message = miniMessage.deserialize(config.getMsgPaperAngelSaved());
            victim.sendMessage(message);

            return;
        }

        // Apply death penalty (either acting loss or sequence regression)
        boolean didRegress = CoiToolkit.lowerByOneSequence(victim);

        // Record this death penalty time
        lastDeathPenaltyTime.put(victim.getUniqueId(), System.currentTimeMillis());

        if (didRegress) {
            // Full sequence regression occurred
            plugin.log("Regressed " + victim.getName() + " from Seq " + victimSequence + " to Seq " + (victimSequence + 1));

            // Send regression message
            Component message = miniMessage.deserialize(config.getMsgDeathRegression());
            victim.sendMessage(message);

            // Drop characteristic item at death location
            dropCharacteristic(victim, victimSequence, deathLocation);
        } else {
            // Acting penalty applied (no sequence regression)
            plugin.log("Applied acting penalty to " + victim.getName() + " (no sequence regression)");

            // Send acting penalty message
            victim.sendMessage(miniMessage.deserialize(
                    "<red>[Cosmos Incursion]</red> <yellow>You lost acting progress from death in the incursion.</yellow>"
            ));
        }

        // Grant reward to killer (if not griefing)
        // Reward is given for both regression and acting penalty deaths
        if (killer != null && !killer.equals(victim)) {
            if (rewardHandler.shouldGrantReward(killer, victim)) {
                rewardHandler.grantCosmosCrate(killer);
            } else {
                plugin.log("Blocked reward for " + killer.getName() + " - griefing kill or Corrupted Monster");
            }
        }
    }

    /**
     * Check if player has Paper Angel protection
     * Uses persistent data container with key "paper_angel"
     */
    private boolean hasPaperAngel(Player player) {
        // Check for Paper Angel PDC
        var dataContainer = player.getPersistentDataContainer();
        var key = plugin.getKey("paper_angel");

        // If the key exists and is true, player has protection
        if (dataContainer.has(key, PersistentDataType.BOOLEAN)) {
            boolean hasAngel = Boolean.TRUE.equals(dataContainer.get(key, PersistentDataType.BOOLEAN));

            if (hasAngel) {
                // Remove the Paper Angel after use
                dataContainer.remove(key);
                return true;
            }
        }

        return false;
    }

    /**
     * Drop characteristic item at death location
     */
    private void dropCharacteristic(Player victim, int sequence, Location deathLocation) {
        try {
            // Get victim's pathway
            Optional<String> pathway = getPrimaryPathway(victim);

            if (pathway.isEmpty()) {
                plugin.log("Could not determine pathway for " + victim.getName());
                return;
            }

            // Get characteristic item
            ItemStack characteristic = CoiToolkit.getBeyonderChar(pathway.get(), sequence);

            if (characteristic != null && !characteristic.getType().isAir()) {
                // Drop at death location
                deathLocation.getWorld().dropItemNaturally(deathLocation, characteristic);
                plugin.log("Dropped " + pathway.get() + " Seq " + sequence + " characteristic at death location");
            } else {
                plugin.log("Warning: Could not create characteristic item for " + pathway.get() + " Seq " + sequence);
            }
        } catch (Exception e) {
            plugin.log("Error dropping characteristic: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Get player's primary (lowest sequence) pathway
     */
    private Optional<String> getPrimaryPathway(Player player) {
        if (!CoiToolkit.isBeyonder(player)) {
            return Optional.empty();
        }

        var pathways = plugin.getCoiAPI().getPathways(player.getName());

        if (pathways.isEmpty()) {
            return Optional.empty();
        }

        // Find pathway with lowest sequence
        int lowestSequence = 9;
        String primaryPathway = "";

        for (var entry : pathways.entrySet()) {
            if (entry.getValue() < lowestSequence) {
                lowestSequence = entry.getValue();
                primaryPathway = entry.getKey();
            }
        }

        return primaryPathway.isEmpty() ? Optional.empty() : Optional.of(primaryPathway);
    }

    /**
     * Check if player is on death penalty cooldown
     */
    private boolean isOnDeathPenaltyCooldown(Player player) {
        if (!lastDeathPenaltyTime.containsKey(player.getUniqueId())) {
            return false;
        }

        long lastPenaltyTime = lastDeathPenaltyTime.get(player.getUniqueId());
        long cooldownMillis = config.getDeathPenaltyCooldownSeconds() * 1000L;
        long elapsed = System.currentTimeMillis() - lastPenaltyTime;

        return elapsed < cooldownMillis;
    }

    /**
     * Get remaining cooldown time in seconds
     */
    private long getRemainingCooldownSeconds(Player player) {
        if (!lastDeathPenaltyTime.containsKey(player.getUniqueId())) {
            return 0;
        }

        long lastPenaltyTime = lastDeathPenaltyTime.get(player.getUniqueId());
        long cooldownMillis = config.getDeathPenaltyCooldownSeconds() * 1000L;
        long elapsed = System.currentTimeMillis() - lastPenaltyTime;
        long remaining = cooldownMillis - elapsed;

        return Math.max(0, remaining / 1000);
    }

    /**
     * Clear death penalty cooldown for a player (useful for event resets)
     */
    public void clearCooldown(Player player) {
        lastDeathPenaltyTime.remove(player.getUniqueId());
    }

    /**
     * Clear all death penalty cooldowns (useful for event end)
     */
    public void clearAllCooldowns() {
        lastDeathPenaltyTime.clear();
    }

}
