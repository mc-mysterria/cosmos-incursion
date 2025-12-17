package net.mysterria.cosmos.domain.beacon.ui;

import net.mysterria.cosmos.domain.beacon.BeaconCapture;
import net.mysterria.cosmos.domain.beacon.SpiritBeacon;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import net.william278.husktowns.town.Town;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * Manages sound effects for beacon capture events
 * Includes spam prevention via cooldowns
 */
public class BeaconSoundManager {

    // Sound types
    private static final String SOUND_AMBIENT = "ambient";
    private static final String SOUND_PROGRESS = "progress";
    private static final String SOUND_COMPLETE = "complete";
    private static final String SOUND_ENEMY = "enemy";
    private static final String SOUND_LOST = "lost";
    // Cooldowns in milliseconds
    private static final long COOLDOWN_AMBIENT = 3000;    // 3 seconds
    private static final long COOLDOWN_PROGRESS = 2000;   // 2 seconds
    private static final long COOLDOWN_COMPLETE = 5000;   // 5 seconds
    private static final long COOLDOWN_ENEMY = 5000;      // 5 seconds
    private static final long COOLDOWN_LOST = 10000;      // 10 seconds
    // Sound radius
    private static final double SOUND_RADIUS = 50.0;
    private final CosmosConfig config;

    public BeaconSoundManager(CosmosConfig config) {
        this.config = config;
    }

    /**
     * Update sounds for a player near a beacon
     * Called every second from BeaconUIManager
     */
    public void updateSounds(Player player, BeaconCapture capture, SpiritBeacon beacon, PlayerBeaconUIState state) {
        String beaconId = beacon.id();
        Optional<Town> playerTown = TownsToolkit.getPlayerTown(player);

        // Observers only hear ambient
        if (playerTown.isEmpty()) {
            playAmbientSound(player, beacon, state);
            return;
        }

        Town town = playerTown.get();
        boolean isOwnedByPlayer = capture.isOwnedBy(town.getId());
        int previousStateHash = getPreviousStateHash(state, beaconId, capture);
        int currentStateHash = calculateStateHash(capture);

        // Detect state changes
        boolean stateChanged = state.hasBeaconStateChanged(beaconId, currentStateHash);

        if (stateChanged) {
            state.updateBeaconState(beaconId, currentStateHash);

            // Check for specific state changes
            if (capture.isContested() && previousStateHash != currentStateHash) {
                playEnemyDetectedSound(player, beacon, state);
            } else if (isOwnedByPlayer && capture.getCaptureProgress() >= config.getBeaconCapturePoints()) {
                playCaptureCompleteSound(player, beacon, state);
            } else if (!isOwnedByPlayer && capture.getOwningTownId() != 0) {
                playBeaconLostSound(player, beacon, state);
            }
        }

        // Continuous sounds
        if (isOwnedByPlayer || capture.getOwningTownId() == 0) {
            // Player is capturing or beacon is neutral
            playAmbientSound(player, beacon, state);

            // Progress tick sound (when capture increases)
            if (capture.getCaptureProgress() > 0 && !capture.isContested()) {
                playProgressSound(player, beacon, state);
            }
        }
    }

    /**
     * Calculate state hash for change detection
     */
    private int calculateStateHash(BeaconCapture capture) {
        return java.util.Objects.hash(
                capture.getOwningTownId(),
                capture.isContested(),
                (int) (capture.getCaptureProgress() / 10) // Group by 10% increments
        );
    }

    /**
     * Get previous state hash from player state
     */
    private int getPreviousStateHash(PlayerBeaconUIState state, String beaconId, BeaconCapture capture) {
        if (state.getLastBeaconStates().containsKey(beaconId)) {
            return state.getLastBeaconStates().get(beaconId);
        }
        return calculateStateHash(capture);
    }

    /**
     * Play ambient loop sound (while capturing)
     */
    private void playAmbientSound(Player player, SpiritBeacon beacon, PlayerBeaconUIState state) {
        if (!state.canPlaySound(beacon.id(), SOUND_AMBIENT, COOLDOWN_AMBIENT)) {
            return;
        }

        playSound(player, beacon.location(), Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1.0f);
        state.recordSoundPlay(beacon.id(), SOUND_AMBIENT);
    }

    /**
     * Play progress tick sound (capture increasing)
     */
    private void playProgressSound(Player player, SpiritBeacon beacon, PlayerBeaconUIState state) {
        if (!state.canPlaySound(beacon.id(), SOUND_PROGRESS, COOLDOWN_PROGRESS)) {
            return;
        }

        playSound(player, beacon.location(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.3f, 1.5f);
        state.recordSoundPlay(beacon.id(), SOUND_PROGRESS);
    }

    /**
     * Play capture complete sound (100% captured)
     */
    private void playCaptureCompleteSound(Player player, SpiritBeacon beacon, PlayerBeaconUIState state) {
        if (!state.canPlaySound(beacon.id(), SOUND_COMPLETE, COOLDOWN_COMPLETE)) {
            return;
        }

        playSound(player, beacon.location(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        state.recordSoundPlay(beacon.id(), SOUND_COMPLETE);
    }

    /**
     * Play enemy detected sound (contested state entered)
     */
    private void playEnemyDetectedSound(Player player, SpiritBeacon beacon, PlayerBeaconUIState state) {
        if (!state.canPlaySound(beacon.id(), SOUND_ENEMY, COOLDOWN_ENEMY)) {
            return;
        }

        playSound(player, beacon.location(), Sound.BLOCK_BELL_USE, 0.8f, 0.8f);
        state.recordSoundPlay(beacon.id(), SOUND_ENEMY);
    }

    /**
     * Play beacon lost sound (ownership lost to enemy)
     */
    private void playBeaconLostSound(Player player, SpiritBeacon beacon, PlayerBeaconUIState state) {
        if (!state.canPlaySound(beacon.id(), SOUND_LOST, COOLDOWN_LOST)) {
            return;
        }

        playSound(player, beacon.location(), Sound.ENTITY_WITHER_AMBIENT, 0.6f, 1.2f);
        state.recordSoundPlay(beacon.id(), SOUND_LOST);
    }

    /**
     * Play a sound at a location for a player
     */
    private void playSound(Player player, Location location, Sound sound, float volume, float pitch) {
        // Only play if player is within sound radius
        if (player.getLocation().distance(location) <= SOUND_RADIUS) {
            player.playSound(location, sound, SoundCategory.BLOCKS, volume, pitch);
        }
    }
}
