package com.crok4it.order;

import com.crok4it.audit.core.AuditEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final AuditEntryRepository auditRepository;

    @PostMapping
    public ResponseEntity<Map<String, String>> placeOrder(
            @RequestParam String customerId,
            @RequestParam BigDecimal amount) {
        String orderId = orderService.placeOrder(customerId, amount);
        return ResponseEntity.ok(Map.of("orderId", orderId, "status", "ACCEPTED"));
    }

    /** Returns the full audit trail for a correlationId — one query for the entire flow. */
    @GetMapping("/audit/correlation")
    public ResponseEntity<?> getByCorrelation(@RequestParam String correlationId) {
        return ResponseEntity.ok(
                auditRepository.findByCorrelationIdOrderByOccurredAtAsc(correlationId));
    }

    /** Returns all audit entries for a given aggregate (entity). */
    @GetMapping("/audit/entity")
    public ResponseEntity<?> getByEntity(@RequestParam String entityType,
                                          @RequestParam String entityId) {
        return ResponseEntity.ok(
                auditRepository.findByEntityTypeAndEntityIdOrderByOccurredAtAsc(entityType, entityId));
    }
}
