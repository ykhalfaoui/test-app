package com.crok4it.audit.core;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity persisted by GenericAuditListener for every AuditableEvent (SUCCESS)
 * and optionally by AuditTrail for explicit FAILURE traces.
 *
 * One table — all outcomes — queryable by correlationId for a 360° view.
 */
@Entity
@Table(name = "audit_entry")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEntry {

    @Id
    private UUID id;

    /** Aggregate type, e.g. "ORDER", "REVIEW". */
    @Column(nullable = false, length = 64)
    private String entityType;

    /** Aggregate identifier. */
    @Column(nullable = false, length = 128)
    private String entityId;

    /** Party (customer/user) identifier — nullable. */
    @Column(length = 64)
    private String partyId;

    /** Action label, e.g. "ORDER_PLACED", "VALIDATE". */
    @Column(nullable = false, length = 64)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Outcome outcome;

    /** Thread that originated this audit — HTTP, KAFKA, SCHEDULER, REPLAY, INTERNAL. */
    @Column(length = 32)
    private String sourceSystem;

    /** correlationId ties the entire business flow together. */
    @Column(nullable = false, length = 64)
    private String correlationId;

    /** ID of the event that caused this one (child chains). */
    @Column(length = 64)
    private String causationId;

    /** Depth in the event chain — 0 for root events. */
    private int chainDepth;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(columnDefinition = "TEXT")
    private String details;

    public enum Outcome {
        SUCCESS,
        BUSINESS_FAILURE,
        TECHNICAL_FAILURE
    }
}
