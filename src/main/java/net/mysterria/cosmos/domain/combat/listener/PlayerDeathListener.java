package net.mysterria.cosmos.domain.combat.listener;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.service.DeathAudit;
import net.mysterria.cosmos.domain.combat.service.DeathHandler;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.combat.service.KillTracker;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import net.mysterria.cosmos.domain.incursion.model.IncursionEvent;
import net.mysterria.cosmos.domain.incursion.model.PlayerZoneState;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Listens for player deaths in Incursion zones
 * Delegates to DeathHandler for processing and KillTracker for griefing detection
 */
public class PlayerDeathListener implements Listener {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;
    private final KillTracker killTracker;
    private final DeathHandler deathHandler;

    public PlayerDeathListener(CosmosIncursion plugin, PlayerStateManager playerStateManager,
                               KillTracker killTracker, DeathHandler deathHandler) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        this.killTracker = killTracker;
        this.deathHandler = deathHandler;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // Skip Citizens NPCs (they have their own death handling via NPCDeathEvent)
        if (victim.hasMetadata("NPC")) {
            return;
        }

        // Only process deaths in incursion zones
        if (!playerStateManager.isInZone(victim)) {
            return;
        }

        // Bar re-entry to incursion zones after dying inside one
        playerStateManager.recordIncursionDeath(victim.getUniqueId());

        // Get killer (nullable for environmental deaths)
        Player killer = victim.getKiller();

        // Record kill for griefing detection (only if there is a killer)
        if (killer != null && !killer.equals(victim)) {
            killTracker.recordKill(killer, victim);
        }

        // Count for the completion summary
        IncursionEvent activeEvent = plugin.getEventManager().getActiveEvent();
        if (activeEvent != null) {
            activeEvent.incrementDeaths();
            if (killer != null && !killer.equals(victim)) activeEvent.incrementKills();
        }

        // Resolve zone tier to determine which items to drop
        ZoneTier tier = ZoneTier.DEATH; // default to harshest behavior if zone state is missing
        PlayerZoneState zoneState = playerStateManager.getState(victim);
        if (zoneState != null && zoneState.getIncursionZone() != null) {
            tier = zoneState.getIncursionZone().getTier();
        }

        boolean rewardEligible = killer != null && !killer.equals(victim) && deathHandler.shouldGrantReward(killer, victim);

        // Audit every zone death before the early return below, whoever or whatever killed the victim
        emitZoneDeath(event, victim, killer, tier, zoneState, rewardEligible);

        // Grant PvP acting for qualifying kills (blocked for griefing / Corrupted Monster killers)
        if (rewardEligible) {
            plugin.getActingRewardManager().grantIncursionPvpActing(killer, victim, tier);
        }

        // GREEN zones only protect inventory against player kills — environmental/mob deaths are not processed
        if (tier == ZoneTier.GREEN && killer == null) {
            return;
        }

        double dropChance = plugin.getConfigLoader().getConfig().getTierConfigs().get(tier).dropChance();

        // IMPORTANT: Clear event drops FIRST to prevent any default drops.
        // This prevents graves plugins from creating graves and avoids item duplication.
        Location deathLocation = victim.getLocation();
        event.getDrops().clear();

        // Manually drop items according to tier drop chance, then clear inventory
        List<Map<String, Object>> droppedStacks = new ArrayList<>();
        ItemStack[] keptItems = dropItemsWithChance(victim, deathLocation, dropChance, droppedStacks);
        deathHandler.storeSavedItems(victim.getUniqueId(), keptItems);
        emitItemsDropped("incursion.items_dropped", victim, tier.name(),
                zoneState != null && zoneState.getIncursionZone() != null ? zoneState.getIncursionZone().getId() : null,
                zoneState != null && zoneState.getIncursionZone() != null ? zoneState.getIncursionZone().getName() : null,
                dropChance, deathLocation, droppedStacks, keptItems);

        // Process death penalties (regression logic — only applies in DEATH tier)
        deathHandler.handleZoneDeath(victim, killer, deathLocation, tier);

        // Handle resource buffer on death in incursion zone.
        // Resources are never turned into physical items — they are either transferred to
        // the killer (handled by ExclusionZoneListener at HIGH priority) or simply voided.
        PermanentZone pZone = plugin.getPermanentZoneManager().getZoneAt(deathLocation);
        if (pZone == null) {
            pZone = plugin.getPermanentZoneManager().getPlayerZone(victim.getUniqueId());
        }
        if (pZone != null) {
            emitExclusionZoneDeath(event, victim, pZone, true, true, null);
            plugin.getPermanentZoneManager().recordZoneDeath(victim.getUniqueId(), pZone.getId());
            boolean hasKiller = killer != null && !killer.equals(victim);
            if (!hasKiller) {
                // No killer — void the resources silently
                emitResourcesLost(victim, pZone, plugin.getPermanentZoneManager().clearBufferSnapshot(victim.getUniqueId()));
                victim.sendMessage(net.kyori.adventure.text.Component.text("[Cosmos] ", net.kyori.adventure.text.format.NamedTextColor.DARK_RED)
                    .append(net.kyori.adventure.text.Component.text("Your carried resources were lost.", net.kyori.adventure.text.format.NamedTextColor.RED)));
            }
            // If killer exists: ExclusionZoneListener (HIGH priority) transfers the buffer.
        }
    }

    /** Called when player dies outside an incursion zone but inside a permanent zone. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDeathInPermanentZone(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim.hasMetadata("NPC")) return;
        // Already handled above if they were also in an incursion zone
        if (playerStateManager.isInZone(victim)) return;

        PermanentZone pZone = plugin.getPermanentZoneManager().getPlayerZone(victim.getUniqueId());
        if (pZone == null) return;

        // SAFE zones only protect inventory against player kills — environmental/mob deaths are not processed
        ExclusionZoneTier tier = pZone.getTier();
        boolean processed = !(tier == ExclusionZoneTier.SAFE && event.getEntity().getKiller() == null);
        // Audit every permanent zone death before the early return below
        emitExclusionZoneDeath(event, victim, pZone, false, processed, processed ? null : "safe_tier_no_player_killer");
        if (!processed) {
            return;
        }

        // Apply inventory item drops based on permanent zone tier
        double dropChance = plugin.getConfigLoader().getConfig().getExclusionTierConfigs()
                .getOrDefault(tier, plugin.getConfigLoader().getConfig().getExclusionTierConfigs().get(ExclusionZoneTier.MEDIUM))
                .dropChance();
        Location deathLocation = victim.getLocation();
        event.getDrops().clear();
        List<Map<String, Object>> droppedStacks = new ArrayList<>();
        ItemStack[] keptItems = dropItemsWithChance(victim, deathLocation, dropChance, droppedStacks);
        deathHandler.storeSavedItems(victim.getUniqueId(), keptItems);
        emitItemsDropped("exclusion.items_dropped", victim, tier.name(), pZone.getId(), pZone.getName(),
                dropChance, deathLocation, droppedStacks, keptItems);

        // Resources are never physical items — void if no killer, else ExclusionZoneListener transfers.
        Player killerP = victim.getKiller();
        boolean hasKiller = killerP != null && !killerP.equals(victim);
        if (!hasKiller) {
            emitResourcesLost(victim, pZone, plugin.getPermanentZoneManager().clearBufferSnapshot(victim.getUniqueId()));
        }

        plugin.getPermanentZoneManager().recordZoneDeath(victim.getUniqueId(), pZone.getId());
    }

    /**
     * Records one {@code incursion.zone_death} row. Reads only the death event, the victim and the
     * zone state already in hand and calls the thread-safe emitter; it never changes the death.
     */
    private void emitZoneDeath(PlayerDeathEvent event, Player victim, Player killer, ZoneTier tier,
                               PlayerZoneState zoneState, boolean rewardEligible) {
        try {
            boolean selfKill = killer != null && killer.equals(victim);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            metadata.put("tier", tier.name());
            if (zoneState != null && zoneState.getIncursionZone() != null) {
                metadata.put("zone_id", zoneState.getIncursionZone().getId().toString());
                metadata.put("zone_name", zoneState.getIncursionZone().getName());
            }
            MysterriaAuditEmitter.putPlayerLocation(metadata, victim);

            DeathAudit.KillerFacts facts = DeathAudit.putKillerFacts(metadata, victim, event.getDamageSource(), killer);
            UUID ownerId = facts.ownerId();

            boolean processed = tier != ZoneTier.GREEN || killer != null;
            metadata.put("death_processed", processed);
            if (!processed) metadata.put("not_processed_reason", "green_tier_no_player_killer");
            metadata.put("counted_for_rewards", rewardEligible);
            if (!rewardEligible) {
                metadata.put("not_counted_reason", killer == null ? "no_player_killer"
                        : selfKill ? "self_kill" : "griefing_or_corrupted_killer");
            }

            IncursionEvent active = plugin.getEventManager().getActiveEvent();
            UUID operationId = UUID.randomUUID();
            MysterriaAuditEmitter.emit(plugin, "incursion.zone_death", AuditOutcome.OBSERVED, AuditRisk.LOW,
                    active != null ? active.getEventId() : operationId, "zone-death." + operationId,
                    ownerId, victim.getUniqueId(), facts.attackerId(),
                    processed ? null : "green_tier_no_player_killer", metadata);
        } catch (RuntimeException | LinkageError failure) {
            // Audit is best effort; a failure here must never change the death itself.
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Records one {@code exclusion.zone_death} row for a death inside a permanent (exclusion) zone,
     * from the death event, the victim and the zone already in hand. {@code alsoInIncursionZone}
     * is true on the incursion-zone path, where the incursion row is written as well.
     */
    private void emitExclusionZoneDeath(PlayerDeathEvent event, Player victim, PermanentZone zone,
                                        boolean alsoInIncursionZone, boolean processed, String notProcessedReason) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            metadata.put("zone_id", zone.getId().toString());
            metadata.put("zone_name", zone.getName());
            metadata.put("tier", zone.getTier().name());
            metadata.put("also_in_incursion_zone", alsoInIncursionZone);
            MysterriaAuditEmitter.putPlayerLocation(metadata, victim);

            DeathAudit.KillerFacts facts = DeathAudit.putKillerFacts(metadata, victim, event.getDamageSource(), victim.getKiller());
            metadata.put("death_processed", processed);
            if (!processed) metadata.put("not_processed_reason", notProcessedReason);

            DeathAudit.emit(plugin, "exclusion.zone_death", AuditOutcome.OBSERVED, AuditRisk.LOW,
                    facts.ownerId(), victim.getUniqueId(), facts.attackerId(), notProcessedReason, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * One row per death for the stacks dropped on the ground: material and amount of each stack,
     * taken from the stacks the drop loop already holds. OBSERVED because dropping returns no result.
     */
    private void emitItemsDropped(String event, Player victim, String tier, UUID zoneId, String zoneName,
                                  double dropChance, Location deathLocation,
                                  List<Map<String, Object>> droppedStacks, ItemStack[] keptItems) {
        try {
            int droppedAmount = 0;
            for (Map<String, Object> stack : droppedStacks) droppedAmount += (Integer) stack.get("amount");
            int keptStacks = 0;
            for (ItemStack kept : keptItems) {
                if (kept != null && kept.getType() != Material.AIR) keptStacks++;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            metadata.put("tier", tier);
            if (zoneId != null) metadata.put("zone_id", zoneId.toString());
            if (zoneName != null) metadata.put("zone_name", zoneName);
            MysterriaAuditEmitter.putLocation(metadata, deathLocation);
            metadata.put("drop_chance", dropChance);
            metadata.put("dropped_stack_count", droppedStacks.size());
            metadata.put("dropped_item_count", droppedAmount);
            metadata.put("kept_stack_count", keptStacks);
            metadata.put("dropped", droppedStacks);

            DeathAudit.emit(plugin, event, AuditOutcome.OBSERVED, AuditRisk.NORMAL,
                    null, victim.getUniqueId(), null, null, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Records carried resources voided on a death without a player killer. {@code lost} is what the
     * removed buffer held; nothing is emitted when the victim carried nothing.
     */
    private void emitResourcesLost(Player victim, PermanentZone zone, Map<ResourceType, Double> lost) {
        try {
            Map<String, Object> resources = new LinkedHashMap<>();
            for (Map.Entry<ResourceType, Double> entry : lost.entrySet()) {
                if (entry.getValue() > 0) resources.put(entry.getKey().name(), entry.getValue());
            }
            if (resources.isEmpty()) return;

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("victim_name", victim.getName());
            metadata.put("zone_id", zone.getId().toString());
            metadata.put("zone_name", zone.getName());
            MysterriaAuditEmitter.putPlayerLocation(metadata, victim);
            metadata.put("cause", "death_without_player_killer");
            metadata.put("resources", resources);

            DeathAudit.emit(plugin, "exclusion.resources_lost", AuditOutcome.COMMITTED, AuditRisk.NORMAL,
                    null, victim.getUniqueId(), null, "death_without_player_killer", metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Drops items from the victim's inventory according to dropChance, clears the physical inventory,
     * and returns the items that were NOT dropped.
     *
     * @param droppedStacks Receives material and amount of each dropped stack (for the audit row)
     * @return Array of items to be restored on respawn
     */
    private ItemStack[] dropItemsWithChance(Player victim, Location loc, double dropChance,
                                            List<Map<String, Object>> droppedStacks) {
        var rng = ThreadLocalRandom.current();
        var inv = victim.getInventory();
        ItemStack[] contents = inv.getContents(); // Includes main, armor, and offhand

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() == Material.AIR) continue;

            if (dropChance >= 1.0 || (dropChance > 0.0 && rng.nextDouble() < dropChance)) {
                if (loc.getWorld() != null) {
                    loc.getWorld().dropItemNaturally(loc, item.clone());
                    droppedStacks.add(DeathAudit.stack(item));
                }
                contents[i] = null; // Mark as dropped, will not be restored
            }
        }

        // Always clear physical inventory to prevent other plugins (like Graves) from seeing items
        inv.clear();
        return contents;
    }

}
