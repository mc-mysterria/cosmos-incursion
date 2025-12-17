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
import org.bukkit.Location;

import java.util.List;

public class EventManager {

    private final CosmosIncursion plugin;
    private final ZoneManager zoneManager;
    private final BeaconManager beaconManager;
    private final BuffManager buffManager;
    private final BlueMapIntegration blueMapIntegration;
    private final net.mysterria.cosmos.beacon.ui.BeaconUIManager beaconUIManager;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;

    private EventState currentState;
    private IncursionEvent activeEvent;
    private long cooldownEndTime;
    private BeaconCaptureTask beaconCaptureTask;
    private net.mysterria.cosmos.task.ZoneBoundaryParticleTask boundaryParticleTask;
    private final java.util.Set<Integer> announcedMinutes;

    public EventManager(CosmosIncursion plugin, ZoneManager zoneManager, BeaconManager beaconManager,
                        BuffManager buffManager, BlueMapIntegration blueMapIntegration,
                        net.mysterria.cosmos.beacon.ui.BeaconUIManager beaconUIManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.beaconManager = beaconManager;
        this.buffManager = buffManager;
        this.blueMapIntegration = blueMapIntegration;
        this.beaconUIManager = beaconUIManager;
        this.config = plugin.getConfigManager().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
        this.currentState = EventState.IDLE;
        this.cooldownEndTime = 0;
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

        // Check if player count dropped too low
        int onlinePlayers = Bukkit.getOnlinePlayers().size();
        if (onlinePlayers < config.getMinPlayers()) {
            plugin.log("Player count dropped below minimum, ending event");
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
                    plugin.getEffectManager().removeEffects(player);
                    plugin.log("Removed zone effects from player: " + player.getName());
                }
            });

            // Clear player states
            plugin.getPlayerStateManager().clearAll();

            // Deactivate all zones
            zoneManager.deactivateAllZones();

            // Remove BlueMap markers
            if (blueMapIntegration.isAvailable()) {
                blueMapIntegration.removeAllZoneMarkers();
                blueMapIntegration.removeAllBeaconMarkers();
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

            // Clear auto-generated beacons
            beaconManager.clearAllBeacons();

            // Reset all consent states
            plugin.getConsentGUI().resetAllConsents();

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

        // Generate beacons automatically for all zones
        beaconManager.generateBeaconsForZones(incursionZones);

        plugin.log("Event starting with " + incursionZones.size() + " zones, " + config.getCountdownSeconds() + "s countdown");
    }

    private void onEnterActive() {
        if (activeEvent == null) {
            plugin.log("Warning: Entering ACTIVE state with no active event");
            transitionTo(EventState.IDLE);
            return;
        }

        // Clear announcement tracking for new event
        announcedMinutes.clear();

        // Activate all zones
        zoneManager.activateAllZones();

        // Handle players who are already inside zones when event starts
        handleExistingPlayersInZones();

        // Create BlueMap markers for all zones
        if (blueMapIntegration.isAvailable()) {
            for (IncursionZone incursionZone : activeEvent.getIncursionZones()) {
                blueMapIntegration.createZoneMarker(incursionZone);
            }

            // Create BlueMap markers for all beacons
            double captureRadius = config.getBeaconCaptureRadius();
            for (var beacon : beaconManager.getAllBeacons()) {
                blueMapIntegration.createBeaconMarker(beacon, captureRadius);
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
                    zone.getName(),
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
            boundaryParticleTask = new net.mysterria.cosmos.task.ZoneBoundaryParticleTask(plugin, zoneManager);
            boundaryParticleTask.runTaskTimer(plugin, 0L, particleInterval);
            plugin.log("Started zone boundary particle task");
        }

        plugin.log("Event is now ACTIVE");
    }

    private void onEnterEnding() {
        // Broadcast ending message
        broadcastMessage(config.getMsgEventEnding());

        // Despawn all remaining Hollow Body NPCs
        if (plugin.getCitizensIntegration() != null && plugin.getCitizensIntegration().isAvailable()) {
            plugin.getCitizensIntegration().despawnAllHollowBodies();
            plugin.log("Despawned all remaining Hollow Body NPCs");
        }

        // Territory rewards are handled via beacon ownership in onEnterIdle()
        // The winning town (most beacon control time) receives the Acting Speed buff

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
     * Handle players who are already inside zones when the event activates
     */
    private void handleExistingPlayersInZones() {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            IncursionZone zone = zoneManager.getZoneAt(player.getLocation());

            if (zone != null) {
                // Player is inside a zone that just activated
                // Teleport them just outside the zone boundary
                Location safeLocation = findSafeLocationOutsideZone(player.getLocation(), zone);

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
     * Find a safe location just outside a zone boundary
     */
    private Location findSafeLocationOutsideZone(Location playerLoc, IncursionZone zone) {
        Location center = zone.getCenter();
        double radius = zone.getRadius();

        // Calculate direction vector from center to player
        double dx = playerLoc.getX() - center.getX();
        double dz = playerLoc.getZ() - center.getZ();

        // Normalize and scale to just outside the radius
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.1) {
            // Player is at center, push them north
            dx = 0;
            dz = -(radius + 5);
        } else {
            double scale = (radius + 5) / distance; // 5 blocks outside the radius
            dx *= scale;
            dz *= scale;
        }

        // Create safe location
        Location safeLoc = center.clone().add(dx, 0, dz);
        safeLoc.setY(playerLoc.getY());

        // Find safe ground
        org.bukkit.World world = safeLoc.getWorld();
        if (world != null) {
            // Move up if in ground
            while (safeLoc.getY() < 256 && !world.getBlockAt(safeLoc).isPassable()) {
                safeLoc.add(0, 1, 0);
            }

            // Move down if in air
            while (safeLoc.getY() > 0 && world.getBlockAt(safeLoc.clone().subtract(0, 1, 0)).isPassable()) {
                safeLoc.subtract(0, 1, 0);
            }
        }

        return safeLoc;
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
