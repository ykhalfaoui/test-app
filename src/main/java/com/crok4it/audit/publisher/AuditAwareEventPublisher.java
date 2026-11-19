package com.crok4it.audit.publisher;

import com.crok4it.audit.core.AuditMetadata;
import com.crok4it.audit.core.AuditableEvent;
import com.crok4it.audit.core.CurrentAuditHolder;
import com.crok4it.audit.core.MdcKeys;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * @Primary wrapper of ApplicationEventPublisher that transparently enriches
 * every AuditableEvent with AuditMetadata before dispatching.
 *
 * Developer code:
 *   publisher.publishEvent(new OrderPlacedEvent(orderId, customerId, null));
 *                                                                      ^^^^ null
 * This publisher intercepts the call, injects the AuditMetadata, and forwards to the multicaster.
 * The developer NEVER touches AuditMetadata directly.
 *
 * Delegation to ApplicationEventMulticaster (not to another ApplicationEventPublisher)
 * is intentional — avoids infinite self-delegation through the @Primary bean.
 */
@Slf4j
@Component
@Primary
public class AuditAwareEventPublisher implements ApplicationEventPublisher {

    private final ApplicationEventMulticaster multicaster;

    public AuditAwareEventPublisher(
            @Qualifier("applicationEventMulticaster") ApplicationEventMulticaster multicaster) {
        this.multicaster = multicaster;
    }

    @Override
    public void publishEvent(Object event) {
        Object enriched = enrichIfNeeded(event);
        ApplicationEvent appEvent = enriched instanceof ApplicationEvent ae
                ? ae
                : new PayloadApplicationEvent<>(this, enriched);
        multicaster.multicastEvent(appEvent);
    }

    private Object enrichIfNeeded(Object event) {
        if (!(event instanceof AuditableEvent auditable)) {
            return event;   // non-auditable events pass through unchanged
        }
        if (auditable.auditMetadata() != null) {
            return event;   // already enriched (e.g. replayed by Modulith)
        }
        AuditMetadata metadata = buildMetadata();
        log.debug("Enriching {} correlationId={} depth={} source={}",
                event.getClass().getSimpleName(),
                metadata.correlationId(), metadata.chainDepth(), metadata.sourceSystem());
        return auditable.withAuditMetadata(metadata);
    }

    /**
     * Metadata strategy:
     *   - CurrentAuditHolder has data → inside a listener → CHILD event (inherit correlationId)
     *   - Otherwise                  → first event in chain → ROOT event (read/generate correlationId)
     */
    private AuditMetadata buildMetadata() {
        AuditMetadata parent = CurrentAuditHolder.get();
        if (parent != null) {
            String causationId = MDC.get(MdcKeys.CAUSATION_ID);
            return AuditMetadata.forChildEvent(parent,
                    causationId != null ? causationId : UUID.randomUUID().toString());
        }
        return AuditMetadata.forRootEvent(detectSourceSystem());
    }

    private String detectSourceSystem() {
        String fromMdc = MDC.get(MdcKeys.SOURCE_SYSTEM);
        if (fromMdc != null) return fromMdc;
        String t = Thread.currentThread().getName();
        if (t.contains("nio") || t.startsWith("http-")) return "HTTP";
        if (t.contains("kafka") || t.contains("Kafka")) return "KAFKA";
        if (t.contains("sched") || t.startsWith("scheduling-")) return "SCHEDULER";
        return "INTERNAL";
    }
}
