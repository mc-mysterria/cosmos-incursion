package net.mysterria.cosmos.domain.incursion.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.toolkit.EffectsToolkit;
import net.mysterria.cosmos.domain.incursion.service.EventManager;
import net.mysterria.cosmos.domain.incursion.model.source.EventState;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import net.mysterria.cosmos.domain.incursion.model.source.PlayerTier;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import net.mysterria.cosmos.domain.incursion.service.ZoneManager;
import net.mysterria.cosmos.domain.incursion.gui.ConsentGUI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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

    // Warning distances from zone edge (in blocks)
    private static final double[] WARNING_DISTANCES = {500.0, 300.0, 200.0, 100.0, 50.0};
    private static final long WARNING_COOLDOWN = 10000; // 10 seconds cooldown between same-tier warnings

    private final CosmosIncursion plugin;
    private final ZoneManager zoneManager;
    private final PlayerStateManager playerStateManager;
    private final EffectsToolkit effectsToolkit;
    private final EventManager eventManager;
    private final ConsentGUI consentGUI;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;
    private final Map<UUID, Long> lastConsentPrompt;
    private final Map<UUID, Map<Double, Long>> lastWarningTime;
    private final Map<UUID, BossBar> zoneBossBars = new HashMap<>();

    public ZoneCheckTask(CosmosIncursion plugin, ZoneManager zoneManager,
                         PlayerStateManager playerStateManager, EffectsToolkit effectsToolkit,
                         EventManager eventManager, ConsentGUI consentGUI) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.playerStateManager = playerStateManager;
        this.effectsToolkit = effectsToolkit;
        this.eventManager = eventManager;
        this.consentGUI = consentGUI;
        this.config = plugin.getConfigLoader().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
        this.lastConsentPrompt = new HashMap<>();
        this.lastWarningTime = new HashMap<>();
    }

    @Override
    public void run() {
        // Only check when event is active; clean up any leftover boss bars on transition
        if (eventManager.getState() != EventState.ACTIVE) {
            if (!zoneBossBars.isEmpty()) {
                for (Map.Entry<UUID, BossBar> entry : new HashMap<>(zoneBossBars).entrySet()) {
                    Player p = Bukkit.getPlayer(entry.getKey());
                    if (p != null) entry.getValue().removePlayer(p);
                }
                zoneBossBars.clear();
            }
            return;
        }

        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Skip Citizens NPCs (they have "NPC" metadata)
                if (player.hasMetadata("NPC")) {
                    continue;
                }
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

        // Check if player is near a zone and show consent GUI or warnings if needed
        if (currentZone == null && !isTracked) {
            checkNearZone(player);
            checkZoneWarnings(player); // Add distance-based warnings
        }

        // Player is in a zone but not tracked
        if (currentZone != null && !isTracked) {
            // Check consent for this zone's specific tier
            if (!consentGUI.hasConsented(player, currentZone.getTier())) {
                // Push player out of zone to a safe location
                pushPlayerOutOfZone(player, currentZone);
                player.sendMessage(Component.text("You must agree to the " + currentZone.getTier().name() + " zone rules before entering!", NamedTextColor.RED));

                // Show consent GUI for this tier
                showConsentGUI(player, currentZone);
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
     * Check if player is near a zone and show consent GUI if needed.
     */
    private void checkNearZone(Player player) {
        // Check cooldown to avoid spamming GUI
        long now = System.currentTimeMillis();
        Long lastPrompt = lastConsentPrompt.get(player.getUniqueId());
        if (lastPrompt != null && (now - lastPrompt) < CONSENT_PROMPT_COOLDOWN) {
            return;
        }

        // Find nearest zone
        IncursionZone nearestZone = zoneManager.getNearestZone(player.getLocation());
        if (nearestZone == null) {
            return;
        }

        // Already consented to this zone's tier, no need to show GUI
        if (consentGUI.hasConsented(player, nearestZone.getTier())) {
            return;
        }

        double distance = nearestZone.getDistanceFromCenter(player.getLocation()) - nearestZone.getRadius();
        if (distance >= 0 && distance <= CONSENT_DISTANCE) {
            showConsentGUI(player, nearestZone);
            lastConsentPrompt.put(player.getUniqueId(), now);
        }
    }

    /**
     * Show tier-aware consent GUI to a player for the given zone.
     */
    private void showConsentGUI(Player player, IncursionZone zone) {
        consentGUI.showConsent(player, zone.getTier());
    }

    /**
     * Check if player is approaching a zone and show distance warnings.
     */
    private void checkZoneWarnings(Player player) {

        // Find nearest zone
        IncursionZone nearestZone = zoneManager.getNearestZone(player.getLocation());
        if (nearestZone == null) {
            return;
        }

        double distanceFromEdge = nearestZone.getDistanceFromCenter(player.getLocation()) - nearestZone.getRadius();

        // Player is already inside or very close (handled by checkNearZone)
        if (distanceFromEdge <= CONSENT_DISTANCE) {
            return;
        }

        // Check if we should show a warning for any distance tier
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();

        for (double warningDistance : WARNING_DISTANCES) {
            if (distanceFromEdge > 0 && distanceFromEdge <= warningDistance) {
                // Check if we've already warned for this distance tier recently
                Map<Double, Long> playerWarnings = lastWarningTime.computeIfAbsent(playerId, k -> new HashMap<>());
                Long lastWarn = playerWarnings.get(warningDistance);

                if (lastWarn == null || (now - lastWarn) > WARNING_COOLDOWN) {
                    // Show warning based on distance
                    String warningMessage = getWarningMessage(distanceFromEdge, formatZoneName(nearestZone.getName()));
                    player.sendMessage(miniMessage.deserialize(warningMessage));

                    // Play warning sound
                    player.playSound(player.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);

                    // Update last warning time for this tier
                    playerWarnings.put(warningDistance, now);
                }
                break; // Only show one warning at a time (the closest tier)
            }
        }
    }

    /**
     * Get warning message based on distance
     */
    private String getWarningMessage(double distance, String zoneName) {
        int roundedDistance = (int) Math.ceil(distance);

        if (distance > 400) {
            return "<yellow>⚠ WARNING: You are approaching <red>" + zoneName + "</red> (" + roundedDistance + " blocks away)</yellow>";
        } else if (distance > 250) {
            return "<gold>⚠ WARNING: You are getting close to <red>" + zoneName + "</red> (" + roundedDistance + " blocks away)</gold>";
        } else if (distance > 150) {
            return "<gold>⚠⚠ WARNING: Zone <red>" + zoneName + "</red> is nearby (" + roundedDistance + " blocks away)</gold>";
        } else if (distance > 75) {
            return "<red>⚠⚠ DANGER: Zone <red>" + zoneName + "</red> is very close (" + roundedDistance + " blocks away)!</red>";
        } else {
            return "<dark_red>⚠⚠⚠ EXTREME DANGER: Zone <red>" + zoneName + "</red> is extremely close (" + roundedDistance + " blocks away)! Stop now!</dark_red>";
        }
    }

    /**
     * Safely push a player out of the zone to prevent underground teleportation
     */
    private void pushPlayerOutOfZone(Player player, IncursionZone zone) {
        Location playerLoc = player.getLocation();
        Location zoneCenter = zone.getCenter();
        double zoneRadius = zone.getRadius();

        // Calculate direction away from zone center (2D)
        double dx = playerLoc.getX() - zoneCenter.getX();
        double dz = playerLoc.getZ() - zoneCenter.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Normalize direction
        if (distance < 0.1) {
            // Player is exactly at center, push them in a random direction
            double angle = Math.random() * 2 * Math.PI;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
        } else {
            dx /= distance;
            dz /= distance;
        }

        // Calculate safe location outside zone (5 blocks past the edge)
        double safeDistance = zoneRadius + 5.0;
        double targetX = zoneCenter.getX() + (dx * safeDistance);
        double targetZ = zoneCenter.getZ() + (dz * safeDistance);

        // Find safe Y coordinate (surface level)
        Location targetLocation = new Location(playerLoc.getWorld(), targetX, playerLoc.getY(), targetZ);
        Location safeLocation = findSafeLocation(targetLocation);

        if (safeLocation != null) {
            // Preserve player's looking direction
            safeLocation.setYaw(playerLoc.getYaw());
            safeLocation.setPitch(playerLoc.getPitch());

            // Teleport to safe location
            player.teleport(safeLocation);

            // Apply gentle upward velocity to prevent fall damage
            player.setVelocity(player.getVelocity().setY(0.3));
        } else {
            // Fallback: just push them up and away from center
            player.teleport(playerLoc.add(0, 2.0, 0));
            player.setVelocity(playerLoc.toVector()
                    .subtract(zoneCenter.toVector())
                    .normalize()
                    .multiply(0.8)
                    .setY(0.5));
        }
    }

    /**
     * Find a safe location at or near the target location
     * Searches up and down to find solid ground with air above
     */
    private Location findSafeLocation(Location target) {
        if (target.getWorld() == null) {
            return null;
        }

        World world = target.getWorld();
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int startY = target.getBlockY();

        // Search up to 20 blocks up and down
        for (int dy = 0; dy <= 20; dy++) {
            // Try going up first
            int checkY = startY + dy;
            if (checkY < world.getMaxHeight() - 2 && isSafeSpot(world, x, checkY, z)) {
                return new Location(world, x + 0.5, checkY, z + 0.5);
            }

            // Then try going down
            if (dy > 0) {
                checkY = startY - dy;
                if (checkY > world.getMinHeight() && isSafeSpot(world, x, checkY, z)) {
                    return new Location(world, x + 0.5, checkY, z + 0.5);
                }
            }
        }

        return null;
    }

    /**
     * Check if a location is safe to teleport to (solid block below, air above)
     */
    private boolean isSafeSpot(org.bukkit.World world, int x, int y, int z) {
        org.bukkit.block.Block ground = world.getBlockAt(x, y, z);
        org.bukkit.block.Block above1 = world.getBlockAt(x, y + 1, z);
        org.bukkit.block.Block above2 = world.getBlockAt(x, y + 2, z);

        // Ground must be solid
        if (!ground.getType().isSolid()) {
            return false;
        }

        // Ground must not be lava or dangerous
        if (ground.getType() == org.bukkit.Material.LAVA ||
                ground.getType() == org.bukkit.Material.MAGMA_BLOCK ||
                ground.getType() == org.bukkit.Material.CACTUS) {
            return false;
        }

        // Two blocks above must be passable
        return above1.isPassable() && above2.isPassable();
    }

    private void onZoneEntry(Player player, IncursionZone incursionZone) {
        PlayerTier tier = playerStateManager.calculateTier(player);

        playerStateManager.registerEntry(player, incursionZone, tier);
        zoneManager.updatePlayerZone(player, incursionZone);

        String tierColor = switch (incursionZone.getTier()) {
            case GREEN  -> "green";
            case YELLOW -> "yellow";
            case RED    -> "red";
            case DEATH  -> "dark_red";
        };

        Component title = miniMessage.deserialize(config.getMsgZoneEntry());
        Component subtitle = miniMessage.deserialize(
                "<white>You have entered <" + tierColor + ">" + formatZoneName(incursionZone.getName())
                + " [" + incursionZone.getTier() + "]</" + tierColor + "></white>"
        );
        player.showTitle(Title.title(title, subtitle,
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3000), Duration.ofMillis(1000))));

        String tierMessage = switch (incursionZone.getTier()) {
            case GREEN  -> "<green>⚠ GREEN ZONE — PvP enabled. No item loss on death.</green>";
            case YELLOW -> "<yellow>⚠ YELLOW ZONE — PvP enabled. ~33% item loss on death!</yellow>";
            case RED    -> "<red>⚠ RED ZONE — PvP enabled. ALL items lost on death!</red>";
            case DEATH  -> tier == PlayerTier.SPIRIT_WEIGHT
                    ? "<dark_red>☠ DEATH ZONE — ALL items + sequence regression! You are SPIRIT WEIGHT!</dark_red>"
                    : "<dark_red>☠ DEATH ZONE — ALL items + sequence regression!</dark_red>";
        };
        player.sendActionBar(miniMessage.deserialize(tierMessage));

        // Persistent boss bar showing zone name and risk while inside
        showZoneBossBar(player, incursionZone);

        effectsToolkit.applyEffects(player, tier);
        plugin.log("Player " + player.getName() + " entered zone: " + incursionZone.getName() + " (" + tier + ")");
    }

    private void onZoneExit(Player player) {
        IncursionZone exitedZone = playerStateManager.getState(player).getIncursionZone();

        effectsToolkit.removeEffects(player);
        playerStateManager.registerExit(player);
        zoneManager.updatePlayerZone(player, null);

        removeZoneBossBar(player);

        Component message = miniMessage.deserialize(config.getMsgZoneExit());
        player.sendMessage(message);
        player.sendActionBar(miniMessage.deserialize("<green>✓ You are now safe</green>"));

        if (exitedZone != null) {
            plugin.log("Player " + player.getName() + " exited zone: " + exitedZone.getName());
        }
    }

    private void showZoneBossBar(Player player, IncursionZone zone) {
        removeZoneBossBar(player); // clean up any existing bar first

        BarColor barColor = switch (zone.getTier()) {
            case GREEN  -> BarColor.GREEN;
            case YELLOW -> BarColor.YELLOW;
            case RED    -> BarColor.RED;
            case DEATH  -> BarColor.PURPLE;
        };
        String barTitle = switch (zone.getTier()) {
            case GREEN  -> "⬟ GREEN ZONE — " + formatZoneName(zone.getName()) + " | No item drops on death";
            case YELLOW -> "⚠ YELLOW ZONE — " + formatZoneName(zone.getName()) + " | ~33% item loss on death";
            case RED    -> "⚠ RED ZONE — " + formatZoneName(zone.getName()) + " | ALL items lost on death!";
            case DEATH  -> "☠ DEATH ZONE — " + formatZoneName(zone.getName()) + " | ALL items + sequence regression!";
        };

        BossBar bar = Bukkit.createBossBar(barTitle, barColor, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        zoneBossBars.put(player.getUniqueId(), bar);
    }

    private void removeZoneBossBar(Player player) {
        BossBar bar = zoneBossBars.remove(player.getUniqueId());
        if (bar != null) bar.removePlayer(player);
    }

    private static String formatZoneName(String name) {
        return name.replace('_', ' ');
    }

}
