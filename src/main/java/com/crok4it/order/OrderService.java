package com.crok4it.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Business service — zero audit code.
 *
 * The developer just publishes the event with null metadata.
 * AuditAwareEventPublisher intercepts the call, injects the AuditMetadata,
 * and GenericAuditListener persists the audit_entry after commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final ApplicationEventPublisher publisher;

    @Transactional
    public String placeOrder(String customerId, BigDecimal amount) {
        String orderId = "ord-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("Placing order {} for customer {}", orderId, customerId);

        // Publish event — null metadata is intentional, filled in by AuditAwareEventPublisher
        publisher.publishEvent(new OrderPlacedEvent(orderId, customerId, amount, "EUR", null));

        return orderId;
    }
}
