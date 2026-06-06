package com.crok4it.audit.core;

/** Centralized MDC key constants shared across the entire audit infrastructure. */
public final class MdcKeys {

    private MdcKeys() {}

    // === Correlation (set by HTTP filter / Kafka interceptor) ===
    public static final String CORRELATION_ID = "business.correlation_id";
    public static final String CAUSATION_ID   = "business.causation_id";
    public static final String SOURCE_SYSTEM  = "business.source_system";

    // === Identity (optional, set by application layer) ===
    public static final String USER_ID   = "business.user_id";
    public static final String TENANT_ID = "business.tenant_id";

    // === Chain tracking (managed by AuditContextRestorerAspect) ===
    public static final String CHAIN_DEPTH = "business.chain_depth";
}
