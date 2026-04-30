package com.crok4it.audit.config;

import com.crok4it.audit.core.AuditMetadata;
import com.crok4it.audit.core.CurrentAuditHolder;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * Registers CurrentAuditHolder with Micrometer's ContextRegistry so that
 * ContextPropagatingTaskDecorator propagates AuditMetadata across async boundaries,
 * exactly as it does for SLF4J MDC.
 *
 * Without this, CurrentAuditHolder would be null in the executor thread even though
 * MDC is correctly propagated, breaking parent→child event correlation.
 */
class CurrentAuditHolderAccessor implements ThreadLocalAccessor<AuditMetadata> {

    static final String KEY = "audit.current-metadata";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public AuditMetadata getValue() {
        return CurrentAuditHolder.get();
    }

    @Override
    public void setValue(AuditMetadata value) {
        CurrentAuditHolder.set(value);
    }

    @Override
    public void setValue() {
        CurrentAuditHolder.clear();
    }
}
