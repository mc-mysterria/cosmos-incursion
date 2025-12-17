package net.mysterria.cosmos.beacon.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.beacon.BeaconCapture;
import net.mysterria.cosmos.beacon.BeaconManager;
import net.mysterria.cosmos.beacon.SpiritBeacon;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import net.william278.husktowns.town.Town;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.time.Duration;
import java.util.*;

/**
 * Central manager for all beacon-related UI elements
 * Handles actionbars, bossbars, scoreboards, titles, sounds, and particles
 */
public class BeaconUIManager {

    private final CosmosIncursion plugin;
    private final BeaconManager beaconManager;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;
    private final Map<String, BeaconVisuals> physicalBeacons;
    // Per-player UI state tracking
    private final Map<UUID, PlayerBeaconUIState> playerStates;
    // Boss bars per beacon
    private final Map<String, BossBar> beaconBossBars;
    // Town color cache
    private final Map<Integer, String> townColorCache;
    // UI component managers
    private BeaconSoundManager soundManager;
    private BeaconParticleTask particleTask;

    public BeaconUIManager(CosmosIncursion plugin, BeaconManager beaconManager) {
        this.plugin = plugin;
        this.beaconManager = beaconManager;
        this.config = plugin.getConfigManager().getConfig();
        this.miniMessage = MiniMessage.miniMessage();

        this.playerStates = new HashMap<>();
        this.beaconBossBars = new HashMap<>();
        this.physicalBeacons = new HashMap<>();
        this.townColorCache = new HashMap<>();
    }

    /**
     * Initialize UI systems when event becomes active
     */
    public void initializeEventUI() {
        if (!config.isBeaconUIEnabled()) {
            return;
        }

        // Initialize sound manager
        if (config.isBeaconSoundsEnabled()) {
            soundManager = new BeaconSoundManager(config);
        }

        // Start particle task
        if (config.isBeaconParticlesEnabled()) {
            particleTask = new BeaconParticleTask(plugin, beaconManager, config, this);
            particleTask.runTaskTimer(plugin, 0L, config.getBeaconParticleUpdateTicks());
        }

        plugin.log("Beacon UI systems initialized");
    }

    /**
     * Main update method called from BeaconCaptureTask every second
     * @param capture The beacon capture state
     * @param beacon The beacon
     */
    public void updateBeaconUI(BeaconCapture capture, SpiritBeacon beacon) {
        if (!config.isBeaconUIEnabled()) {
            return;
        }

        String beaconId = beacon.id();
        List<Player> nearbyPlayers = getNearbyPlayers(beacon.location(), config.getBeaconUIRadius());

        for (Player player : nearbyPlayers) {
            PlayerBeaconUIState state = getOrCreatePlayerState(player);

            // Update actionbar
            updateActionBar(player, capture, beacon, state);

            // Update bossbar
            updateBossBar(player, capture, beacon, state);

            // Update scoreboard (every 2 seconds)
            updateScoreboard(player, state);

            // Play sounds
            if (soundManager != null) {
                soundManager.updateSounds(player, capture, beacon, state);
            }

            // Send titles on state changes
            sendBeaconTitle(player, capture, beacon, state);
        }

        // Remove players who moved away from all beacons
        removeDistantPlayers();
    }

    /**
     * Update actionbar message for a player
     */
    private void updateActionBar(Player player, BeaconCapture capture, SpiritBeacon beacon, PlayerBeaconUIState state) {
        Optional<Town> playerTown = TownsToolkit.getPlayerTown(player);
        boolean isObserver = playerTown.isEmpty();

        String message;
        if (isObserver) {
            // Observer mode - show status only
            message = buildObserverActionBar(capture, beacon);
        } else {
            // Participant mode - show capture progress
            message = buildParticipantActionBar(capture, beacon, playerTown.get());
        }

        // Only send if message changed
        if (!message.equals(state.getLastActionBarMessage())) {
            player.sendActionBar(miniMessage.deserialize(message));
            state.setLastActionBarMessage(message);
        }
    }

    /**
     * Build actionbar message for observer (no-town) players
     */
    private String buildObserverActionBar(BeaconCapture capture, SpiritBeacon beacon) {
        if (capture.isContested()) {
            return "<gray>Observing: " + beacon.name() + " - <yellow>CONTESTED</yellow></gray>";
        } else if (capture.getOwningTownId() != 0) {
            return String.format("<gray>Observing: %s - <white>%s</white> (%.0f%%)</gray>",
                    beacon.name(),
                    capture.getOwningTownName(),
                    (capture.getCaptureProgress() / config.getBeaconCapturePoints()) * 100);
        } else {
            return "<gray>Observing: " + beacon.name() + " - <white>Neutral</white></gray>";
        }
    }

    /**
     * Build actionbar message for participant (with-town) players
     */
    private String buildParticipantActionBar(BeaconCapture capture, SpiritBeacon beacon, Town playerTown) {
        boolean isOwnedByPlayer = capture.isOwnedBy(playerTown.getId());
        int percent = (int) ((capture.getCaptureProgress() / config.getBeaconCapturePoints()) * 100);

        if (capture.isContested()) {
            return "<red>⚠ CONTESTED - " + beacon.name() + "</red>";
        } else if (isOwnedByPlayer && percent >= 100) {
            return "<green>✓ Beacon Secured - " + beacon.name() + "</green>";
        } else if (isOwnedByPlayer) {
            return buildProgressBar(beacon.name(), percent, "gold");
        } else if (capture.getOwningTownId() != 0) {
            return "<red>Enemy Beacon - " + beacon.name() + " (" + capture.getOwningTownName() + ")</red>";
        } else {
            return buildProgressBar(beacon.name(), percent, "yellow");
        }
    }

    /**
     * Build progress bar for actionbar
     */
    private String buildProgressBar(String beaconName, int percent, String color) {
        int filledBars = percent / 10;
        int emptyBars = 10 - filledBars;

        String bar = "<" + color + ">⚡ Capturing " + beaconName + "... [" +
                     "█".repeat(Math.max(0, filledBars)) +
                     "░".repeat(Math.max(0, emptyBars)) +
                     "] " + percent + "%</" + color + ">";

        return bar;
    }

    /**
     * Update bossbar for a player
     */
    private void updateBossBar(Player player, BeaconCapture capture, SpiritBeacon beacon, PlayerBeaconUIState state) {
        String beaconId = beacon.id();
        BossBar bossBar = beaconBossBars.computeIfAbsent(beaconId, id -> createBossBar());

        // Update bossbar properties
        updateBossBarProperties(bossBar, capture, beacon, player);

        // Add player if not already added
        if (!bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
            state.getActiveBossbars().add(beaconId);
        }
    }

    /**
     * Create a new bossbar
     */
    private BossBar createBossBar() {
        return Bukkit.createBossBar("Beacon", BarColor.WHITE, BarStyle.SOLID);
    }

    /**
     * Update bossbar properties (title, color, progress)
     */
    private void updateBossBarProperties(BossBar bossBar, BeaconCapture capture, SpiritBeacon beacon, Player player) {
        // Update title
        String title;
        if (capture.getOwningTownId() != 0) {
            title = beacon.name() + " - Owner: " + capture.getOwningTownName();
        } else {
            title = beacon.name() + " - Neutral";
        }
        bossBar.setTitle(title);

        // Update progress
        double progress = Math.min(1.0, Math.max(0.0, capture.getCaptureProgress() / config.getBeaconCapturePoints()));
        bossBar.setProgress(progress);

        // Update color based on ownership
        Optional<Town> playerTown = TownsToolkit.getPlayerTown(player);
        if (capture.isContested()) {
            bossBar.setColor(BarColor.YELLOW);
        } else if (playerTown.isPresent() && capture.isOwnedBy(playerTown.get().getId())) {
            bossBar.setColor(BarColor.GREEN);
        } else if (capture.getOwningTownId() != 0) {
            bossBar.setColor(BarColor.RED);
        } else {
            bossBar.setColor(BarColor.WHITE);
        }
    }

    /**
     * Update scoreboard for a player
     */
    private void updateScoreboard(Player player, PlayerBeaconUIState state) {
        long updateInterval = config.getBeaconScoreboardUpdateTicks() * 50L; // Convert ticks to milliseconds

        if (!state.shouldUpdateScoreboard(updateInterval)) {
            return;
        }

        // Get or create scoreboard
        Scoreboard scoreboard = state.getScoreboard();
        if (scoreboard == null) {
            ScoreboardManager manager = Bukkit.getScoreboardManager();
            if (manager == null) return;

            scoreboard = manager.getNewScoreboard();
            state.setScoreboard(scoreboard);
            player.setScoreboard(scoreboard);
        }

        // Get or create objective
        Objective objective = scoreboard.getObjective("beacons");
        if (objective == null) {
            objective = scoreboard.registerNewObjective("beacons", "dummy",
                    miniMessage.deserialize("<gold>⚡ BEACON STATUS</gold>"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }

        // Clear existing scores and teams
        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }
        for (Team team : scoreboard.getTeams()) {
            team.unregister();
        }

        // Add beacon status for each beacon
        Optional<Town> playerTown = TownsToolkit.getPlayerTown(player);
        int score = 15;  // Start from top

        int beaconIndex = 0;
        for (BeaconCapture capture : beaconManager.getAllCaptureStates()) {
            SpiritBeacon beacon = capture.getBeacon();

            // Use regular spaces to create unique entries
            String entryName = " ".repeat(beaconIndex + 1);

            // Create team for this entry to support Adventure components
            Team team = scoreboard.registerNewTeam("team_" + beaconIndex);
            team.addEntry(entryName);

            // Set prefix (colored status) and suffix (beacon name)
            buildScoreboardLine(team, capture, beacon, playerTown);

            // Add score
            Score beaconScore = objective.getScore(entryName);
            beaconScore.setScore(score--);

            beaconIndex++;
        }

        state.recordScoreboardUpdate();
    }

    /**
     * Build scoreboard line for a beacon using Team prefix/suffix
     */
    private void buildScoreboardLine(Team team, BeaconCapture capture, SpiritBeacon beacon, Optional<Town> playerTown) {
        // Extract short identifier from beacon name
        String beaconName = extractShortBeaconName(beacon.name());
        int percent = (int) ((capture.getCaptureProgress() / config.getBeaconCapturePoints()) * 100);

        if (capture.isContested()) {
            // Contested - Yellow gradient with swords
            team.prefix(miniMessage.deserialize("<gradient:yellow:gold>⚔ CONTESTED</gradient>"));
            team.suffix(miniMessage.deserialize(" <gradient:white:gray>" + beaconName + "</gradient>"));
        } else if (capture.getOwningTownId() != 0) {
            // Owned by a town
            boolean isOwnedByPlayer = playerTown.isPresent() && capture.isOwnedBy(playerTown.get().getId());
            String owner = capture.getOwningTownName();

            // Truncate long town names
            if (owner.length() > 8) {
                owner = owner.substring(0, 8);
            }

            if (isOwnedByPlayer) {
                // Your town - Green gradient
                team.prefix(miniMessage.deserialize("<gradient:green:dark_green>✓ " + owner + "</gradient>"));
                team.suffix(miniMessage.deserialize(" <gradient:white:green>" + beaconName + " " + percent + "%</gradient>"));
            } else {
                // Enemy town - Red gradient
                team.prefix(miniMessage.deserialize("<gradient:red:dark_red>✗ " + owner + "</gradient>"));
                team.suffix(miniMessage.deserialize(" <gradient:white:red>" + beaconName + " " + percent + "%</gradient>"));
            }
        } else {
            // Neutral - Gray gradient
            team.prefix(miniMessage.deserialize("<gradient:gray:dark_gray>◆ Neutral</gradient>"));
            team.suffix(miniMessage.deserialize(" <gradient:white:gray>" + beaconName + "</gradient>"));
        }
    }

    /**
     * Extract a short identifier from beacon name
     * Examples: "North Zone - Beacon" -> "North"
     *          "Zone South - Beacon" -> "South"
     *          "Beacon #3" -> "#3"
     */
    private String extractShortBeaconName(String fullName) {
        // Remove common prefixes/suffixes
        String name = fullName.replace("Zone ", "")
                .replace(" Zone", "")
                .replace(" - Beacon", "")
                .replace("Beacon ", "")
                .trim();

        // If still too long, take first word only
        if (name.length() > 10) {
            String[] parts = name.split("[\\s-]+");
            if (parts.length > 0) {
                name = parts[0];
            }
        }

        // Final truncation if still too long
        if (name.length() > 10) {
            name = name.substring(0, 10);
        }

        return name;
    }

    /**
     * Send title message on important state changes
     */
    private void sendBeaconTitle(Player player, BeaconCapture capture, SpiritBeacon beacon, PlayerBeaconUIState state) {
        String beaconId = beacon.id();
        Optional<Town> playerTown = TownsToolkit.getPlayerTown(player);

        // Minimum cooldown between titles (10 seconds)
        long titleCooldown = 10000L;
        if (!state.canSendTitle(beaconId, titleCooldown)) {
            return;
        }

        // Check if player just entered beacon range
        boolean justEntered = !beaconId.equals(state.getCurrentBeaconId());
        if (justEntered && beacon.isWithinCaptureRadius(player.getLocation(), config.getBeaconCaptureRadius())) {
            sendEnteringTitle(player, beacon, playerTown.isPresent());
            state.recordTitleSent(beaconId);
            state.setCurrentBeaconId(beaconId);
            return;
        }

        // Check for state changes
        int currentStateHash = calculateTitleStateHash(capture, playerTown);
        if (!state.hasBeaconStateChanged(beaconId, currentStateHash)) {
            return;  // No state change
        }

        int previousHash = state.getLastBeaconStates().getOrDefault(beaconId, 0);
        state.updateBeaconState(beaconId, currentStateHash);

        // Determine what changed and send appropriate title
        if (playerTown.isPresent()) {
            Town town = playerTown.get();
            boolean nowOwned = capture.isOwnedBy(town.getId());
            boolean wasOwned = (previousHash & 0x1) == 1;  // First bit = was owned
            boolean nowContested = capture.isContested();
            boolean wasContested = ((previousHash >> 1) & 0x1) == 1;  // Second bit = was contested

            if (nowOwned && !wasOwned && capture.getCaptureProgress() >= config.getBeaconCapturePoints()) {
                // Successful capture
                sendCapturedTitle(player, beacon);
                state.recordTitleSent(beaconId);
            } else if (wasOwned && !nowOwned && capture.getOwningTownId() != 0) {
                // Lost beacon to enemy
                sendLostTitle(player, beacon, capture.getOwningTownName());
                state.recordTitleSent(beaconId);
            } else if (nowContested && !wasContested) {
                // Beacon became contested
                sendContestedTitle(player, beacon);
                state.recordTitleSent(beaconId);
            }
        }
    }

    /**
     * Calculate state hash for title change detection
     */
    private int calculateTitleStateHash(BeaconCapture capture, Optional<Town> playerTown) {
        int hash = 0;

        // Bit 0: Is owned by player's town
        if (playerTown.isPresent() && capture.isOwnedBy(playerTown.get().getId())) {
            hash |= 0x1;
        }

        // Bit 1: Is contested
        if (capture.isContested()) {
            hash |= 0x2;
        }

        // Bits 2-9: Owner town ID (for detecting ownership changes)
        hash |= (capture.getOwningTownId() & 0xFF) << 2;

        // Bits 10-17: Capture progress (in 10% increments)
        int progressBucket = (int) ((capture.getCaptureProgress() / config.getBeaconCapturePoints()) * 10);
        hash |= (progressBucket & 0xFF) << 10;

        return hash;
    }

    /**
     * Send "entering beacon range" title
     */
    private void sendEnteringTitle(Player player, SpiritBeacon beacon, boolean hasTown) {
        Component title = miniMessage.deserialize("<gold>⚡ BEACON DETECTED</gold>");
        Component subtitle;

        if (hasTown) {
            subtitle = miniMessage.deserialize("<white>Help your town capture " + beacon.name() + "!</white>");
        } else {
            subtitle = miniMessage.deserialize("<gray>Observing " + beacon.name() + "</gray>");
        }

        Title.Times times = Title.Times.times(
                Duration.ofMillis(500),   // fade in
                Duration.ofMillis(2000),  // stay
                Duration.ofMillis(500)    // fade out
        );

        player.showTitle(Title.title(title, subtitle, times));
    }

    /**
     * Send "beacon captured" title
     */
    private void sendCapturedTitle(Player player, SpiritBeacon beacon) {
        Component title = miniMessage.deserialize("<green>✓ BEACON CAPTURED!</green>");
        Component subtitle = miniMessage.deserialize("<white>Your town now controls " + beacon.name() + "</white>");

        Title.Times times = Title.Times.times(
                Duration.ofMillis(300),   // fade in
                Duration.ofMillis(2000),  // stay
                Duration.ofMillis(700)    // fade out
        );

        player.showTitle(Title.title(title, subtitle, times));
    }

    /**
     * Send "beacon lost" title
     */
    private void sendLostTitle(Player player, SpiritBeacon beacon, String enemyTown) {
        Component title = miniMessage.deserialize("<red>⚠ BEACON LOST!</red>");
        Component subtitle = miniMessage.deserialize("<white>" + enemyTown + " has taken " + beacon.name() + "</white>");

        Title.Times times = Title.Times.times(
                Duration.ofMillis(300),   // fade in
                Duration.ofMillis(2500),  // stay
                Duration.ofMillis(500)    // fade out
        );

        player.showTitle(Title.title(title, subtitle, times));
    }

    /**
     * Send "contested" warning title
     */
    private void sendContestedTitle(Player player, SpiritBeacon beacon) {
        Component title = miniMessage.deserialize("<yellow>⚔ CONTESTED!</yellow>");
        Component subtitle = miniMessage.deserialize("<white>Enemy forces detected at " + beacon.name() + "</white>");

        Title.Times times = Title.Times.times(
                Duration.ofMillis(300),   // fade in
                Duration.ofMillis(1500),  // stay
                Duration.ofMillis(500)    // fade out
        );

        player.showTitle(Title.title(title, subtitle, times));
    }

    /**
     * Create physical beacon blocks
     * @param beacon The beacon to visualize
     */
    public void createPhysicalBeacon(SpiritBeacon beacon) {
        if (!config.isBeaconPhysicalEnabled()) {
            return;
        }

        BeaconVisuals visuals = new BeaconVisuals(plugin, beacon);
        visuals.createBeacon();
        physicalBeacons.put(beacon.id(), visuals);
    }

    /**
     * Get or create player UI state
     */
    private PlayerBeaconUIState getOrCreatePlayerState(Player player) {
        return playerStates.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerBeaconUIState());
    }

    /**
     * Get players near a location within radius
     */
    public List<Player> getNearbyPlayers(Location location, double radius) {
        List<Player> nearby = new ArrayList<>();
        double radiusSquared = radius * radius;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(location.getWorld())) {
                double distanceSquared = player.getLocation().distanceSquared(location);
                if (distanceSquared <= radiusSquared) {
                    nearby.add(player);
                }
            }
        }

        return nearby;
    }

    /**
     * Remove players who are no longer near any beacon
     */
    private void removeDistantPlayers() {
        for (Iterator<Map.Entry<UUID, PlayerBeaconUIState>> it = playerStates.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PlayerBeaconUIState> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player == null || !player.isOnline()) {
                // Player offline - cleanup
                removePlayerFromBossBars(player, entry.getValue());
                clearPlayerScoreboard(player, entry.getValue());
                it.remove();
                continue;
            }

            // Check if player is near any beacon
            boolean nearAnyBeacon = false;
            for (SpiritBeacon beacon : beaconManager.getAllBeacons()) {
                if (beacon.isWithinCaptureRadius(player.getLocation(), config.getBeaconUIRadius())) {
                    nearAnyBeacon = true;
                    break;
                }
            }

            if (!nearAnyBeacon) {
                // Remove from all bossbars
                removePlayerFromBossBars(player, entry.getValue());
                // Clear scoreboard
                clearPlayerScoreboard(player, entry.getValue());
                entry.getValue().setLastActionBarMessage(""); // Reset for next time
            }
        }
    }

    /**
     * Clear player's scoreboard
     */
    private void clearPlayerScoreboard(Player player, PlayerBeaconUIState state) {
        if (state.getScoreboard() != null && player != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            state.setScoreboard(null);
        }
    }

    /**
     * Remove player from all bossbars
     */
    private void removePlayerFromBossBars(Player player, PlayerBeaconUIState state) {
        for (String beaconId : state.getActiveBossbars()) {
            BossBar bossBar = beaconBossBars.get(beaconId);
            if (bossBar != null) {
                bossBar.removePlayer(player);
            }
        }
        state.getActiveBossbars().clear();
    }

    /**
     * Handle player quit - cleanup all UI elements
     */
    public void handlePlayerQuit(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerBeaconUIState state = playerStates.remove(playerId);

        if (state == null) {
            return;
        }

        // Remove from all bossbars
        removePlayerFromBossBars(player, state);

        // Clear scoreboard
        if (state.getScoreboard() != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }

        // Clear state
        state.clear();

        plugin.log("Cleaned up beacon UI for player: " + player.getName());
    }

    /**
     * Cleanup all UI elements when event ends
     */
    public void cleanupAllUI() {
        plugin.log("Cleaning up all beacon UI elements...");

        // Remove all players from bossbars
        for (BossBar bossBar : beaconBossBars.values()) {
            bossBar.removeAll();
        }
        beaconBossBars.clear();

        // Clear all player states
        for (Map.Entry<UUID, PlayerBeaconUIState> entry : playerStates.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                // Clear scoreboard
                if (entry.getValue().getScoreboard() != null) {
                    player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                }
            }
            entry.getValue().clear();
        }
        playerStates.clear();

        // Remove physical beacons
        for (BeaconVisuals visuals : physicalBeacons.values()) {
            visuals.removeBeacon();
        }
        physicalBeacons.clear();

        // Cancel particle task
        if (particleTask != null) {
            particleTask.cancel();
            particleTask = null;
        }

        // Clear town color cache
        townColorCache.clear();

        plugin.log("Beacon UI cleanup complete");
    }

    /**
     * Get cached town color (for consistency)
     * @param townId The town ID
     * @return MiniMessage color tag
     */
    public String getTownColor(int townId) {
        return townColorCache.computeIfAbsent(townId, id -> {
            // Generate consistent color from town ID
            String[] colors = {"red", "blue", "green", "yellow", "purple", "aqua", "gold", "white",
                    "dark_red", "dark_blue", "dark_green", "dark_purple", "dark_aqua", "gray", "light_purple"};
            return colors[Math.abs(id % colors.length)];
        });
    }

    /**
     * Get player UI state (for external access)
     */
    public PlayerBeaconUIState getPlayerState(UUID playerId) {
        return playerStates.get(playerId);
    }
}
