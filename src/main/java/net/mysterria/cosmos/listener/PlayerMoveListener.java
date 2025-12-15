package net.mysterria.cosmos.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.effect.EffectManager;
import net.mysterria.cosmos.player.PlayerStateManager;
import net.mysterria.cosmos.player.source.PlayerTier;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.zone.IncursionZone;
import net.mysterria.cosmos.zone.ZoneManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.time.Duration;

public class PlayerMoveListener implements Listener {

    private final CosmosIncursion plugin;
    private final ZoneManager zoneManager;
    private final PlayerStateManager playerStateManager;
    private final EffectManager effectManager;
    private final CosmosConfig config;
    private final MiniMessage miniMessage;

    public PlayerMoveListener(CosmosIncursion plugin, ZoneManager zoneManager,
                              PlayerStateManager playerStateManager, EffectManager effectManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.playerStateManager = playerStateManager;
        this.effectManager = effectManager;
        this.config = plugin.getConfigManager().getConfig();
        this.miniMessage = MiniMessage.miniMessage();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Optimization: Only check on block position change
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        IncursionZone fromIncursionZone = zoneManager.getZoneAt(event.getFrom());
        IncursionZone toIncursionZone = zoneManager.getZoneAt(event.getTo());

        // No change in zone status
        if (fromIncursionZone == toIncursionZone) {
            return;
        }

        // Entering a zone
        if (fromIncursionZone == null) {
            onZoneEntry(player, toIncursionZone);
        }
        // Exiting a zone
        else if (toIncursionZone == null) {
            onZoneExit(player, fromIncursionZone);
        }
        // Moving between zones (rare, but possible if zones overlap)
        else if (!fromIncursionZone.equals(toIncursionZone)) {
            onZoneExit(player, fromIncursionZone);
            onZoneEntry(player, toIncursionZone);
        }
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

    private void onZoneExit(Player player, IncursionZone incursionZone) {
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

        plugin.log("Player " + player.getName() + " exited zone: " + incursionZone.getName());
    }

}
