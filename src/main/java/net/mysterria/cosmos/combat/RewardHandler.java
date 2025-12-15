package net.mysterria.cosmos.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.player.KillTracker;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Handles rewards for kills in Incursion zones
 * - Cosmos Crate distribution
 * - Blocks rewards for griefing kills
 */
public class RewardHandler {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
    private final KillTracker killTracker;

    public RewardHandler(CosmosIncursion plugin, KillTracker killTracker) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager().getConfig();
        this.killTracker = killTracker;
    }

    /**
     * Grant a Cosmos Crate to a player
     * Executes the configured crate command
     */
    public void grantCosmosCrate(Player player) {
        if (player == null || !player.isOnline()) {
            plugin.log("Cannot grant crate - player is null or offline");
            return;
        }

        try {
            // Get crate command from config
            String command = config.getCrateCommand().replace("%player%", player.getName());

            // Execute command as console
            boolean success = Bukkit.getServer().dispatchCommand(
                    Bukkit.getConsoleSender(),
                    command
            );

            if (success) {
                plugin.log("Granted Cosmos Crate to " + player.getName());

                // Send confirmation message
                player.sendMessage(
                        Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                                .append(Component.text("You've been rewarded with a Cosmos Crate!", NamedTextColor.WHITE))
                );
            } else {
                plugin.log("Failed to execute crate command for " + player.getName() + ": " + command);
            }
        } catch (Exception e) {
            plugin.log("Error granting crate to " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check if a kill qualifies for rewards
     * Blocks rewards for:
     * - Corrupted Monsters
     * - Griefing kills (high-tier killing significantly lower-tier)
     */
    public boolean shouldGrantReward(Player killer, Player victim) {
        // Block rewards if killer is marked as Corrupted Monster
        if (killTracker.isCorruptedMonster(killer)) {
            return false;
        }

        // Block rewards for griefing kills
        if (isGriefingKill(killer, victim)) {
            return false;
        }

        return true;
    }

    /**
     * Check if a kill qualifies as griefing
     */
    private boolean isGriefingKill(Player killer, Player victim) {
        // Both must be beyonders
        if (!CoiToolkit.isBeyonder(killer) || !CoiToolkit.isBeyonder(victim)) {
            return false;
        }

        int killerSequence = CoiToolkit.getBeyonderSequence(killer);
        int victimSequence = CoiToolkit.getBeyonderSequence(victim);

        // Calculate sequence difference (lower sequence = stronger)
        int sequenceDifference = victimSequence - killerSequence;

        // Check if difference exceeds threshold
        return sequenceDifference >= config.getGriefSequenceDifference();
    }

}
