package com.crok4it.audit.listener;

import com.crok4it.audit.core.AuditEntry;
import com.crok4it.audit.core.AuditEntryRepository;
import com.crok4it.audit.core.AuditMetadata;
import com.crok4it.audit.core.AuditableEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Automatically persists one audit_entry row for every AuditableEvent published
 * after a successful transaction commit.
 *
 * Infrastructure-managed — no developer action required.
 *
 * - Fires after the publisher transaction commits (AFTER_COMMIT)
 * - Runs asynchronously in the audit-async thread pool
 * - Uses REQUIRES_NEW to survive independently of the caller transaction
 * - Reads all audit fields directly from event.auditMetadata() — no MDC dependency
 * - Swallows exceptions to ensure audit NEVER breaks the business flow
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GenericAuditListener {

    private final AuditEntryRepository repository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAuditableEvent(AuditableEvent event) {
        AuditMetadata metadata = event.auditMetadata();
        if (metadata == null) {
            log.warn("AuditableEvent {} published without metadata — skipped",
                    event.getClass().getSimpleName());
            return;
        }
        try {
            var entry = AuditEntry.builder()
                    .id(UUID.randomUUID())
                    .entityType(event.entityType())
                    .entityId(event.entityId())
                    .partyId(event.partyId())
                    .actionType(event.actionType())
                    .outcome(AuditEntry.Outcome.SUCCESS)
                    .correlationId(metadata.correlationId())
                    .causationId(metadata.causationEventId())
                    .sourceSystem(metadata.sourceSystem())
                    .chainDepth(metadata.chainDepth())
                    .occurredAt(metadata.publishedAt() != null ? metadata.publishedAt() : Instant.now())
                    .build();

            repository.save(entry);

            log.debug("audit_entry saved: {} {} action={} correlationId={}",
                    event.entityType(), event.entityId(), event.actionType(), metadata.correlationId());

        } catch (Exception e) {
            // Audit must NEVER fail the business — log and swallow
            log.error("Failed to persist audit_entry for {}/{} action={}",
                    event.entityType(), event.entityId(), event.actionType(), e);
        }
    }
}
