package net.mysterria.cosmos.domain.combat.service;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.incursion.model.IncursionEvent;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Shared audit helpers for the automatic consequences of a death (item drops, penalties, rewards,
 * resource loss, hollow bodies). Everything here reads values the caller already holds and calls the
 * thread-safe emitter; nothing schedules, does IO or looks anything up, and a failure is recorded
 * instead of thrown so it can never change the death.
 */
public final class DeathAudit {

    /** Who or what caused a death, as far as the death event itself says. */
    public record KillerFacts(UUID ownerId, UUID attackerId) {
    }

    private DeathAudit() {
    }

    /**
     * Emits one automatic death-consequence row. The correlation ID is the active incursion's ID, or
     * the row's own operation UUID when no incursion is running; there is no actor unless given.
     */
    public static void emit(CosmosIncursion plugin, String event, AuditOutcome outcome, AuditRisk risk,
                            UUID actorId, UUID subjectId, UUID targetId, String reason,
                            Map<String, Object> metadata) {
        try {
            IncursionEvent active = plugin.getEventManager().getActiveEvent();
            UUID operationId = UUID.randomUUID();
            MysterriaAuditEmitter.emit(plugin, event, outcome, risk,
                    active != null ? active.getEventId() : operationId, event + "." + operationId,
                    actorId, subjectId, targetId, reason, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /** Material and amount of a stack, read from the stack in hand. */
    public static Map<String, Object> stack(ItemStack item) {
        Map<String, Object> stack = new LinkedHashMap<>();
        stack.put("material", item.getType().name().toLowerCase(Locale.ROOT));
        stack.put("amount", item.getAmount());
        return stack;
    }

    /**
     * Adds the damage cause and killer facts a death event already carries: Bukkit cause, damage
     * type, killer type and entity, the direct entity when different (projectile) and the resolved
     * player owner. {@code killer} is the player the game credits ({@code getKiller()}), if any.
     */
    public static KillerFacts putKillerFacts(Map<String, Object> metadata, Entity victim,
                                             DamageSource source, Player killer) {
        boolean selfKill = killer != null && killer.equals(victim);
        Player playerKiller = killer != null && !selfKill ? killer : null;

        Entity attacker = source.getCausingEntity() != null ? source.getCausingEntity() : source.getDirectEntity();
        if (attacker != null && attacker.equals(victim)) attacker = null;

        EntityDamageEvent lastDamage = victim.getLastDamageCause();
        if (lastDamage != null) metadata.put("damage_cause", lastDamage.getCause().name());
        metadata.put("damage_type", source.getDamageType().getKey().toString());

        String killerType = playerKiller != null ? "PLAYER" : attacker != null ? "ENTITY" : "ENVIRONMENT";
        metadata.put("killer_type", killerType);
        if (attacker != null) {
            metadata.put("killer_entity_type", attacker.getType().name());
            metadata.put("killer_entity_uuid", attacker.getUniqueId().toString());
            metadata.put("killer_entity_name", attacker.getName());
        }
        Entity direct = source.getDirectEntity();
        if (direct != null && !direct.equals(victim) && !direct.equals(attacker)) {
            metadata.put("direct_entity_type", direct.getType().name());
            metadata.put("direct_entity_uuid", direct.getUniqueId().toString());
        }

        // Resolved player owner, only from values the death event and Bukkit entities already carry
        UUID ownerId = null;
        String ownerName = null;
        String ownerSource = null;
        if (playerKiller != null) {
            ownerId = playerKiller.getUniqueId();
            ownerName = playerKiller.getName();
            ownerSource = "player_killer";
        } else if (attacker instanceof Player damager) {
            ownerId = damager.getUniqueId();
            ownerName = damager.getName();
            ownerSource = "damage_source_player";
        } else if (attacker instanceof Tameable tameable && tameable.getOwnerUniqueId() != null) {
            ownerId = tameable.getOwnerUniqueId();
            ownerSource = "tamed_owner";
        }
        if (ownerId != null) {
            metadata.put("owner_player_uuid", ownerId.toString());
            if (ownerName != null) metadata.put("owner_player_name", ownerName);
            metadata.put("owner_source", ownerSource);
        }
        return new KillerFacts(ownerId, attacker != null ? attacker.getUniqueId() : null);
    }
}
