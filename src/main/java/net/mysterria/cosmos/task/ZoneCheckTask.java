package net.mysterria.cosmos.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.effect.EffectManager;
import net.mysterria.cosmos.event.EventManager;
import net.mysterria.cosmos.event.source.EventState;
import net.mysterria.cosmos.gui.ConsentGUI;
import net.mysterria.cosmos.player.PlayerStateManager;
import net.mysterria.cosmos.player.source.PlayerTier;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.zone.IncursionZone;
import net.mysterria.cosmos.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Checks all online players every N ticks to detect zone entry/exit
 * More reliable than PlayerMoveEvent for handling edge cases
 */
public class ZoneCheckTask extends BukkitRunnable {

    private static final double CONSENT_DISTANCE = 10.0; // Show GUI when within 10 blocks of zone
    private static final long CONSENT_PROMPT_COOLDOWN = 5000; // 5 seconds cooldown between prompts
    private final CosmosIncursion plugin;
    private final ZoneManager zoneManager;
    private final PlayerStateManager playerStateManager;
    private final EffectManager effectManager;
    private final EventManager eventManager;
    private final ConsentGUI consentGUI;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;
    private final Map<UUID, Long> lastConsentPrompt;

    public ZoneCheckTask(CosmosIncursion plugin, ZoneManager zoneManager,
                         PlayerStateManager playerStateManager, EffectManager effectManager,
                         EventManager eventManager, ConsentGUI consentGUI) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.playerStateManager = playerStateManager;
        this.effectManager = effectManager;
        this.eventManager = eventManager;
        this.consentGUI = consentGUI;
        this.config = plugin.getConfigManager().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
        this.lastConsentPrompt = new HashMap<>();
    }

    @Override
    public void run() {
        // Only check when event is active
        if (eventManager.getState() != EventState.ACTIVE) {
            return;
        }

        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                checkPlayerZone(player);
            }
        } catch (Exception e) {
            plugin.log("Error in ZoneCheckTask: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check if a player should be in a zone or not
     */
    private void checkPlayerZone(Player player) {
        IncursionZone currentZone = zoneManager.getZoneAt(player.getLocation());
        boolean isTracked = playerStateManager.isInZone(player);

        // Check if player is near a zone and show consent GUI if needed
        if (currentZone == null && !isTracked) {
            checkNearZone(player);
        }

        // Player is in a zone but not tracked
        if (currentZone != null && !isTracked) {
            // Check consent
            if (!consentGUI.hasConsented(player)) {
                // Push player out of zone
                player.teleport(player.getLocation().add(0, 0.5, 0)); // Small upward bump
                player.setVelocity(player.getLocation().toVector()
                        .subtract(currentZone.getCenter().toVector())
                        .normalize()
                        .multiply(0.5)
                        .setY(0.2));
                player.sendMessage(Component.text("You must agree to the zone rules before entering!", NamedTextColor.RED));

                // Show consent GUI
                showConsentGUI(player);
                return;
            }

            // Player has consented, register entry
            onZoneEntry(player, currentZone);
        }
        // Player is tracked but not in a zone
        else if (currentZone == null && isTracked) {
            onZoneExit(player);
        }
        // Player is in a different zone than tracked (rare but possible)
        else if (currentZone != null) {
            IncursionZone trackedZone = playerStateManager.getState(player).getIncursionZone();
            if (!currentZone.equals(trackedZone)) {
                onZoneExit(player);
                onZoneEntry(player, currentZone);
            }
        }
    }

    /**
     * Check if player is near a zone and show consent GUI if needed
     */
    private void checkNearZone(Player player) {
        // Already consented, no need to check
        if (consentGUI.hasConsented(player)) {
            return;
        }

        // Check cooldown to avoid spamming GUI
        long now = System.currentTimeMillis();
        Long lastPrompt = lastConsentPrompt.get(player.getUniqueId());
        if (lastPrompt != null && (now - lastPrompt) < CONSENT_PROMPT_COOLDOWN) {
            return;
        }

        // Find nearest zone
        IncursionZone nearestZone = zoneManager.getNearestZone(player.getLocation());
        if (nearestZone != null) {
            double distance = nearestZone.getDistanceFromCenter(player.getLocation()) - nearestZone.getRadius();

            // Player is within consent distance
            if (distance >= 0 && distance <= CONSENT_DISTANCE) {
                showConsentGUI(player);
                lastConsentPrompt.put(player.getUniqueId(), now);
            }
        }
    }

    /**
     * Show consent GUI to player
     */
    private void showConsentGUI(Player player) {
        consentGUI.showConsent(player);
    }

    private void onZoneEntry(Player player, IncursionZone incursionZone) {
        // Calculate player tier
        PlayerTier tier = playerStateManager.calculateTier(player);

        // Force safe mode OFF
        CoiToolkit.turnOffSafeMode(player);

        // Register player state
        playerStateManager.registerEntry(player, incursionZone, tier);

        // Update zone tracking
        zoneManager.updatePlayerZone(player, incursionZone);

        // Display warning title
        Component title = miniMessage.deserialize(config.getMsgZoneEntry());
        Component subtitle = miniMessage.deserialize("<white>You have entered <red>" + incursionZone.getName() + "</red></white>");

        player.showTitle(Title.title(
                title,
                subtitle,
                Title.Times.times(
                        Duration.ofMillis(500),
                        Duration.ofMillis(3000),
                        Duration.ofMillis(1000)
                )
        ));

        // Send action bar message
        String tierMessage = tier == PlayerTier.SPIRIT_WEIGHT ?
                "<red>⚠ DANGER ZONE ⚠ PvP is now enabled! You are marked as SPIRIT WEIGHT</red>" :
                "<red>⚠ DANGER ZONE ⚠ PvP is now enabled!</red>";

        player.sendActionBar(miniMessage.deserialize(tierMessage));

        // Apply tier-specific effects
        effectManager.applyEffects(player, tier);

        plugin.log("Player " + player.getName() + " entered zone: " + incursionZone.getName() + " (" + tier + ")");
    }

    private void onZoneExit(Player player) {
        IncursionZone exitedZone = playerStateManager.getState(player).getIncursionZone();

        // Remove all zone effects
        effectManager.removeEffects(player);

        // Unregister player state
        playerStateManager.registerExit(player);

        // Update zone tracking
        zoneManager.updatePlayerZone(player, null);

        // Send exit message
        Component message = miniMessage.deserialize(config.getMsgZoneExit());
        player.sendMessage(message);

        player.sendActionBar(miniMessage.deserialize(
                "<green>✓ You are now safe - Safe mode restrictions removed</green>"
        ));

        if (exitedZone != null) {
            plugin.log("Player " + player.getName() + " exited zone: " + exitedZone.getName());
        }
    }

}
