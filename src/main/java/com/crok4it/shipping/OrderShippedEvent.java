package com.crok4it.shipping;

import com.crok4it.audit.core.AuditMetadata;
import com.crok4it.audit.core.AuditableEvent;

/**
 * Domain event published when an order is handed off to shipping.
 * Implements AuditableEvent — correlationId is automatically inherited from the parent event.
 */
public record OrderShippedEvent(
        String orderId,
        String trackingNumber,
        AuditMetadata auditMetadata   // null at construction — injected by AuditAwareEventPublisher
) implements AuditableEvent {

    @Override
    public String entityType() { return "ORDER"; }

    @Override
    public String entityId() { return orderId; }

    @Override
    public String actionType() { return "ORDER_SHIPPED"; }

    @Override
    public OrderShippedEvent withAuditMetadata(AuditMetadata metadata) {
        return new OrderShippedEvent(orderId, trackingNumber, metadata);
    }
}
