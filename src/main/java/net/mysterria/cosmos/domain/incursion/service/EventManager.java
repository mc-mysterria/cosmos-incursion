package net.mysterria.cosmos.domain.incursion.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.beacon.service.BeaconManager;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.beacon.service.BeaconUIManager;
import net.mysterria.cosmos.domain.incursion.model.IncursionEvent;
import net.mysterria.cosmos.domain.incursion.model.source.EventState;
import net.mysterria.cosmos.domain.incursion.task.ZoneBoundaryParticleTask;
import net.mysterria.cosmos.toolkit.map.MapIntegration;
import net.mysterria.cosmos.toolkit.BuffToolkit;
import net.mysterria.cosmos.toolkit.DiscordToolkit;
import net.mysterria.cosmos.domain.beacon.task.BeaconCaptureTask;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import net.mysterria.cosmos.toolkit.ZonePlacerToolkit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import dev.ua.ikeepcalm.coi.api.audit.AuditOutcome;
import dev.ua.ikeepcalm.coi.api.audit.AuditRisk;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.List;

public class EventManager {

    private final CosmosIncursion plugin;
    private final ZoneManager zoneManager;
    private final BeaconManager beaconManager;
    private final BuffToolkit buffToolkit;
    private final MapIntegration mapIntegration;
    private final BeaconUIManager beaconUIManager;
    private final DiscordToolkit discordToolkit;
    private final RewardDistributor rewardDistributor;
    private final EventHistoryStore eventHistoryStore;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;
    private final java.util.Set<Integer> announcedMinutes;
    private EventState currentState;
    private IncursionEvent activeEvent;
    private long cooldownEndTime;
    private BeaconCaptureTask beaconCaptureTask;
    private ZoneBoundaryParticleTask boundaryParticleTask;
    /** Final lifecycle reason used to distinguish normal completion from an admin/shutdown stop. */
    private String terminationReason = "duration_elapsed";

    public EventManager(CosmosIncursion plugin, ZoneManager zoneManager, BeaconManager beaconManager,
                        BuffToolkit buffToolkit, MapIntegration mapIntegration,
                        BeaconUIManager beaconUIManager, DiscordToolkit discordToolkit,
                        RewardDistributor rewardDistributor, EventHistoryStore eventHistoryStore) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.beaconManager = beaconManager;
        this.buffToolkit = buffToolkit;
        this.mapIntegration = mapIntegration;
        this.beaconUIManager = beaconUIManager;
        this.discordToolkit = discordToolkit;
        this.rewardDistributor = rewardDistributor;
        this.eventHistoryStore = eventHistoryStore;
        this.config = plugin.getConfigLoader().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
        this.currentState = EventState.IDLE;
        this.cooldownEndTime = eventHistoryStore.getCooldownEndTime();
        this.beaconCaptureTask = null;
        this.boundaryParticleTask = null;
        this.announcedMinutes = new java.util.HashSet<>();
    }

    /**
     * Main tick method called every second by EventCheckTask
     */
    public void tick() {
        switch (currentState) {
            case IDLE -> tickIdle();
            case STARTING -> tickStarting();
            case ACTIVE -> tickActive();
            case ENDING -> tickEnding();
        }
    }

    /**
     * IDLE state: Check trigger conditions
     */
    private void tickIdle() {
        // Skip auto-start if disabled in config
        if (!config.isEventAutoStart()) {
            return;
        }

        // Check if still on cooldown
        if (System.currentTimeMillis() < cooldownEndTime) {
            return;
        }

        // Check player count
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        if (onlinePlayers >= config.getMinPlayers()) {
            startEvent(false);
        }
    }

    /**
     * STARTING state: Countdown in progress
     */
    private void tickStarting() {
        if (activeEvent == null) {
            plugin.log("Warning: No active event in STARTING state, transitioning to IDLE");
            transitionTo(EventState.IDLE);
            return;
        }

        // Tick countdown
        boolean countdownComplete = activeEvent.tickCountdown();

        // Broadcast countdown at specific intervals
        int remaining = activeEvent.getCountdownRemaining();
        if (remaining == 60 || remaining == 30 || remaining == 10 || remaining <= 5) {
            String message = config.getMsgEventStarting().replace("%countdown%", String.valueOf(remaining));
            broadcastMessage(message);
        }

        // Transition to ACTIVE when countdown reaches 0
        if (countdownComplete) {
            transitionTo(EventState.ACTIVE);
        }
    }

    /**
     * ACTIVE state: Event running
     */
    private void tickActive() {
        if (activeEvent == null) {
            plugin.log("Warning: No active event in ACTIVE state, transitioning to IDLE");
            transitionTo(EventState.IDLE);
            return;
        }

        // Announce time remaining at intervals
        announceTimeRemaining();

        // Check if event duration has elapsed
        if (activeEvent.shouldEnd()) {
            endEvent();
        }
    }

    /**
     * Announce time remaining at specific intervals
     */
    private void announceTimeRemaining() {
        long remainingMillis = activeEvent.getRemainingTime();
        int remainingMinutes = (int) (remainingMillis / 60_000L);
        int remainingSeconds = (int) ((remainingMillis % 60_000L) / 1000L);

        // Announce at: 25, 20, 15, 10, 5, 3, 2, 1 minutes
        int[] minuteThresholds = {25, 20, 15, 10, 5, 3, 2, 1};

        for (int threshold : minuteThresholds) {
            if (remainingMinutes == threshold && remainingSeconds >= 58 && !announcedMinutes.contains(threshold)) {
                String message = config.getMsgEventTimeRemaining()
                        .replace("%minutes%", String.valueOf(threshold));
                broadcastMessage(message);
                announcedMinutes.add(threshold);
                return;
            }
        }

        // Announce at 30 seconds
        if (remainingMinutes == 0 && remainingSeconds == 30 && !announcedMinutes.contains(0)) {
            String message = config.getMsgEventTimeRemaining()
                    .replace("%minutes%", "0")
                    .replace("minutes", "30 seconds");
            broadcastMessage(message);
            announcedMinutes.add(0);
        }
    }

    /**
     * ENDING state: Cleanup in progress
     */
    private void tickEnding() {
        // Immediate transition to IDLE (cleanup happens in transitionTo)
        transitionTo(EventState.IDLE);
    }

    /**
     * Transition to a new state
     */
    private void transitionTo(EventState newState) {
        if (currentState == newState) {
            return;
        }

        plugin.log("Event state transition: " + currentState + " -> " + newState);
        EventState oldState = currentState;
        currentState = newState;

        // Execute transition logic
        switch (newState) {
            case IDLE -> onEnterIdle(oldState);
            case STARTING -> onEnterStarting();
            case ACTIVE -> onEnterActive();
            case ENDING -> onEnterEnding();
        }
    }

    private void onEnterIdle(EventState fromState) {
        // Cleanup from previous event
        if (activeEvent != null) {
            // Remove effects from all players still in zones before cleanup
            plugin.getPlayerStateManager().getAllStates().forEach(state -> {
                org.bukkit.entity.Player player = plugin.getServer().getPlayer(state.getPlayerId());
                if (player != null && player.isOnline()) {
                    plugin.getEffectsToolkit().removeEffects(player);
                    plugin.log("Removed zone effects from player: " + player.getName());
                }
            });

            // Clear player states
            plugin.getPlayerStateManager().clearAll();

            // Deactivate all zones
            zoneManager.deactivateAllZones();

            // Remove BlueMap markers
            if (mapIntegration.isAvailable()) {
                mapIntegration.removeAllZoneMarkers();
                mapIntegration.removeAllBeaconMarkers();
            }

            // Stop beacon capture task
            if (beaconCaptureTask != null) {
                beaconCaptureTask.cancel();
                beaconCaptureTask = null;
                plugin.log("Stopped beacon capture task");
            }

            // Stop boundary particle task
            if (boundaryParticleTask != null) {
                boundaryParticleTask.cancel();
                boundaryParticleTask = null;
                plugin.log("Stopped boundary particle task");
            }

            // Cleanup all UI elements
            beaconUIManager.cleanupAllUI();

            // Rank towns by contribution and distribute podium buffs, proportional resources
            // (with Nation amplification), and MVP rewards. Replaces the old single-winner
            // beacon-ownership check, which credited only whichever town held a beacon at the
            // exact instant the event ended.
            if (beaconManager.hasBeacons()) {
                rewardDistributor.distribute(activeEvent);

                // Reset all beacons
                beaconManager.resetAllCaptures();
            }

            plugin.log("Event ended. Stats - Kills: " + activeEvent.getTotalKills() +
                       ", Deaths: " + activeEvent.getTotalDeaths());

            // Clear auto-generated beacons
            beaconManager.clearAllBeacons();

            // Clear all death penalty cooldowns
            plugin.getDeathHandler().clearAllCooldowns();
            plugin.log("Cleared all death penalty cooldowns");

            // Emit exactly one terminal lifecycle record after every synchronous reward and
            // cleanup mutation has completed. EventHistoryStore remains the operational source
            // of truth for holder/cooldown/pending-reward behavior.
            AuditOutcome terminalOutcome = "duration_elapsed".equals(terminationReason)
                    ? AuditOutcome.COMMITTED : AuditOutcome.CANCELLED;
            MysterriaAuditEmitter.emit(plugin,
                    terminalOutcome == AuditOutcome.COMMITTED ? "incursion.completed" : "incursion.cancelled",
                    terminalOutcome,
                    terminalOutcome == AuditOutcome.COMMITTED ? AuditRisk.NORMAL : AuditRisk.HIGH,
                    activeEvent.getEventId(), activeEvent.getEventId().toString(), null, null, null,
                    terminationReason,
                    java.util.Map.of("kills", activeEvent.getTotalKills(),
                            "deaths", activeEvent.getTotalDeaths(),
                            "zone_count", activeEvent.getIncursionZones().size()));

            activeEvent = null;
        }

        // Start cooldown
        if (fromState == EventState.ENDING) {
            cooldownEndTime = System.currentTimeMillis() + (config.getCooldownMinutes() * 60_000L);
            eventHistoryStore.setCooldownEndTime(cooldownEndTime);
            eventHistoryStore.save();
            plugin.log("Cooldown started for " + config.getCooldownMinutes() + " minutes");
        }
    }

    private void onEnterStarting() {
        if (activeEvent == null) {
            plugin.log("Warning: Entering STARTING state with no active event");
            transitionTo(EventState.IDLE);
            return;
        }

        // Generate zones BEFORE setting the countdown so a generation failure cancels cleanly
        List<IncursionZone> incursionZones;
        try {
            ZonePlacerToolkit placementStrategy = new ZonePlacerToolkit(plugin);
            int zoneCount = placementStrategy.calculateZoneCount();
            incursionZones = placementStrategy.generateZones(zoneCount);
        } catch (Exception e) {
            plugin.log("Exception during zone generation, aborting event: " + e.getMessage());
            broadcastMessage("<red>[Cosmos Incursion]</red> <white>Event cancelled - zone generation failed</white>");
            MysterriaAuditEmitter.emit(plugin, "incursion.cancelled", AuditOutcome.CANCELLED, AuditRisk.HIGH,
                    activeEvent.getEventId(), activeEvent.getEventId().toString(), null, null, null,
                    "zone_generation_failed", java.util.Map.of("error", String.valueOf(e.getMessage())));
            activeEvent = null;
            transitionTo(EventState.IDLE);
            return;
        }

        if (incursionZones.isEmpty()) {
            plugin.log("Failed to generate any zones, aborting event");
            broadcastMessage("<red>[Cosmos Incursion]</red> <white>Event cancelled - could not find suitable zone locations</white>");
            MysterriaAuditEmitter.emit(plugin, "incursion.cancelled", AuditOutcome.CANCELLED, AuditRisk.NORMAL,
                    activeEvent.getEventId(), activeEvent.getEventId().toString(), null, null, null,
                    "no_zones_generated", java.util.Map.of());
            activeEvent = null;
            transitionTo(EventState.IDLE);
            return;
        }

        // Set countdown only after zones are confirmed
        activeEvent.setCountdown(config.getCountdownSeconds());

        // Register zones
        zoneManager.clearAllZones();
        for (IncursionZone incursionZone : incursionZones) {
            zoneManager.registerZone(incursionZone);
            activeEvent.addZone(incursionZone);
        }

        // Generate beacons automatically for all zones
        beaconManager.generateBeaconsForZones(incursionZones);

        // Notify Discord that the event is starting
        discordToolkit.sendEventStarting(config.getCountdownSeconds(), incursionZones.size());

        plugin.log("Event starting with " + incursionZones.size() + " zones, " + config.getCountdownSeconds() + "s countdown");
    }

    private void onEnterActive() {
        if (activeEvent == null) {
            plugin.log("Warning: Entering ACTIVE state with no active event");
            transitionTo(EventState.IDLE);
            return;
        }

        if (activeEvent.getIncursionZones().isEmpty()) {
            plugin.log("Warning: Entering ACTIVE state with no zones — aborting event");
            broadcastMessage("<red>[Cosmos Incursion]</red> <white>Event cancelled - no zones were generated</white>");
            activeEvent = null;
            transitionTo(EventState.IDLE);
            return;
        }

        // Clear announcement tracking for new event
        announcedMinutes.clear();

        // Fresh per-player contribution scores for this event's MVP payout
        plugin.getContributionTracker().reset();

        // Announce the current title holder, if any — gives everyone a target to dethrone
        if (eventHistoryStore.getHolderTownId() != 0) {
            String holderMessage = "<red>[Cosmos Incursion]</red> <white>Current Holder: </white>" +
                    "<gold>" + eventHistoryStore.getHolderTownName() + "</gold> " +
                    "<gray>(streak: " + eventHistoryStore.getHolderStreak() + ")</gray>";
            broadcastMessage(holderMessage);
        }

        // Activate all zones
        zoneManager.activateAllZones();

        // Handle players who are already inside zones when event starts
        handleExistingPlayersInZones();

        // Create BlueMap markers for all zones
        if (mapIntegration.isAvailable()) {
            for (IncursionZone incursionZone : activeEvent.getIncursionZones()) {
                mapIntegration.createZoneMarker(incursionZone);
            }

            // Create BlueMap markers for all beacons
            double captureRadius = config.getBeaconCaptureRadius();
            for (var beacon : beaconManager.getAllBeacons()) {
                mapIntegration.createBeaconMarker(beacon, captureRadius);
            }
        }

        // Broadcast event started
        String message = config.getMsgEventStarted()
                .replace("%zones%", String.valueOf(activeEvent.getIncursionZones().size()));
        broadcastMessage(message);

        // Broadcast zone coordinates
        broadcastMessage("<red>[Cosmos Incursion]</red> <white>Zone Locations:</white>");
        for (IncursionZone zone : activeEvent.getIncursionZones()) {
            Location center = zone.getCenter();
            String coordMessage = String.format(
                    "<gray>• <yellow>%s</yellow>: X: <white>%.0f</white>, Y: <white>%.0f</white>, Z: <white>%.0f</white></gray>",
                    zone.getName().replace('_', ' '),
                    center.getX(),
                    center.getY(),
                    center.getZ()
            );
            broadcastMessage(coordMessage);
        }

        // Initialize beacon capture states
        if (beaconManager.hasBeacons()) {
            beaconManager.initializeCaptureStates();

            // Start beacon capture task (runs every second)
            beaconCaptureTask = new BeaconCaptureTask(plugin, beaconManager, beaconUIManager);
            beaconCaptureTask.runTaskTimer(plugin, 0L, 20L);

            plugin.log("Started beacon capture task for " + beaconManager.getBeaconCount() + " beacons");

            // Initialize UI systems
            beaconUIManager.initializeEventUI();

            // Create physical beacons
            for (var beacon : beaconManager.getAllBeacons()) {
                beaconUIManager.createPhysicalBeacon(beacon);
            }
        }

        // Start zone boundary particle task
        if (config.isZoneBoundaryParticlesEnabled()) {
            long particleInterval = config.getZoneBoundaryParticleUpdateTicks();
            boundaryParticleTask = new ZoneBoundaryParticleTask(plugin, zoneManager);
            boundaryParticleTask.runTaskTimer(plugin, 0L, particleInterval);
            plugin.log("Started zone boundary particle task");
        }

        plugin.log("Event is now ACTIVE");
        MysterriaAuditEmitter.emitCommitted(plugin, "incursion.started", activeEvent.getEventId(),
                activeEvent.getEventId().toString(), null, null, null,
                java.util.Map.of("zone_count", activeEvent.getIncursionZones().size(),
                        "beacon_count", beaconManager.getBeaconCount(),
                        "countdown_seconds", config.getCountdownSeconds()));
    }

    private void onEnterEnding() {
        // Broadcast ending message
        broadcastMessage(config.getMsgEventEnding());

        // Despawn all remaining Hollow Body NPCs
        if (plugin.getCitizensToolkit() != null && plugin.getCitizensToolkit().isAvailable()) {
            plugin.getCitizensToolkit().despawnAllHollowBodies();
            plugin.log("Despawned all remaining Hollow Body NPCs");
        }

        // Contribution scoring and reward distribution happen in onEnterIdle(), via
        // RewardDistributor — see there for the podium/proportional/nation payout logic.

        plugin.log("Event is ending...");
    }

    /**
     * Start an event
     * @param forced If true, bypasses cooldown and player count checks
     */
    public boolean startEvent(boolean forced) {
        if (currentState != EventState.IDLE) {
            return false;
        }

        // Check cooldown (unless forced)
        if (!forced && System.currentTimeMillis() < cooldownEndTime) {
            long remainingMinutes = (cooldownEndTime - System.currentTimeMillis()) / 60_000L;
            plugin.log("Cannot start event - cooldown active for " + remainingMinutes + " more minutes");
            return false;
        }

        // Check player count (unless forced)
        if (!forced && Bukkit.getOnlinePlayers().size() < config.getMinPlayers()) {
            plugin.log("Cannot start event - not enough players online");
            return false;
        }

        // Create new event
        long durationMillis = config.getDurationMinutes() * 60_000L;
        activeEvent = new IncursionEvent(durationMillis);
        terminationReason = "duration_elapsed";

        MysterriaAuditEmitter.emitCommitted(plugin, "incursion.created", activeEvent.getEventId(),
                activeEvent.getEventId().toString(), null, null, forced ? "forced" : "automatic",
                java.util.Map.of("forced", forced,
                        "duration_minutes", config.getDurationMinutes(),
                        "min_players", config.getMinPlayers()));

        // Transition to STARTING
        transitionTo(EventState.STARTING);

        return true;
    }

    /**
     * End the current event
     */
    public void endEvent() {
        if (currentState != EventState.ACTIVE) {
            return;
        }

        terminationReason = "duration_elapsed";
        transitionTo(EventState.ENDING);
    }

    /**
     * Force stop the event immediately
     */
    public boolean forceStop() {
        if (currentState == EventState.IDLE) {
            return false;
        }

        broadcastMessage("<red>[Cosmos Incursion]</red> <white>Event has been force-stopped by an administrator</white>");
        terminationReason = "admin_force_stop";
        transitionTo(EventState.ENDING);
        return true;
    }

    /**
     * Finalizes a pending event synchronously before the plugin is disabled.
     * Bukkit tasks are cancelled as part of shutdown, so waiting for the normal
     * ENDING tick would otherwise discard the event and its rewards.
     */
    public void finalizeForShutdown() {
        if (currentState == EventState.ACTIVE) {
            terminationReason = "plugin_shutdown";
            transitionTo(EventState.ENDING);
            transitionTo(EventState.IDLE);
        } else if (currentState == EventState.STARTING) {
            terminationReason = "plugin_shutdown";
            transitionTo(EventState.ENDING);
            transitionTo(EventState.IDLE);
        } else if (currentState == EventState.ENDING) {
            transitionTo(EventState.IDLE);
        }
    }

    /**
     * Handle players who are already inside zones when the event activates
     */
    private void handleExistingPlayersInZones() {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            // Skip Citizens NPCs (they have "NPC" metadata)
            if (player.hasMetadata("NPC")) {
                continue;
            }

            IncursionZone zone = zoneManager.getZoneAt(player.getLocation());

            if (zone != null) {
                // Player is inside a zone that just activated
                // Teleport them just outside the zone boundary
                Location safeLocation = zoneManager.findSafeLocationOutsideZone(player.getLocation(), zone);

                if (safeLocation != null) {
                    player.teleport(safeLocation);
                    player.sendMessage(miniMessage.deserialize(
                            "<red>[Cosmos Incursion]</red> <white>An incursion zone has appeared! You've been moved to safety.</white>"
                    ));
                    player.sendMessage(miniMessage.deserialize(
                            "<gray>You must consent to the zone rules before entering. Approach the zone to see the agreement.</gray>"
                    ));
                }
            }
        }
    }

    /**
     * Broadcast a message to all players
     */
    private void broadcastMessage(String message) {
        Component component = miniMessage.deserialize(message);
        Bukkit.getServer().sendMessage(component);
    }

    /**
     * Get current event state
     */
    public EventState getState() {
        return currentState;
    }

    /**
     * Get active event (nullable)
     */
    public IncursionEvent getActiveEvent() {
        return activeEvent;
    }

    /**
     * Get remaining cooldown in seconds
     */
    public long getRemainingCooldownSeconds() {
        if (System.currentTimeMillis() >= cooldownEndTime) {
            return 0;
        }
        return (cooldownEndTime - System.currentTimeMillis()) / 1000L;
    }

}
