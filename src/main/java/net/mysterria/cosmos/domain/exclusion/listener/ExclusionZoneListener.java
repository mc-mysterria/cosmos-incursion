package net.mysterria.cosmos.domain.exclusion.listener;

import dev.ua.ikeepcalm.coi.api.event.AbilityUsageEvent;
import dev.ua.ikeepcalm.coi.api.event.MadnessGainEvent;
import dev.ua.ikeepcalm.coi.api.event.MagicDamageEvent;
import dev.ua.ikeepcalm.coi.api.event.MythicalFormEvent;
import dev.ua.ikeepcalm.coi.api.model.AbilityData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionChannelState;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central listener for permanent extraction zone enforcement:
 * <p>
 * 1. Blocks all teleportation/movement out of the zone while carrying resources.
 * 2. Cancels an active extraction channel when the player takes damage.
 * 3. Transfers a dead player's resource buffer to their killer.
 * <p>
 * The tick-based PermanentZonePlayerTask acts as a final backup for edge cases.
 */
public class ExclusionZoneListener implements Listener {

    private static final long MESSAGE_COOLDOWN_MS = 3000;

    private final PermanentZoneManager permanentZoneManager;
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();

    public ExclusionZoneListener(PermanentZoneManager permanentZoneManager) {
        this.permanentZoneManager = permanentZoneManager;
    }

    // ── Exit prevention ───────────────────────────────────────────────────────────

    /**
     * Blocks all teleportation out of the zone while carrying resources.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player.hasMetadata("cosmos_extraction_exit")) return;

        PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
        if (zone == null) return;

        PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
        if (buffer.isEmpty()) return;

        Location to = event.getTo();
        if (to != null && zone.contains(to)) return;

        event.setCancelled(true);
        sendThrottledMessage(player, zone);
    }

    /**
     * Blocks walking across the polygon boundary while carrying resources.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;

        Player player = event.getPlayer();
        PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
        if (zone == null) return;

        PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
        if (buffer.isEmpty()) return;

        Location to = event.getTo();
        if (to == null || zone.contains(to)) return;

        event.setCancelled(true);
    }

    // ── Damage cancels extraction channel ────────────────────────────────────────

    /**
     * Any non-cancelled damage to a channeling player interrupts their extraction.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ExtractionChannelState channel = permanentZoneManager.getExtractionChannel(player.getUniqueId());
        if (channel == null) return;

        permanentZoneManager.cancelExtractionChannel(player.getUniqueId());
        player.sendActionBar(Component.text("Extraction interrupted by damage!", NamedTextColor.RED));
    }

    // ── Resource transfer on kill ─────────────────────────────────────────────────

    /**
     * When a player with resources dies, their entire buffer is transferred to
     * their killer (if a player). Resources are always lost from the victim —
     * whether claimed by a killer or simply dropped on the floor of the zone.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();

        // Cancel any active extraction channel
        permanentZoneManager.cancelExtractionChannel(victim.getUniqueId());

        PlayerResourceBuffer victimBuffer = permanentZoneManager.getBuffer(victim.getUniqueId());
        if (victimBuffer.isEmpty()) return;

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            Map<ResourceType, Double> loot = victimBuffer.snapshot();
            PlayerResourceBuffer killerBuffer = permanentZoneManager.getBuffer(killer.getUniqueId());
            for (Map.Entry<ResourceType, Double> entry : loot.entrySet()) {
                killerBuffer.add(entry.getKey(), entry.getValue());
            }
            notifyKiller(killer, victim.getName(), loot);
            victim.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                    .append(Component.text(killer.getName(), NamedTextColor.RED))
                    .append(Component.text(" took your resources.", NamedTextColor.DARK_RED)));

            permanentZoneManager.clearBuffer(victim.getUniqueId());
        }
    }

    private void notifyKiller(Player killer, String victimName, Map<ResourceType, Double> loot) {
        Component msg = Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Looted " + victimName + ": ", NamedTextColor.GREEN));
        boolean first = true;
        for (Map.Entry<ResourceType, Double> entry : loot.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (!first) msg = msg.append(Component.text(" | ", NamedTextColor.DARK_GRAY));
            msg = msg.append(Component.text(
                    entry.getKey().name() + " +" + String.format("%.1f", entry.getValue()),
                    resourceColor(entry.getKey())
            ));
            first = false;
        }
        killer.sendMessage(msg);
    }

    private NamedTextColor resourceColor(ResourceType type) {
        return switch (type) {
            case GOLD -> NamedTextColor.GOLD;
            case SILVER -> NamedTextColor.WHITE;
            case GEMS -> NamedTextColor.GREEN;
        };
    }

    private void sendThrottledMessage(Player player, PermanentZone zone) {
        long now = System.currentTimeMillis();
        if (now - lastMessageTime.getOrDefault(player.getUniqueId(), 0L) < MESSAGE_COOLDOWN_MS) return;
        lastMessageTime.put(player.getUniqueId(), now);

        player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                .append(Component.text("You cannot leave ", NamedTextColor.RED))
                .append(Component.text(zone.getName().replace('_', ' '), NamedTextColor.YELLOW))
                .append(Component.text(" while carrying resources. Use an extraction point first.", NamedTextColor.RED)));
    }

    /**
     * Blocks movement abilities while carrying resources inside extraction zones.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAbilityUsage(AbilityUsageEvent event) {
        Player player = event.getPlayer();

        PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
        if (zone == null) return;

        PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
        if (buffer == null || buffer.isEmpty()) return;

        if (isMovementAbility(event.getAbility())) {
            event.setCancelled(true);
            player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                    .append(Component.text("You cannot use movement abilities while inside extraction zones with resources!", NamedTextColor.RED)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMythicForm(MythicalFormEvent event) {
        Player player = event.getPlayer();

        PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
        if (zone == null) return;

        if (zone.getTier() != ExclusionZoneTier.HARD && event.getState() == MythicalFormEvent.MythicalFormState.ACTIVATE) {
            event.setCancelled(true);
            player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                    .append(Component.text("You cannot use your Mythical Creature Form while inside non-red zone!", NamedTextColor.RED)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMagicDamage(MagicDamageEvent event) {
        Player damager = event.getDamager();
        if (damager == null) return;

        if (!(event.getDamaged() instanceof Player damaged)) return;

        PermanentZone zone = permanentZoneManager.getPlayerZone(damager.getUniqueId());
        if (zone == null) return;

        if (!CoiToolkit.isBeyonder(damager) || !CoiToolkit.isBeyonder(damaged)) return;

        switch (zone.getTier()) {
            case SAFE -> {
                double damagerMaxHP = CoiToolkit.getBeyonderData(damager).maxHealth();
                double damagedMaxHP = CoiToolkit.getBeyonderData(damaged).maxHealth();
                if (damagerMaxHP <= 0) return;
                double normalized = (event.getDamage() / damagerMaxHP) * damagedMaxHP;
                event.setDamage(normalized);
            }
            case MEDIUM -> {
                double damagerMaxHP = CoiToolkit.getBeyonderData(damager).maxHealth();
                double damagedMaxHP = CoiToolkit.getBeyonderData(damaged).maxHealth();
                if (damagerMaxHP <= 0) return;
                double raw = event.getDamage();
                double normalized = (raw / damagerMaxHP) * damagedMaxHP;
                event.setDamage(raw + (normalized - raw) * 0.5);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMadnessGain(MadnessGainEvent event) {
        if (event.getSource() != MadnessGainEvent.Source.EXTERNAL_ABILITY) return;

        Player player = event.getPlayer();
        if (player == null) return;

        PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
        if (zone == null) return;

        switch (zone.getTier()) {
            case SAFE -> event.setCancelled(true);
            case MEDIUM -> event.setAmount(event.getAmount() * 0.5);
        }

    }

    private boolean isMovementAbility(AbilityData ability) {
        return ability.category() == AbilityData.AbilityCategory.MOBILITY;
    }
}
