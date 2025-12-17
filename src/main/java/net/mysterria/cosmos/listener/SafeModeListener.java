package net.mysterria.cosmos.listener;

import dev.ua.ikeepcalm.coi.api.event.SafeModeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.player.PlayerStateManager;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

public class SafeModeListener implements Listener {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;

    public SafeModeListener(CosmosIncursion plugin, PlayerStateManager playerStateManager) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        startSafeModeEnforcer();
    }

    @EventHandler
    public void onCommand(SafeModeEvent event) {
        Player player = event.getPlayer();

        // Only check if player is in a zone
        if (!playerStateManager.isInZone(player)) {
            return;
        }

        if (event.isAfter()) {
            event.setCancelled(true);
            player.sendMessage(Component.text(
                    "You cannot toggle safe mode while in an Incursion zone!"
            ).color(NamedTextColor.RED));

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
