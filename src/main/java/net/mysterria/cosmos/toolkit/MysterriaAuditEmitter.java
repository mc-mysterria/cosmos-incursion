package net.mysterria.cosmos.toolkit;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.CosmosIncursion;

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
}
