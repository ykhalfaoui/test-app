package com.crok4it.audit.core;

import org.slf4j.MDC;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit context carried by every AuditableEvent.
 * Injected automatically by AuditAwareEventPublisher — never set manually.
 *
 * Root events (first publication) read correlationId from MDC.
 * Child events (published inside a listener) inherit the parent's correlationId
 * and increment chainDepth — enabling full event-chain tracing with one SQL query.
 */
public record AuditMetadata(
        String correlationId,
        String sourceSystem,
        String userId,
        String tenantId,
        int chainDepth,
        String causationEventId,
        Instant publishedAt
) {

    /** Builds metadata for the first event in a chain (root). Reads correlationId from MDC. */
    public static AuditMetadata forRootEvent(String sourceSystem) {
        String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return new AuditMetadata(
                correlationId,
                sourceSystem != null ? sourceSystem : "INTERNAL",
                MDC.get(MdcKeys.USER_ID),
                MDC.get(MdcKeys.TENANT_ID),
                0,
                null,
                Instant.now()
        );
    }

    /** Builds metadata for a child event published inside a listener. Inherits correlationId. */
    public static AuditMetadata forChildEvent(AuditMetadata parent, String causationEventId) {
        return new AuditMetadata(
                parent.correlationId(),
                parent.sourceSystem(),
                parent.userId(),
                parent.tenantId(),
                parent.chainDepth() + 1,
                causationEventId,
                Instant.now()
        );
    }

    /** Returns a copy of this metadata tagged as REPLAY (used by AuditContextRestorerAspect). */
    public AuditMetadata asReplay() {
        return new AuditMetadata(
                correlationId, "REPLAY", userId, tenantId,
                chainDepth, causationEventId, publishedAt
        );
    }
}
