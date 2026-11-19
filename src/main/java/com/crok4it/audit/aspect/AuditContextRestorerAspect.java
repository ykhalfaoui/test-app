package com.crok4it.audit.aspect;

import com.crok4it.audit.core.AuditMetadata;
import com.crok4it.audit.core.AuditableEvent;
import com.crok4it.audit.core.CurrentAuditHolder;
import com.crok4it.audit.core.MdcKeys;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Intercepts every @AuditableListener method and, before execution:
 *   1. Reads AuditMetadata from event.auditMetadata()
 *   2. Restores the MDC (correlation_id, source_system, chain_depth, …)
 *   3. Sets CurrentAuditHolder so child events published from within this listener inherit the correlation
 *   4. Detects replays (publishedAt > 30s ago) and marks source_system = REPLAY
 *
 * After execution (finally):
 *   5. Restores the previous MDC state
 *   6. Restores the previous CurrentAuditHolder (supports nested listeners)
 *
 * Developer cost: zero. Annotate your listener method with @AuditableListener — done.
 *
 * @Order(0) ensures this aspect wraps BEFORE @Transactional (which is typically Order(100)).
 */
@Aspect
@Component
@Order(0)
@Slf4j
public class AuditContextRestorerAspect {

    private static final Duration REPLAY_THRESHOLD = Duration.ofSeconds(30);

    private static final String[] MANAGED_MDC_KEYS = {
            MdcKeys.CORRELATION_ID, MdcKeys.CAUSATION_ID, MdcKeys.SOURCE_SYSTEM,
            MdcKeys.USER_ID, MdcKeys.TENANT_ID, MdcKeys.CHAIN_DEPTH
    };

    @Around("@annotation(com.crok4it.audit.core.AuditableListener) && args(event,..)")
    public Object restoreAuditContext(ProceedingJoinPoint pjp, Object event) throws Throwable {

        if (!(event instanceof AuditableEvent auditable) || auditable.auditMetadata() == null) {
            return pjp.proceed();
        }

        AuditMetadata metadata = auditable.auditMetadata();

        if (isReplay(metadata)) {
            metadata = metadata.asReplay();
            log.debug("Replay detected correlationId={} originalPublishedAt={}",
                    metadata.correlationId(), auditable.auditMetadata().publishedAt());
        }

        Map<String, String> previousMdc = snapshotManagedMdcKeys();
        AuditMetadata previousHolder = CurrentAuditHolder.get();

        try {
            applyToMdc(metadata);
            CurrentAuditHolder.set(metadata);

            // causationId for events published INSIDE this listener = stable id of this event
            MDC.put(MdcKeys.CAUSATION_ID, stableId(event));

            return pjp.proceed();

        } finally {
            if (previousHolder != null) {
                CurrentAuditHolder.set(previousHolder);
            } else {
                CurrentAuditHolder.clear();
            }
            restoreMdc(previousMdc);
        }
    }

    private boolean isReplay(AuditMetadata m) {
        return m.publishedAt() != null
                && Duration.between(m.publishedAt(), Instant.now()).compareTo(REPLAY_THRESHOLD) > 0;
    }

    private void applyToMdc(AuditMetadata m) {
        MDC.put(MdcKeys.CORRELATION_ID, m.correlationId());
        MDC.put(MdcKeys.SOURCE_SYSTEM, m.sourceSystem());
        MDC.put(MdcKeys.CHAIN_DEPTH, String.valueOf(m.chainDepth()));
        if (m.userId() != null) MDC.put(MdcKeys.USER_ID, m.userId());
        if (m.tenantId() != null) MDC.put(MdcKeys.TENANT_ID, m.tenantId());
        if (m.causationEventId() != null) MDC.put(MdcKeys.CAUSATION_ID, m.causationEventId());
    }

    private Map<String, String> snapshotManagedMdcKeys() {
        Map<String, String> snapshot = new HashMap<>();
        for (String key : MANAGED_MDC_KEYS) {
            String v = MDC.get(key);
            if (v != null) snapshot.put(key, v);
        }
        return snapshot;
    }

    private void restoreMdc(Map<String, String> snapshot) {
        for (String key : MANAGED_MDC_KEYS) MDC.remove(key);
        snapshot.forEach(MDC::put);
    }

    private String stableId(Object event) {
        return event.getClass().getSimpleName() + ":"
                + Integer.toHexString(System.identityHashCode(event));
    }
}
