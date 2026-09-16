package net.mysterria.cosmos.domain.incursion.listener;

import dev.ua.ikeepcalm.coi.api.event.MadnessGainEvent;
import dev.ua.ikeepcalm.coi.api.event.MagicDamageEvent;
import dev.ua.ikeepcalm.coi.api.event.MythicalFormEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.incursion.model.PlayerZoneState;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class IncursionZoneListener implements Listener {

    /** Set by Circle of Imagination while a Fool marionettist possesses a body. */
    private static final String POSSESSION_ORIGINAL_MAX_HEALTH = "coi_possession_original_max_health";

    private final PlayerStateManager playerStateManager;

    public IncursionZoneListener(PlayerStateManager playerStateManager) {
        this.playerStateManager = playerStateManager;
    }

    /**
     * During possession the controller's Beyonder carries the borrowed body's health pool, so
     * normalizing against it would multiply damage. COI publishes the controller's own max health
     * as player metadata; use it when present.
     */
    private static double possessionOriginalMaxHealth(Player player) {
        if (!player.hasMetadata(POSSESSION_ORIGINAL_MAX_HEALTH)) return 0;
        for (org.bukkit.metadata.MetadataValue value : player.getMetadata(POSSESSION_ORIGINAL_MAX_HEALTH)) {
            if (value.asDouble() > 0) return value.asDouble();
        }
        return 0;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMagicDamage(MagicDamageEvent event) {
        Player damager = event.getDamager();
        if (damager == null) return;
        if (!(event.getDamaged() instanceof Player damaged)) return;

        PlayerZoneState state = playerStateManager.getState(damager.getUniqueId());
        if (state == null) return;

        ZoneTier tier = state.getIncursionZone().getTier();
        if (tier == ZoneTier.RED || tier == ZoneTier.DEATH) return;

        if (!CoiToolkit.isBeyonder(damager) || !CoiToolkit.isBeyonder(damaged)) return;

        double damagerMaxHP = possessionOriginalMaxHealth(damager);
        if (damagerMaxHP <= 0) damagerMaxHP = CoiToolkit.getBeyonderData(damager).maxHealth();
        double damagedMaxHP = CoiToolkit.getBeyonderData(damaged).maxHealth();
        if (damagerMaxHP <= 0) return;

        double raw = event.getDamage();
        double normalized = (raw / damagerMaxHP) * damagedMaxHP;
        double lerpFactor = (tier == ZoneTier.GREEN) ? 1.0 : 0.5;
        event.setDamage(raw + (normalized - raw) * lerpFactor);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMythicForm(MythicalFormEvent event) {
        if (event.getState() != MythicalFormEvent.MythicalFormState.ACTIVATE) return;

        Player player = event.getPlayer();
        PlayerZoneState state = playerStateManager.getState(player.getUniqueId());
        if (state == null) return;

        ZoneTier tier = state.getIncursionZone().getTier();
        if (tier == ZoneTier.RED || tier == ZoneTier.DEATH) return;

        event.setCancelled(true);
        player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                .append(Component.text("You cannot use your Mythical Creature Form while inside non-red zone!", NamedTextColor.RED)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMadnessGain(MadnessGainEvent event) {
        if (event.getSource() != MadnessGainEvent.Source.EXTERNAL_ABILITY) return;

        Player player = event.getPlayer();
        if (player == null) return;

        PlayerZoneState state = playerStateManager.getState(player.getUniqueId());
        if (state == null) return;

        switch (state.getIncursionZone().getTier()) {
            case GREEN -> event.setCancelled(true);
            case YELLOW -> event.setAmount(event.getAmount() * 0.5);
        }
    }
}
