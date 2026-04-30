package com.crok4it.audit.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    /** Returns the full history of a business flow ordered by time. */
    List<AuditEntry> findByCorrelationIdOrderByOccurredAtAsc(String correlationId);

    /** Returns all audit entries for a given aggregate. */
    List<AuditEntry> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, String entityId);
}
