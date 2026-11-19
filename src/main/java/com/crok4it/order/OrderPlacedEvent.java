package com.crok4it.order;

import com.crok4it.audit.core.AuditMetadata;
import com.crok4it.audit.core.AuditableEvent;

import java.math.BigDecimal;

/**
 * Domain event published when an order is placed.
 *
 * Developer contract to implement transparent audit:
 *   1. Implement AuditableEvent
 *   2. Add AuditMetadata as the last record component (always null at construction)
 *   3. Implement entityType(), entityId(), and withAuditMetadata()
 *
 * That's it. AuditAwareEventPublisher handles the rest automatically.
 */
public record OrderPlacedEvent(
        String orderId,
        String customerId,
        BigDecimal amount,
        String currency,
        AuditMetadata auditMetadata   // null at construction — injected by AuditAwareEventPublisher
) implements AuditableEvent {

    @Override
    public String entityType() { return "ORDER"; }

    @Override
    public String entityId() { return orderId; }

    @Override
    public String partyId() { return customerId; }

    @Override
    public String actionType() { return "ORDER_PLACED"; }

    @Override
    public OrderPlacedEvent withAuditMetadata(AuditMetadata metadata) {
        return new OrderPlacedEvent(orderId, customerId, amount, currency, metadata);
    }
}
