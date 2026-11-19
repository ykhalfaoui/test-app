package com.crok4it.audit.core;

import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.annotation.*;

/**
 * Meta-annotation for async event listeners that require automatic MDC restoration.
 *
 * Combining:
 *   - @TransactionalEventListener(AFTER_COMMIT) — fires only after the publisher transaction commits
 *   - @Async                                    — runs in a separate thread (MDC copied via ContextPropagatingTaskDecorator)
 *   - @Transactional(REQUIRES_NEW)              — own transaction for the listener's DB operations
 *
 * AuditContextRestorerAspect intercepts every method annotated with @AuditableListener
 * and automatically restores the MDC from event.auditMetadata() before execution.
 * Developers write zero audit code in their listeners.
 *
 * Usage:
 * <pre>{@code
 * @AuditableListener
 * void onOrderPlaced(OrderPlacedEvent event) {
 *     // MDC already has correlation_id, source_system, chain_depth
 *     log.info("Processing order {}", event.orderId()); // ← corr-id in log
 *     publisher.publishEvent(new OrderShippedEvent(...)); // ← inherits correlation
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
public @interface AuditableListener {
}
