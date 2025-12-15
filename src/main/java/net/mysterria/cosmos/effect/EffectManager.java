package net.mysterria.cosmos.effect;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.player.source.PlayerTier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Manages potion effects for players in Incursion zones
 */
public class EffectManager {

    private final CosmosIncursion plugin;

    public EffectManager(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    /**
     * Apply tier-specific effects to a player
     */
    public void applyEffects(Player player, PlayerTier tier) {
        if (tier == PlayerTier.SPIRIT_WEIGHT) {
            applySpiritWeightEffects(player);
        }
        // INSIGNIFICANT tier gets no effects
    }

    /**
     * Remove all zone-related effects from a player
     */
    public void removeEffects(Player player) {
        // Remove GLOWING effect
        player.removePotionEffect(PotionEffectType.GLOWING);

        plugin.log("Removed effects from player: " + player.getName());
    }

    /**
     * Apply Spirit Weight effects (high-tier penalties)
     * - GLOWING effect (cannot hide)
     */
    private void applySpiritWeightEffects(Player player) {
        // Apply GLOWING effect with infinite duration
        // Amplifier 0 = level 1 effect
        PotionEffect glowing = new PotionEffect(
                PotionEffectType.GLOWING,
                PotionEffect.INFINITE_DURATION,
                0,
                false,  // ambient (particles less visible)
                false   // particles (no particles)
        );

        player.addPotionEffect(glowing);

        plugin.log("Applied Spirit Weight effects to player: " + player.getName());
    }

    /**
     * Refresh effects for a player (prevents expiration)
     * Called periodically to ensure effects stay active
     */
    public void refreshEffects(Player player, PlayerTier tier) {
        if (tier == PlayerTier.SPIRIT_WEIGHT) {
            // Re-apply glowing if it's missing
            if (!player.hasPotionEffect(PotionEffectType.GLOWING)) {
                applySpiritWeightEffects(player);
            }
        }
    }

}
