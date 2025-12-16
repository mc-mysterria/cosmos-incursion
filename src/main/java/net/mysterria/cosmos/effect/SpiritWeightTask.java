package net.mysterria.cosmos.effect;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.event.EventManager;
import net.mysterria.cosmos.event.source.EventState;
import net.mysterria.cosmos.player.PlayerStateManager;
import net.mysterria.cosmos.player.PlayerZoneState;
import net.mysterria.cosmos.player.source.PlayerTier;
import net.mysterria.cosmos.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Applies DOT (Damage Over Time) to Spirit Weight players
 * Runs periodically based on config (default: every 5 seconds / 100 ticks)
 */
public class SpiritWeightTask extends BukkitRunnable {

    private final CosmosIncursion plugin;
    private final PlayerStateManager playerStateManager;
    private final EffectManager effectManager;
    private final EventManager eventManager;
    private final ZoneManager zoneManager;
    private final CosmosConfig config;

    public SpiritWeightTask(CosmosIncursion plugin, PlayerStateManager playerStateManager,
                            EffectManager effectManager, EventManager eventManager, ZoneManager zoneManager) {
        this.plugin = plugin;
        this.playerStateManager = playerStateManager;
        this.effectManager = effectManager;
        this.eventManager = eventManager;
        this.zoneManager = zoneManager;
        this.config = plugin.getConfigManager().getConfig();
    }

    @Override
    public void run() {
        try {
            // Only apply effects when event is ACTIVE
            if (eventManager.getState() != EventState.ACTIVE) {
                return;
            }

            // Get all Spirit Weight players
            for (PlayerZoneState state : playerStateManager.getPlayersByTier(PlayerTier.SPIRIT_WEIGHT)) {
                Player player = Bukkit.getPlayer(state.getPlayerId());

                if (player != null && player.isOnline()) {
                    // Verify player is actually still in a zone
                    if (zoneManager.getZoneAt(player.getLocation()) == null) {
                        // Player is no longer in a zone (likely respawned outside)
                        // Remove them from tracking and skip DOT damage
                        playerStateManager.registerExit(player);
                        effectManager.removeEffects(player);
                        plugin.log("Removed player " + player.getName() + " from zone tracking (no longer in zone)");
                        continue;
                    }

                    // Apply DOT damage
                    applyDotDamage(player);

                    // Refresh effects (ensure GLOWING doesn't expire)
                    effectManager.refreshEffects(player, PlayerTier.SPIRIT_WEIGHT);
                }
            }
        } catch (Exception e) {
            plugin.log("Error in SpiritWeightTask: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Apply DOT damage to a player
     * Damage is non-lethal (leaves player at 1 HP minimum)
     */
    private void applyDotDamage(Player player) {
        double currentHealth = player.getHealth();
        double damage = config.getDotDamage();

        // Calculate new health (minimum 1.0)
        double newHealth = Math.max(1.0, currentHealth - damage);

        // Apply damage
        player.setHealth(newHealth);

        // Send action bar message
        player.sendActionBar(Component.text("The cosmos corrodes your being...")
                .color(NamedTextColor.RED));

        // Log if player is close to death
        if (newHealth <= 3.0) {
            plugin.log("Player " + player.getName() + " is low health from Spirit Weight DOT (" + newHealth + " HP)");
        }
    }

}
