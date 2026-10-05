package net.mysterria.cosmos.domain.combat.service;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
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
    private final ConcurrentHashMap<UUID, ItemStack[]> savedInventories = new ConcurrentHashMap<>();

    public DeathHandler(CosmosIncursion plugin, PlayerStateManager playerStateManager, KillTracker killTracker) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        this.rewardHandler = new RewardHandler(plugin, killTracker);
        this.config = plugin.getConfigLoader().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
    }

    /**
     * Checks whether a kill qualifies for rewards (not griefing, not a Corrupted Monster kill).
     * Shared gate for both crate rewards and acting rewards.
     */
    public boolean shouldGrantReward(Player killer, Player victim) {
        return rewardHandler.shouldGrantReward(killer, victim);
    }

    /**
     * Stores items that should be kept after death.
     * @param uuid Player UUID
     * @param items Items to restore on respawn
     */
    public void storeSavedItems(UUID uuid, ItemStack[] items) {
        if (items == null || items.length == 0) return;
        savedInventories.put(uuid, items);
    }

    public void restoreSavedItems(Player player) {
        ItemStack[] items = savedInventories.remove(player.getUniqueId());
        if (items != null) {
            if (!plugin.getPermanentZoneManager().isInsideAnyZone(player.getLocation())) {
                for (int i = 0; i < items.length; i++) {
                    if (isCosmosItem(items[i])) {
                        items[i] = null;
                    }
                }
            }
            player.getInventory().setContents(items);
            plugin.log("Restored " + items.length + " items to " + player.getName() + " after respawn");
        }
    }

    private boolean isCosmosItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(plugin.getKey("cosmos_zone_compass"), PersistentDataType.BOOLEAN)
                || pdc.has(plugin.getKey("cosmos_incursion_saddle"), PersistentDataType.BOOLEAN);
    }

    /**
     * Handle player death in incursion zone.
     *
     * @param victim        The player who died
     * @param killer        The killer (nullable for environmental deaths)
     * @param deathLocation Death location for characteristic item drops
     * @param tier          The tier of the zone the victim died in
     */
    public void handleZoneDeath(Player victim, Player killer, Location deathLocation, ZoneTier tier) {
        // Only process if victim is actually in a zone
        if (!playerStateManager.isInZone(victim)) {
            return;
        }

        plugin.log("Processing " + tier + " zone death for " + victim.getName() +
                   (killer != null ? " (killed by " + killer.getName() + ")" : " (natural death)"));

        // Dispatch tier-specific reward command to the killer (if configured)
        String rewardCommand = plugin.getConfigLoader().getConfig().getTierConfigs().get(tier).rewardCommand();
        if (killer != null && !killer.equals(victim) && rewardCommand != null && !rewardCommand.isBlank()) {
            String cmd = rewardCommand.replace("%player%", killer.getName());
            boolean dispatched;
            try {
                dispatched = plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), cmd);
            } catch (RuntimeException failure) {
                emitKillRewardCommand(tier, victim, killer, cmd, false, failure);
                throw failure;
            }
            emitKillRewardCommand(tier, victim, killer, cmd, dispatched, null);
        }

        // GREEN / YELLOW / RED: no sequence regression, no acting penalty — item drops are the only punishment
        if (tier != ZoneTier.DEATH) {
            plugin.log("Non-DEATH tier (" + tier + ") — skipping regression logic for " + victim.getName());
            return;
        }

        // Check if player is a beyonder
        if (!CoiToolkit.isBeyonder(victim)) {
            plugin.log("Victim is not a beyonder, skipping death penalties");
            emitPenaltySkipped(victim, killer, tier, "not_a_beyonder", null, null);
            return;
        }

        int victimSequence = CoiToolkit.getBeyonderSequence(victim);

        // Check if sequence qualifies for regression (Seq 4 by default)
        if (victimSequence > config.getRegressionSequence()) {
            plugin.log("Victim sequence " + victimSequence + " does not trigger regression (requires Seq " +
                       config.getRegressionSequence() + " or lower)");
            emitPenaltySkipped(victim, killer, tier, "sequence_above_threshold", victimSequence, null);
            return;
        }

        // Check death penalty cooldown
        if (isOnDeathPenaltyCooldown(victim)) {
            long remainingSeconds = getRemainingCooldownSeconds(victim);
            plugin.log("Death penalty cooldown active for " + victim.getName() +
                      " (" + remainingSeconds + " seconds remaining)");
            emitPenaltySkipped(victim, killer, tier, "death_penalty_cooldown", victimSequence, remainingSeconds);

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
            emitPaperAngelConsumed(victim, killer, tier, victimSequence);

            // Send protection message
            Component message = miniMessage.deserialize(config.getMsgPaperAngelSaved());
            victim.sendMessage(message);

            return;
        }

        // Apply death penalty (either acting loss or sequence regression)
        Map<String, Object> penaltyDetail = new LinkedHashMap<>();
        boolean didRegress;
        try {
            didRegress = CoiToolkit.lowerByOneSequence(victim, penaltyDetail);
        } catch (RuntimeException failure) {
            emitDeathPenalty(victim, killer, tier, victimSequence, penaltyDetail, failure);
            throw failure;
        }

        // Record this death penalty time
        lastDeathPenaltyTime.put(victim.getUniqueId(), System.currentTimeMillis());
        emitDeathPenalty(victim, killer, tier, victimSequence, penaltyDetail, null);

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
                emitCrateBlocked(victim, killer, tier);
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
                emitCharacteristicDropped(victim, null, sequence, deathLocation, null, null, "pathway_unknown", null);
                return;
            }

            // Get characteristic item
            ItemStack characteristic = CoiToolkit.getBeyonderChar(pathway.get(), sequence);

            if (characteristic != null && !characteristic.getType().isAir()) {
                // Drop at death location
                Item dropped = deathLocation.getWorld().dropItemNaturally(deathLocation, characteristic);
                plugin.log("Dropped " + pathway.get() + " Seq " + sequence + " characteristic at death location");
                // ItemSpawnEvent may be cancelled: only a live entity counts as dropped.
                emitCharacteristicDropped(victim, pathway.get(), sequence, deathLocation, characteristic, dropped,
                        dropped == null || !dropped.isValid() ? "drop_spawn_cancelled" : null, null);
            } else {
                plugin.log("Warning: Could not create characteristic item for " + pathway.get() + " Seq " + sequence);
                emitCharacteristicDropped(victim, pathway.get(), sequence, deathLocation, null, null, "characteristic_unavailable", null);
            }
        } catch (Exception e) {
            plugin.log("Error dropping characteristic: " + e.getMessage());
            e.printStackTrace();
            emitCharacteristicDropped(victim, null, sequence, deathLocation, null, null, "drop_threw", e.getClass().getSimpleName());
        }
    }

    /**
     * Records the characteristic dropped when a death regresses a sequence. {@code failure} null
     * means the entity spawned (COMMITTED); otherwise the row is FAILED with that reason. Built from
     * the pathway, sequence, item and dropped entity the drop code already holds.
     */
    private void emitCharacteristicDropped(Player victim, String pathway, int sequence, Location deathLocation,
                                           ItemStack characteristic, Item dropped, String failure, String error) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            if (pathway != null) metadata.put("pathway", pathway);
            metadata.put("sequence_before", sequence);
            metadata.put("sequence_after", sequence + 1);
            MysterriaAuditEmitter.putLocation(metadata, deathLocation);
            if (characteristic != null) metadata.put("characteristic", DeathAudit.stack(characteristic));
            if (dropped != null) metadata.put("dropped_entity_uuid", dropped.getUniqueId().toString());
            if (error != null) metadata.put("error", error);

            DeathAudit.emit(plugin, "incursion.characteristic_dropped",
                    failure == null ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, AuditRisk.HIGH,
                    null, victim.getUniqueId(), null, failure, metadata);
        } catch (RuntimeException | LinkageError auditFailure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Records the sequence regression or acting loss a DEATH-tier death applied, from the values
     * {@link CoiToolkit#lowerByOneSequence(Player, Map)} already held. {@code thrown} is the
     * exception the penalty call threw, when it did; the exception is rethrown by the caller.
     */
    private void emitDeathPenalty(Player victim, Player killer, ZoneTier tier, int sequence,
                                  Map<String, Object> detail, RuntimeException thrown) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            metadata.put("tier", tier.name());
            if (killer != null && !killer.equals(victim)) metadata.put("killer_name", killer.getName());
            metadata.putAll(detail);
            metadata.putIfAbsent("sequence_before", sequence);
            MysterriaAuditEmitter.putPlayerLocation(metadata, victim);

            Object result = detail.get("result");
            AuditOutcome outcome;
            String reason;
            if (thrown != null) {
                outcome = AuditOutcome.FAILED;
                reason = "penalty_threw";
                metadata.put("error", thrown.getClass().getSimpleName());
            } else if ("sequence_regressed".equals(result) || "acting_lost".equals(result)) {
                outcome = AuditOutcome.COMMITTED;
                reason = null;
            } else if ("regression_failed".equals(result)) {
                // The beyonder was destroyed and could not be created again at the next sequence
                outcome = AuditOutcome.FAILED;
                reason = "beyonder_recreate_failed";
            } else {
                outcome = AuditOutcome.OBSERVED;
                reason = "no_penalty_applied";
            }

            DeathAudit.emit(plugin, "incursion.death_penalty_applied", outcome, AuditRisk.HIGH,
                    null, victim.getUniqueId(), killer != null && !killer.equals(victim) ? killer.getUniqueId() : null,
                    reason, metadata);
        } catch (RuntimeException | LinkageError auditFailure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Records a DEATH-tier death whose penalty was not applied and the reason the code already
     * knows: {@code not_a_beyonder}, {@code sequence_above_threshold} (with the victim's sequence
     * and the configured regression sequence) or {@code death_penalty_cooldown} (with the seconds
     * the handler computed for its own log line). One row per death. Built from the arguments and
     * the config already in hand; a Paper Angel that blocks the penalty writes its own row.
     */
    private void emitPenaltySkipped(Player victim, Player killer, ZoneTier tier, String reason,
                                    Integer sequence, Long cooldownRemainingSeconds) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            metadata.put("tier", tier.name());
            if (killer != null && !killer.equals(victim)) metadata.put("killer_name", killer.getName());
            if (sequence != null) {
                metadata.put("sequence", sequence);
                metadata.put("regression_sequence", config.getRegressionSequence());
            }
            if (cooldownRemainingSeconds != null) {
                metadata.put("cooldown_seconds", config.getDeathPenaltyCooldownSeconds());
                metadata.put("cooldown_remaining_seconds", cooldownRemainingSeconds);
            }
            MysterriaAuditEmitter.putPlayerLocation(metadata, victim);

            DeathAudit.emit(plugin, "incursion.death_penalty_skipped", AuditOutcome.OBSERVED,
                    "death_penalty_cooldown".equals(reason) ? AuditRisk.NORMAL : AuditRisk.LOW,
                    null, victim.getUniqueId(), killer != null && !killer.equals(victim) ? killer.getUniqueId() : null,
                    reason, metadata);
        } catch (RuntimeException | LinkageError auditFailure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /** Records the Paper Angel protection removed from the victim when it blocked a regression. */
    private void emitPaperAngelConsumed(Player victim, Player killer, ZoneTier tier, int sequence) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            metadata.put("tier", tier.name());
            metadata.put("sequence", sequence);
            metadata.put("regression_prevented", true);
            MysterriaAuditEmitter.putPlayerLocation(metadata, victim);

            DeathAudit.emit(plugin, "incursion.paper_angel_consumed", AuditOutcome.COMMITTED, AuditRisk.NORMAL,
                    null, victim.getUniqueId(), killer != null && !killer.equals(victim) ? killer.getUniqueId() : null,
                    null, metadata);
        } catch (RuntimeException | LinkageError auditFailure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Records the tier reward command dispatched for a kill. {@code dispatched} is what
     * {@code dispatchCommand} returned; {@code thrown} is the exception it threw, if any.
     */
    private void emitKillRewardCommand(ZoneTier tier, Player victim, Player killer, String command,
                                       boolean dispatched, RuntimeException thrown) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("tier", tier.name());
            metadata.put("killer_name", killer.getName());
            metadata.put("victim_name", victim.getName());
            metadata.put("command", command);
            metadata.put("dispatched", dispatched);
            if (thrown != null) metadata.put("error", thrown.getClass().getSimpleName());

            DeathAudit.emit(plugin, "incursion.kill_reward_command",
                    dispatched ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, AuditRisk.NORMAL,
                    null, killer.getUniqueId(), victim.getUniqueId(),
                    thrown != null ? "command_threw" : dispatched ? null : "command_not_executed", metadata);
        } catch (RuntimeException | LinkageError auditFailure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /** Records a Cosmos Crate withheld because the kill failed the reward gate. */
    private void emitCrateBlocked(Player victim, Player killer, ZoneTier tier) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("tier", tier.name());
            metadata.put("killer_name", killer.getName());
            metadata.put("victim_name", victim.getName());

            DeathAudit.emit(plugin, "incursion.cosmos_crate_granted", AuditOutcome.DENIED, AuditRisk.NORMAL,
                    null, killer.getUniqueId(), victim.getUniqueId(), "griefing_or_corrupted_killer", metadata);
        } catch (RuntimeException | LinkageError auditFailure) {
            MysterriaAuditEmitter.recordFailure();
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
