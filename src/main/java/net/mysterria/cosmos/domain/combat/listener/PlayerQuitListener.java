package net.mysterria.cosmos.domain.combat.listener;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.beacon.service.BeaconUIManager;
import net.mysterria.cosmos.domain.combat.service.CombatLogHandler;
import net.mysterria.cosmos.domain.combat.service.DeathAudit;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.toolkit.BuffToolkit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for player disconnects to:
 * - Spawn Hollow Body NPCs for combat logging
 * - Clean up buff tracking
 */
public class PlayerQuitListener implements Listener {

    private final CosmosIncursion plugin;
    private final CombatLogHandler combatLogHandler;
    private final BuffToolkit buffToolkit;
    private final BeaconUIManager beaconUIManager;

    public PlayerQuitListener(CosmosIncursion plugin, CombatLogHandler combatLogHandler,
                              BuffToolkit buffToolkit,
                              BeaconUIManager beaconUIManager) {
        this.plugin = plugin;
        this.combatLogHandler = combatLogHandler;
        this.buffToolkit = buffToolkit;
        this.beaconUIManager = beaconUIManager;
    }

    private void dropPermanentZoneBuffer(Player player) {
        PermanentZone pZone = plugin.getPermanentZoneManager().getPlayerZone(player.getUniqueId());
        if (pZone == null) return;
        PlayerResourceBuffer buffer = plugin.getPermanentZoneManager().getBuffer(player.getUniqueId());
        if (buffer.isEmpty()) return;
        // Amounts carried, copied before the spill clears the buffer (for the audit row only)
        Map<ResourceType, Double> carried = buffer.snapshot();
        Location loc = player.getLocation();
        try {
            // Same spill as the spectator-mode path: exact amounts as reclaimable items
            plugin.getPermanentZoneManager().dropBufferAsItems(player, loc);
        } catch (RuntimeException failure) {
            emitResourcesSpilled(player, pZone, loc, carried, failure);
            throw failure;
        }
        emitResourcesSpilled(player, pZone, loc, carried, null);
    }

    /**
     * Records the carried resources a disconnecting player left on the ground: one reclaimable item
     * per resource type holding its exact amount, the same spill as spectator mode. Built from the
     * buffer copy, zone and location the handler already holds; {@code thrown} is the exception the
     * drop threw (some ground items may exist and the buffer was not cleared).
     */
    private void emitResourcesSpilled(Player player, PermanentZone zone, Location location,
                                      Map<ResourceType, Double> carried, RuntimeException thrown) {
        try {
            boolean spawned = thrown == null && location.getWorld() != null;
            Map<String, Object> resources = new LinkedHashMap<>();
            List<Map<String, Object>> dropped = new ArrayList<>();
            for (Map.Entry<ResourceType, Double> entry : carried.entrySet()) {
                if (entry.getValue() <= 0) continue;
                resources.put(entry.getKey().name(), entry.getValue());
                if (spawned) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("resource", entry.getKey().name());
                    item.put("amount", entry.getValue());
                    dropped.add(item);
                }
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("player_name", player.getName());
            metadata.put("zone_id", zone.getId().toString());
            metadata.put("zone_name", zone.getName());
            MysterriaAuditEmitter.putLocation(metadata, location);
            metadata.put("trigger", "quit");
            metadata.put("resources", resources);
            metadata.put("dropped_item_count", dropped.size());
            metadata.put("dropped", dropped);
            if (thrown != null) metadata.put("error", thrown.getClass().getSimpleName());

            DeathAudit.emit(plugin, "exclusion.resources_spilled",
                    spawned ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, AuditRisk.NORMAL,
                    null, player.getUniqueId(), null,
                    thrown != null ? "spill_threw" : spawned ? "quit" : "no_world", metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Handle potential combat logging
        combatLogHandler.handleDisconnect(player);

        // Drop permanent zone resource buffer at disconnect location
        dropPermanentZoneBuffer(player);

        // Clean up buff tracking
        buffToolkit.handlePlayerQuit(player);

        // Clean up beacon UI
        beaconUIManager.handlePlayerQuit(player);

        // Clean up permanent zone tracking
        plugin.getPermanentZoneManager().updatePlayerZone(player.getUniqueId(), null);
    }

}
