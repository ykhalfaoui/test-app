package com.crok4it.audit;

import com.crok4it.audit.core.AuditEntry;
import com.crok4it.audit.core.AuditEntryRepository;
import com.crok4it.audit.core.MdcKeys;
import com.crok4it.order.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests verifying that the audit correlationId propagates automatically
 * through the full event chain without any developer code.
 */
@SpringBootTest
class AuditPropagationTest {

    @Autowired
    OrderService orderService;

    @Autowired
    AuditEntryRepository auditRepository;

    @AfterEach
    void cleanup() {
        MDC.clear();
        auditRepository.deleteAll();
    }

    @Test
    void correlationId_propagates_through_event_chain() {
        // Given: correlationId in MDC (set by CorrelationIdFilter in production)
        MDC.put(MdcKeys.CORRELATION_ID, "test-corr-001");
        MDC.put(MdcKeys.SOURCE_SYSTEM, "TEST");

        // When: place an order — zero audit code in OrderService
        orderService.placeOrder("cust-42", new BigDecimal("150.00"));

        // Then: both ORDER_PLACED and ORDER_SHIPPED must be in audit_entry
        //       with the SAME correlationId — without any developer effort
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<AuditEntry> entries =
                    auditRepository.findByCorrelationIdOrderByOccurredAtAsc("test-corr-001");

            assertThat(entries).hasSize(2);
            assertThat(entries).allMatch(e -> "test-corr-001".equals(e.getCorrelationId()));
            assertThat(entries.stream().map(AuditEntry::getActionType))
                    .containsExactlyInAnyOrder("ORDER_PLACED", "ORDER_SHIPPED");
        });
    }

    @Test
    void correlationId_generated_when_absent() {
        // No correlationId in MDC — AuditAwareEventPublisher must generate one
        orderService.placeOrder("cust-99", new BigDecimal("50.00"));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<AuditEntry> entries = auditRepository.findAll();
            assertThat(entries).isNotEmpty();

            String generatedCorrelationId = entries.get(0).getCorrelationId();
            assertThat(generatedCorrelationId).isNotBlank();

            // All entries in the chain share the auto-generated correlationId
            assertThat(entries).allMatch(e -> generatedCorrelationId.equals(e.getCorrelationId()));
        });
    }

    @Test
    void partyId_persisted_from_event() {
        MDC.put(MdcKeys.CORRELATION_ID, "test-corr-003");

        orderService.placeOrder("cust-77", new BigDecimal("200.00"));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<AuditEntry> entries =
                    auditRepository.findByCorrelationIdOrderByOccurredAtAsc("test-corr-003");

            AuditEntry placed = entries.stream()
                    .filter(e -> "ORDER_PLACED".equals(e.getActionType()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("ORDER_PLACED entry not found"));

            assertThat(placed.getPartyId()).isEqualTo("cust-77");
            assertThat(placed.getEntityType()).isEqualTo("ORDER");
            assertThat(placed.getOutcome()).isEqualTo(AuditEntry.Outcome.SUCCESS);
        });
    }

    @Test
    void child_event_has_higher_chain_depth() {
        MDC.put(MdcKeys.CORRELATION_ID, "test-corr-004");

        orderService.placeOrder("cust-55", new BigDecimal("75.00"));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<AuditEntry> entries =
                    auditRepository.findByCorrelationIdOrderByOccurredAtAsc("test-corr-004");

            assertThat(entries).hasSize(2);

            AuditEntry placed = entries.stream()
                    .filter(e -> "ORDER_PLACED".equals(e.getActionType())).findFirst().orElseThrow();
            AuditEntry shipped = entries.stream()
                    .filter(e -> "ORDER_SHIPPED".equals(e.getActionType())).findFirst().orElseThrow();

            // Root event → depth 0; child event (published inside listener) → depth 1
            assertThat(placed.getChainDepth()).isEqualTo(0);
            assertThat(shipped.getChainDepth()).isEqualTo(1);
        });
    }
}
