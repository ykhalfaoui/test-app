package com.crok4it.audit.core;

/**
 * ThreadLocal that holds the AuditMetadata of the listener currently executing.
 *
 * This enables automatic parent → child event propagation:
 * when a listener publishes a new event, AuditAwareEventPublisher reads
 * CurrentAuditHolder.get() and builds child metadata (same correlationId, depth+1).
 *
 * Lifecycle managed by AuditContextRestorerAspect:
 *   - set() before the listener method executes
 *   - clear() (or restore previous) in finally
 *
 * Note: if you use @Async executors, configure them with ContextPropagatingTaskDecorator
 * to copy this ThreadLocal across thread boundaries.
 */
public final class CurrentAuditHolder {

    private static final ThreadLocal<AuditMetadata> CURRENT = new ThreadLocal<>();

    private CurrentAuditHolder() {}

    public static void set(AuditMetadata metadata) {
        CURRENT.set(metadata);
    }

    public static AuditMetadata get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
