package net.mysterria.cosmos.toolkit;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.CosmosIncursion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Best-effort bridge to the optional Mysterria audit ledger.
 *
 * <p>Cosmos emits only after its owning mutation has reached a final outcome. A missing,
 * incompatible, or failing provider is deliberately ignored so audit delivery can never change
 * an incursion, reward, or shop transaction.</p>
 */
public final class MysterriaAuditEmitter {
    private static final String NAMESPACE = "mysterria-cosmos.";
    private static AuditProducer producer;

    private MysterriaAuditEmitter() {
    }

    public static void initialize(CosmosIncursion plugin) {
        producer = AuditProducer.create(plugin.getDataFolder().toPath().toAbsolutePath().getParent(),
                "mysterria-cosmos", plugin.getPluginMeta().getVersion());
    }

    public static void close() {
        AuditProducer current = producer;
        producer = null;
        if (current != null) current.close();
    }

    public static void emit(CosmosIncursion plugin, String event, AuditOutcome outcome,
                            AuditRisk risk, UUID correlationId, String businessId,
                            UUID actorId, UUID subjectId, UUID targetId, String reason,
                            Map<String, ?> metadata) {
        if (event == null || event.isBlank() || outcome == null || risk == null) return;

        try {
            AuditProducer current = producer;
            if (current == null) return;

            Map<String, Object> metadataValues = new LinkedHashMap<>();
            if (metadata != null) metadata.forEach(metadataValues::put);

            current.emit(NAMESPACE + event, outcome, risk, AuditPrivacy.STAFF_RESTRICTED,
                    correlationId, businessId, actorId, subjectId, targetId, reason, metadataValues);
        } catch (RuntimeException | LinkageError failure) {
            // Audit is explicitly best effort; never fail a committed gameplay operation.
            if (plugin != null) {
                plugin.getLogger().log(Level.FINE, "Mysterria audit emission unavailable", failure);
            }
        }
    }

    public static void emitCommitted(CosmosIncursion plugin, String event, UUID correlationId,
                                     String businessId, UUID actorId, UUID subjectId,
                                     String reason, Map<String, ?> metadata) {
        emit(plugin, event, AuditOutcome.COMMITTED, AuditRisk.NORMAL, correlationId, businessId,
                actorId, subjectId, null, reason, metadata);
    }
}
