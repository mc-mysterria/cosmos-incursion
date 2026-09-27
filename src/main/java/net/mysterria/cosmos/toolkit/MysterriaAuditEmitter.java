package net.mysterria.cosmos.toolkit;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * Best-effort bridge to the optional Mysterria audit ledger.
 *
 * <p>Cosmos emits only after its owning mutation has reached a final outcome. A missing,
 * incompatible, or failing audit client is deliberately ignored so audit delivery can never change
 * an incursion, reward, or shop transaction.</p>
 */
public final class MysterriaAuditEmitter {
    private static final String NAMESPACE = "mysterria-cosmos.";
    private static volatile AuditProducer producer;

    private MysterriaAuditEmitter() {
    }

    public static void initialize(CosmosIncursion plugin) {
        producer = AuditProducer.create(plugin.getDataFolder().toPath().toAbsolutePath().getParent()
                        .resolve("mysterria-audit-spool"),
                "mysterria-cosmos", plugin.getPluginMeta().getVersion());
    }

    public static void close() {
        AuditProducer current = producer;
        producer = null;
        if (current != null) current.close();
    }

    public static void recordFailure() {
        AuditProducer current = producer;
        if (current != null) current.recordFailure();
    }

    public static void emit(CosmosIncursion plugin, String event, AuditOutcome outcome,
                            AuditRisk risk, UUID correlationId, String businessId,
                            UUID actorId, UUID subjectId, UUID targetId, String reason,
                            Map<String, ?> metadata) {
        if (event == null || event.isBlank() || outcome == null || risk == null) return;

        try {
            AuditProducer current = producer;
            if (current == null) return;

            current.emit(NAMESPACE + event, outcome, risk, AuditPrivacy.STAFF_RESTRICTED,
                    correlationId, businessId, actorId, subjectId, targetId, reason, metadata);
        } catch (RuntimeException | LinkageError failure) {
            // Audit is explicitly best effort; never fail a committed gameplay operation.
            AuditProducer current = producer;
            if (current != null) current.recordFailure();
        }
    }

    public static void emitCommitted(CosmosIncursion plugin, String event, UUID correlationId,
                                     String businessId, UUID actorId, UUID subjectId,
                                     String reason, Map<String, ?> metadata) {
        emit(plugin, event, AuditOutcome.COMMITTED, AuditRisk.NORMAL, correlationId, businessId,
                actorId, subjectId, null, reason, metadata);
    }

    /**
     * Adds the shared {@code world}/{@code x}/{@code y}/{@code z} location keys for a player. A
     * missing or offline player adds nothing; location capture never fails the caller.
     */
    public static void putPlayerLocation(Map<String, Object> metadata, Player player) {
        if (metadata == null || player == null) return;
        try {
            putLocation(metadata, player.getLocation());
        } catch (RuntimeException | LinkageError failure) {
            recordFailure();
        }
    }

    /** Adds the shared {@code world}/{@code x}/{@code y}/{@code z} block-position keys. */
    public static void putLocation(Map<String, Object> metadata, Location location) {
        if (metadata == null || location == null || location.getWorld() == null) return;
        metadata.put("world", location.getWorld().getName());
        metadata.put("x", location.getBlockX());
        metadata.put("y", location.getBlockY());
        metadata.put("z", location.getBlockZ());
    }

    /** Actor UUID for a command sender; {@code null} for console and other non-player senders. */
    public static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    /** Adds actor name/type, plus the actor's location when the sender is a player. */
    public static void putActor(Map<String, Object> metadata, CommandSender sender) {
        if (metadata == null || sender == null) return;
        metadata.put("actor_name", sender.getName());
        metadata.put("actor_type", sender instanceof Player ? "player" : "console");
        if (sender instanceof Player player) putPlayerLocation(metadata, player);
    }
}
