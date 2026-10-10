package net.mysterria.cosmos.domain.combat.service;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

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
        this.config = plugin.getConfigLoader().getConfig();
        this.killTracker = killTracker;
    }

    /**
     * Grant a Cosmos Crate to a player
     * Executes the configured crate command
     */
    public void grantCosmosCrate(Player player) {
        if (player == null || !player.isOnline()) {
            plugin.log("Cannot grant crate - player is null or offline");
            emitCrate(AuditOutcome.DENIED, player, null, "player_offline", null);
            return;
        }

        String command = null;
        boolean audited = false;
        try {
            // Get crate command from config
            command = config.getCrateCommand().replace("%player%", player.getName());

            // Execute command as console
            boolean success = Bukkit.getServer().dispatchCommand(
                    Bukkit.getConsoleSender(),
                    command
            );
            audited = true;
            emitCrate(success ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, player, command,
                    success ? null : "command_not_executed", null);

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
            if (!audited) emitCrate(AuditOutcome.FAILED, player, command, "command_threw", e.getClass().getSimpleName());
        }
    }

    /**
     * Records the Cosmos Crate command dispatched for a reward, from the player, the command string
     * and the dispatch result the method already holds. The player is null on the offline guard path.
     */
    private void emitCrate(AuditOutcome outcome, Player player, String command, String reason, String error) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (player != null) metadata.put("player_name", player.getName());
            if (command != null) metadata.put("command", command);
            if (error != null) metadata.put("error", error);

            DeathAudit.emit(plugin, "incursion.cosmos_crate_granted", outcome, AuditRisk.NORMAL,
                    null, player != null ? player.getUniqueId() : null, null, reason, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
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
        return !isGriefingKill(killer, victim);
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
