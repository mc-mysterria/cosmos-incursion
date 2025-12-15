package net.mysterria.cosmos.listener;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.player.PlayerStateManager;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Prevents players from re-enabling safe mode while in an Incursion zone
 *
 * NOTE: This listener uses multiple strategies to block safe mode:
 * 1. Listens for commands that might toggle safe mode
 * 2. Runs periodic check to force safe mode OFF
 *
 * TODO: If Circle of Imagination API provides a SafeModeToggleEvent,
 * add an event handler here to cancel it when player is in zone
 */
public class SafeModeListener implements Listener {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;

    public SafeModeListener(CosmosIncursion plugin, PlayerStateManager playerStateManager) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        startSafeModeEnforcer();
    }

    /**
     * Listen for commands that might toggle safe mode
     * Block any command containing "safemode", "safe", etc. while in zone
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        // Only check if player is in a zone
        if (!playerStateManager.isInZone(player)) {
            return;
        }

        String command = event.getMessage().toLowerCase();

        // Check for common safe mode command patterns
        if (command.contains("safemode") || command.contains("safe mode") ||
            command.contains("/safe") || command.contains("togglesafe")) {

            event.setCancelled(true);
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "You cannot toggle safe mode while in an Incursion zone!"
            ).color(net.kyori.adventure.text.format.NamedTextColor.RED));

            plugin.log("Blocked safe mode command from " + player.getName() + " in zone");
        }
    }

    /**
     * Periodically enforce safe mode OFF for all players in zones
     * Runs every 5 seconds as a failsafe
     */
    private void startSafeModeEnforcer() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // Get all players in zones
                playerStateManager.getAllStates().forEach(state -> {
                    Player player = Bukkit.getPlayer(state.getPlayerId());
                    if (player != null && player.isOnline()) {
                        // Force safe mode OFF
                        CoiToolkit.turnOffSafeMode(player);
                    }
                });
            }
        }.runTaskTimer(plugin, 100L, 100L); // Run every 5 seconds (100 ticks)
    }

}
