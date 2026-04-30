package com.crok4it.shipping;

import com.crok4it.audit.core.AuditableListener;
import com.crok4it.order.OrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Business listener — zero audit code.
 *
 * @AuditableListener ensures:
 *   - Fires after the publisher transaction commits (AFTER_COMMIT)
 *   - Runs asynchronously with MDC already restored (correlation_id in all logs)
 *   - Child events (OrderShippedEvent) automatically inherit the same correlationId
 *
 * The developer annotates with @AuditableListener — the framework handles everything else.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderShippingListener {

    private final ApplicationEventPublisher publisher;

    @AuditableListener
    void onOrderPlaced(OrderPlacedEvent event) {
        // MDC already restored by AuditContextRestorerAspect
        // → correlation_id appears in this log line automatically
        log.info("Preparing shipping for order {}", event.orderId());

        // Publish child event — inherits correlationId from CurrentAuditHolder
        publisher.publishEvent(
                new OrderShippedEvent(event.orderId(), "TRACK-" + event.orderId(), null));
    }
}
