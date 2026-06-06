package com.crok4it.audit.core;

/**
 * Interface that every auditable domain event must implement.
 *
 * Developer contract (per event):
 *   1. Add `AuditMetadata auditMetadata` as the last record component (pass null when constructing)
 *   2. Implement entityType() and entityId()
 *   3. Implement withAuditMetadata() as a copy-constructor delegate
 *
 * Everything else — correlation capture, MDC propagation, persistence, chain tracking — is automatic.
 *
 * Example:
 * <pre>{@code
 * public record OrderPlacedEvent(
 *     String orderId,
 *     String customerId,
 *     AuditMetadata auditMetadata   // always null at construction time
 * ) implements AuditableEvent {
 *
 *     @Override public String entityType() { return "ORDER"; }
 *     @Override public String entityId()   { return orderId; }
 *     @Override public String partyId()    { return customerId; }
 *
 *     @Override
 *     public OrderPlacedEvent withAuditMetadata(AuditMetadata m) {
 *         return new OrderPlacedEvent(orderId, customerId, m);
 *     }
 * }
 * }</pre>
 */
public interface AuditableEvent {

    /** Aggregate type, e.g. "ORDER", "REVIEW", "PAYMENT". */
    String entityType();

    /** Aggregate identifier. */
    String entityId();

    /** Optional — party (customer/user) who triggered the action. */
    default String partyId() {
        return null;
    }

    /**
     * Action label written to audit_entry.action_type.
     * Defaults to the simple class name, override for custom labels.
     */
    default String actionType() {
        return getClass().getSimpleName();
    }

    /** Audit context injected by AuditAwareEventPublisher — null before publication. */
    AuditMetadata auditMetadata();

    /** Returns a copy of this event with the given metadata attached. */
    AuditableEvent withAuditMetadata(AuditMetadata metadata);
}
