package net.mysterria.cosmos.event;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.beacon.BeaconManager;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.event.source.EventState;
import net.mysterria.cosmos.integration.BlueMapIntegration;
import net.mysterria.cosmos.reward.BuffManager;
import net.mysterria.cosmos.task.BeaconCaptureTask;
import net.mysterria.cosmos.zone.IncursionZone;
import net.mysterria.cosmos.zone.ZoneManager;
import net.mysterria.cosmos.zone.ZonePlacer;
import org.bukkit.Bukkit;

import java.util.List;

public class EventManager {

    private final CosmosIncursion plugin;
    private final ZoneManager zoneManager;
    private final BeaconManager beaconManager;
    private final BuffManager buffManager;
    private final BlueMapIntegration blueMapIntegration;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;

    private EventState currentState;
    private IncursionEvent activeEvent;
    private long cooldownEndTime;
    private BeaconCaptureTask beaconCaptureTask;

    public EventManager(CosmosIncursion plugin, ZoneManager zoneManager, BeaconManager beaconManager,
                        BuffManager buffManager, BlueMapIntegration blueMapIntegration) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.beaconManager = beaconManager;
        this.buffManager = buffManager;
        this.blueMapIntegration = blueMapIntegration;
        this.config = plugin.getConfigManager().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
        this.currentState = EventState.IDLE;
        this.cooldownEndTime = 0;
        this.beaconCaptureTask = null;
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

        // Check if event duration has elapsed
        if (activeEvent.shouldEnd()) {
            endEvent();
        }

        // Check if player count dropped too low
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        if (onlinePlayers < config.getMinPlayers()) {
            plugin.log("Player count dropped below minimum, ending event");
            endEvent();
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
            // Deactivate all zones
            zoneManager.deactivateAllZones();

            // Remove BlueMap markers
            if (blueMapIntegration.isAvailable()) {
                blueMapIntegration.removeAllZoneMarkers();
            }

            // Stop beacon capture task
            if (beaconCaptureTask != null) {
                beaconCaptureTask.cancel();
                beaconCaptureTask = null;
                plugin.log("Stopped beacon capture task");
            }

            // Calculate winning town from beacon ownership
            if (beaconManager.hasBeacons()) {
                int winningTownId = beaconManager.getWinningTown();
                if (winningTownId != 0) {
                    plugin.log("Winning town (most beacon control): " + winningTownId);

                    // Award Acting Speed buff to winning town
                    buffManager.awardBuffToTown(winningTownId);
                } else {
                    plugin.log("No winning town (no beacons were captured)");
                }

                // Reset all beacons
                beaconManager.resetAllCaptures();
            }

            plugin.log("Event ended. Stats - Kills: " + activeEvent.getTotalKills() +
                    ", Deaths: " + activeEvent.getTotalDeaths());

            activeEvent = null;
        }

        // Start cooldown
        if (fromState == EventState.ENDING) {
            cooldownEndTime = System.currentTimeMillis() + (config.getCooldownMinutes() * 60_000L);
            plugin.log("Cooldown started for " + config.getCooldownMinutes() + " minutes");
        }
    }

    private void onEnterStarting() {
        if (activeEvent == null) {
            plugin.log("Warning: Entering STARTING state with no active event");
            transitionTo(EventState.IDLE);
            return;
        }

        // Set countdown
        activeEvent.setCountdown(config.getCountdownSeconds());

        // Generate zones
        ZonePlacer placementStrategy = new ZonePlacer(plugin);
        int zoneCount = placementStrategy.calculateZoneCount();
        List<IncursionZone> incursionZones = placementStrategy.generateZones(zoneCount);

        if (incursionZones.isEmpty()) {
            plugin.log("Failed to generate any zones, aborting event");
            broadcastMessage("<red>[Cosmos Incursion]</red> <white>Event cancelled - could not find suitable zone locations</white>");
            activeEvent = null;
            transitionTo(EventState.IDLE);
            return;
        }

        // Register zones
        zoneManager.clearAllZones();
        for (IncursionZone incursionZone : incursionZones) {
            zoneManager.registerZone(incursionZone);
            activeEvent.addZone(incursionZone);
        }

        plugin.log("Event starting with " + incursionZones.size() + " zones, " + config.getCountdownSeconds() + "s countdown");
    }

    private void onEnterActive() {
        if (activeEvent == null) {
            plugin.log("Warning: Entering ACTIVE state with no active event");
            transitionTo(EventState.IDLE);
            return;
        }

        // Activate all zones
        zoneManager.activateAllZones();

        // Create BlueMap markers for all zones
        if (blueMapIntegration.isAvailable()) {
            for (IncursionZone incursionZone : activeEvent.getIncursionZones()) {
                blueMapIntegration.createZoneMarker(incursionZone);
            }
        }

        // Broadcast event started
        String message = config.getMsgEventStarted()
                .replace("%zones%", String.valueOf(activeEvent.getIncursionZones().size()));
        broadcastMessage(message);

        // Initialize beacon capture states
        if (beaconManager.hasBeacons()) {
            beaconManager.initializeCaptureStates();

            // Start beacon capture task (runs every second)
            beaconCaptureTask = new BeaconCaptureTask(plugin, beaconManager);
            beaconCaptureTask.runTaskTimer(plugin, 0L, 20L);

            plugin.log("Started beacon capture task for " + beaconManager.getBeaconCount() + " beacons");
        }

        plugin.log("Event is now ACTIVE");
    }

    private void onEnterEnding() {
        // Broadcast ending message
        broadcastMessage(config.getMsgEventEnding());

        // TODO: Session 8 - Despawn all Hollow Body NPCs
        // TODO: Session 11 - Calculate territory rewards

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
        transitionTo(EventState.ENDING);
        return true;
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
