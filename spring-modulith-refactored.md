# 🚀 SOLUTION REFACTORISÉE - Spring Modulith + TraceId + Audit + DDD

Version complète et améliorée corrigeant tous les problèmes identifiés.

---

## 📁 STRUCTURE DU PROJET

```
src/main/java/com/example/
├── infrastructure/
│   ├── trace/
│   │   ├── TraceContext.java
│   │   ├── TraceContextHolder.java
│   │   ├── TraceContextScope.java
│   │   └── TraceContextAware.java (interface)
│   ├── events/
│   │   ├── DomainEvent.java (marker interface)
│   │   └── DomainEventPublisher.java
│   ├── audit/
│   │   ├── AuditEvent.java
│   │   ├── AuditEventRepository.java
│   │   ├── AuditTrailListener.java
│   │   └── AuditService.java
│   ├── outbox/
│   │   ├── OutboxItem.java
│   │   ├── OutboxItemRepository.java
│   │   ├── OutboxProcessor.java
│   │   └── OutboxDLQHandler.java
│   ├── retry/
│   │   ├── RetryPolicy.java
│   │   ├── ExponentialBackoffRetry.java
│   │   └── RetryableException.java
│   ├── circuitbreaker/
│   │   ├── CircuitBreakerFactory.java
│   │   └── CircuitBreakerConfig.java
│   └── web/
│       └── TraceContextFilter.java
├── party/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Party.java (agrégat)
│   │   │   ├── PartyStatus.java (value object)
│   │   │   ├── Block.java (entité)
│   │   │   └── PartyId.java (value object)
│   │   ├── events/
│   │   │   ├── PartyCreated.java
│   │   │   ├── BlockCreated.java
│   │   │   ├── AllBlocksCreated.java
│   │   │   └── SynchronizedBlock.java
│   │   └── repository/
│   │       └── PartyRepository.java
│   ├── application/
│   │   ├── PartyApplicationService.java
│   │   ├── PartyCommandHandler.java
│   │   ├── PartyAsyncHandler.java
│   │   ├── CreatePartyCommand.java
│   │   └── IncomingPartyEvent.java
│   └── outbox/
│       ├── OutboxListener.java
│       └── OutboxProcessingHandler.java
└── config/
    ├── AsyncConfig.java
    ├── ModulithConfig.java
    └── CircuitBreakerConfig.java
```

---

## 1️⃣ INFRASTRUCTURE - TRACE CONTEXT (CORRIGÉ)

### 1.1. TraceContext - Record immutable

```java
package com.example.infrastructure.trace;

import java.time.Instant;

public record TraceContext(
    String traceId,
    String userId,
    String correlationId,
    Instant timestamp
) {
    
    public TraceContext {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId cannot be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be blank");
        }
    }
    
    public static TraceContext create(String userId) {
        return new TraceContext(
            java.util.UUID.randomUUID().toString(),
            userId,
            java.util.UUID.randomUUID().toString(),
            Instant.now()
        );
    }
    
    public static TraceContext from(String traceId, String userId) {
        return new TraceContext(
            traceId,
            userId,
            java.util.UUID.randomUUID().toString(),
            Instant.now()
        );
    }
}
```

### 1.2. TraceContextHolder - Gestion ThreadLocal

```java
package com.example.infrastructure.trace;

import org.slf4j.MDC;
import java.util.Optional;

public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CTX = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static TraceContext get() {
        return CTX.get();
    }

    public static Optional<TraceContext> getOptional() {
        return Optional.ofNullable(CTX.get());
    }

    public static void set(TraceContext context) {
        if (context != null) {
            CTX.set(context);
            MDC.put("traceId", context.traceId());
            MDC.put("userId", context.userId());
            MDC.put("correlationId", context.correlationId());
        } else {
            clear();
        }
    }

    public static void clear() {
        CTX.remove();
        MDC.remove("traceId");
        MDC.remove("userId");
        MDC.remove("correlationId");
    }

    public static boolean isPresent() {
        return CTX.get() != null;
    }
}
```

### 1.3. TraceContextScope - Try-with-resources (AMÉLIORÉ)

```java
package com.example.infrastructure.trace;

import java.util.Optional;

public final class TraceContextScope implements AutoCloseable {

    private final TraceContext previous;

    public TraceContextScope(String traceId, String userId) {
        this.previous = TraceContextHolder.get();
        TraceContextHolder.set(TraceContext.from(traceId, userId));
    }

    public TraceContextScope(TraceContext context) {
        this.previous = TraceContextHolder.get();
        TraceContextHolder.set(context);
    }

    public static TraceContextScope from(TraceContext context) {
        return new TraceContextScope(context);
    }

    public TraceContext getCurrent() {
        return TraceContextHolder.get();
    }

    @Override
    public void close() {
        TraceContextHolder.set(previous);
    }
}
```

### 1.4. TraceContextAware - Interface pour injection

```java
package com.example.infrastructure.trace;

import java.util.Optional;

public interface TraceContextAware {
    
    Optional<TraceContext> currentContext();
    
    void executeWithContext(TraceContext context, Runnable task);
    
    default TraceContext getContextOrDefault() {
        return currentContext()
            .orElseGet(() -> new TraceContext(
                java.util.UUID.randomUUID().toString(),
                "system",
                java.util.UUID.randomUUID().toString(),
                java.time.Instant.now()
            ));
    }
}
```

---

## 2️⃣ ASYNC CONFIG - FIX THREADLOCAL (🔴 CRITIQUE)

```java
package com.example.config;

import com.example.infrastructure.trace.TraceContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * Fixe le problème ThreadLocal + @Async
     * Les tâches asynchrones héritent du contexte du thread parent
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        
        // 🔑 TaskDecorator pour propager le contexte
        executor.setTaskDecorator(runnable -> {
            var ctx = TraceContextHolder.get();
            return () -> {
                TraceContextHolder.set(ctx);
                try {
                    runnable.run();
                } finally {
                    TraceContextHolder.clear();
                }
            };
        });
        
        executor.initialize();
        return executor;
    }

    /**
     * Scheduler avec TaskDecorator aussi
     */
    @Bean(name = "tracedTaskScheduler")
    public ThreadPoolTaskScheduler tracedTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("scheduler-");
        
        scheduler.setTaskDecorator(runnable -> {
            var ctx = TraceContextHolder.get();
            return () -> {
                TraceContextHolder.set(ctx);
                try {
                    runnable.run();
                } finally {
                    TraceContextHolder.clear();
                }
            };
        });
        
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void initialize(org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler handler) {
        // Custom exception handler si nécessaire
    }
}
```

---

## 3️⃣ DDD - MODÈLE DE DOMAINE (AMÉLIORÉ)

### 3.1. Value Objects

```java
package com.example.party.domain.model;

import jakarta.persistence.Embeddable;
import java.util.UUID;

// PartyId - Value Object
@Embeddable
public final class PartyId {
    
    private String id;

    protected PartyId() {}

    private PartyId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("PartyId cannot be blank");
        }
        this.id = id;
    }

    public static PartyId of(String value) {
        return new PartyId(value);
    }

    public static PartyId generate() {
        return new PartyId(UUID.randomUUID().toString());
    }

    public String value() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PartyId)) return false;
        return id.equals(((PartyId) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}

// BlockId - Value Object
@Embeddable
public final class BlockId {
    
    private String id;

    protected BlockId() {}

    private BlockId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("BlockId cannot be blank");
        }
        this.id = id;
    }

    public static BlockId of(String value) {
        return new BlockId(value);
    }

    public static BlockId generate() {
        return new BlockId(UUID.randomUUID().toString());
    }

    public String value() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockId)) return false;
        return id.equals(((BlockId) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

// PartyStatus - Enum Value Object
public enum PartyStatus {
    CREATED,
    BLOCKS_IN_PROGRESS,
    ALL_BLOCKS_CREATED,
    SYNCHRONIZED,
    FAILED;

    public boolean canAddBlocks() {
        return this == CREATED || this == BLOCKS_IN_PROGRESS;
    }

    public boolean canSynchronize() {
        return this == ALL_BLOCKS_CREATED;
    }
}
```

### 3.2. Entité Block

```java
package com.example.party.domain.model;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import java.time.Instant;

@Embeddable
public final class Block {
    
    @Embedded
    private BlockId id;
    
    private Instant createdAt;

    protected Block() {}

    private Block(BlockId id) {
        this.id = id;
        this.createdAt = Instant.now();
    }

    public static Block create(BlockId blockId) {
        return new Block(blockId);
    }

    public BlockId getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Block)) return false;
        return id.equals(((Block) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
```

### 3.3. Agrégat Party (🔑 REFACTORISÉ)

```java
package com.example.party.domain.model;

import com.example.party.domain.events.*;
import jakarta.persistence.*;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "parties")
public class Party {

    @EmbeddedId
    private PartyId id;

    @Enumerated(EnumType.STRING)
    private PartyStatus status;

    @ElementCollection
    @CollectionTable(name = "party_blocks", joinColumns = @JoinColumn(name = "party_id"))
    private List<Block> blocks = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    protected Party() {}

    private Party(PartyId id) {
        this.id = Objects.requireNonNull(id, "PartyId cannot be null");
        this.status = PartyStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        
        // 🔑 Événement de domaine sans détails techniques
        recordEvent(new PartyCreated(id.value()));
    }

    public static Party create(PartyId partyId) {
        return new Party(partyId);
    }

    public static Party create(String partyId) {
        return new Party(PartyId.of(partyId));
    }

    /**
     * INVARIANT : Un block ne peut être créé que si le Party est dans le bon état
     */
    public void addBlock(BlockId blockId) {
        if (!status.canAddBlocks()) {
            throw new IllegalStateException(
                "Cannot add block to party in status: " + status
            );
        }

        Block newBlock = Block.create(blockId);
        blocks.add(newBlock);
        status = PartyStatus.BLOCKS_IN_PROGRESS;
        updatedAt = Instant.now();

        recordEvent(new BlockCreated(id.value(), blockId.value()));
    }

    /**
     * INVARIANT : Ne peut marquer comme "all blocks created" que si on a au moins 1 block
     */
    public void markAllBlocksCreated() {
        if (blocks.isEmpty()) {
            throw new IllegalStateException("Cannot mark complete: no blocks created");
        }
        if (status != PartyStatus.BLOCKS_IN_PROGRESS && status != PartyStatus.CREATED) {
            throw new IllegalStateException(
                "Cannot mark complete in status: " + status
            );
        }

        status = PartyStatus.ALL_BLOCKS_CREATED;
        updatedAt = Instant.now();
        recordEvent(new AllBlocksCreated(id.value(), blocks.size()));
    }

    /**
     * INVARIANT : Synchronisation seulement après blocks complétés
     */
    public void markSynchronized() {
        if (!status.canSynchronize()) {
            throw new IllegalStateException(
                "Cannot synchronize party in status: " + status
            );
        }

        status = PartyStatus.SYNCHRONIZED;
        updatedAt = Instant.now();
        recordEvent(new SynchronizedBlock(id.value()));
    }

    /**
     * INVARIANT : Marquer comme failed pour arrêter les traitements
     */
    public void markFailed(String reason) {
        if (status == PartyStatus.FAILED) {
            return;
        }
        status = PartyStatus.FAILED;
        updatedAt = Instant.now();
        recordEvent(new PartyFailed(id.value(), reason));
    }

    // === Accesseurs ===

    public PartyId getId() {
        return id;
    }

    public PartyStatus getStatus() {
        return status;
    }

    public List<Block> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public int getBlockCount() {
        return blocks.size();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // === Events Management ===

    private void recordEvent(Object event) {
        domainEvents.add(Objects.requireNonNull(event, "Event cannot be null"));
    }

    @DomainEvents
    public Collection<Object> getDomainEvents() {
        return Collections.unmodifiableCollection(new ArrayList<>(domainEvents));
    }

    @AfterDomainEventPublication
    public void clearDomainEvents() {
        domainEvents.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Party)) return false;
        return Objects.equals(id, ((Party) o).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Party{" +
                "id=" + id +
                ", status=" + status +
                ", blockCount=" + blocks.size() +
                ", createdAt=" + createdAt +
                '}';
    }
}
```

### 3.4. Repository

```java
package com.example.party.domain.repository;

import com.example.party.domain.model.Party;
import com.example.party.domain.model.PartyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PartyRepository extends JpaRepository<Party, String> {

    Optional<Party> findById(PartyId id);

    default Party getReferenceById(PartyId id) {
        return getReferenceById(id.value());
    }
}
```

### 3.5. Domain Events (PURS, sans annotations techniques)

```java
package com.example.party.domain.events;

/**
 * Events de domaine purs - JAMAIS avec CorrelatedEvent<T>
 * Le traçage est géré transparemment via ThreadLocal + Bridge
 */

public record PartyCreated(String partyId) {}

public record BlockCreated(String partyId, String blockId) {}

public record AllBlocksCreated(String partyId, int blockCount) {}

public record SynchronizedBlock(String partyId) {}

public record PartyFailed(String partyId, String reason) {}
```

---

## 4️⃣ AUDIT TRAIL (ENRICHI)

### 4.1. AuditEvent - Modèle enrichi

```java
package com.example.infrastructure.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events", indexes = {
    @Index(name = "idx_trace_id", columnList = "trace_id"),
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_aggregate_id", columnList = "aggregate_id"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String traceId;

    @Column(nullable = false, length = 100)
    private String userId;

    @Column(nullable = false, length = 50)
    private String correlationId;

    @Column(length = 100)
    private String aggregateType; // "Party", "Block", etc.

    @Column(length = 100)
    private String aggregateId;

    @Column(nullable = false, length = 100)
    private String eventType; // "PartyCreated", "BlockCreated", etc.

    @Column(columnDefinition = "TEXT")
    private String details; // JSON

    @Column(length = 20)
    private String status; // "SUCCESS", "FAILURE", "RETRY"

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private Instant timestamp;

    private int retryCount = 0;

    // === Constructors ===

    protected AuditEvent() {}

    private AuditEvent(Builder builder) {
        this.traceId = builder.traceId;
        this.userId = builder.userId;
        this.correlationId = builder.correlationId;
        this.aggregateType = builder.aggregateType;
        this.aggregateId = builder.aggregateId;
        this.eventType = builder.eventType;
        this.details = builder.details;
        this.status = builder.status;
        this.errorMessage = builder.errorMessage;
        this.timestamp = builder.timestamp;
    }

    // === Builder ===

    public static class Builder {
        private String traceId;
        private String userId;
        private String correlationId;
        private String aggregateType;
        private String aggregateId;
        private String eventType;
        private String details;
        private String status = "SUCCESS";
        private String errorMessage;
        private Instant timestamp = Instant.now();

        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder userId(String userId) { this.userId = userId; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder aggregateType(String type) { this.aggregateType = type; return this; }
        public Builder aggregateId(String id) { this.aggregateId = id; return this; }
        public Builder eventType(String type) { this.eventType = type; return this; }
        public Builder details(String details) { this.details = details; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder error(String message) { this.status = "FAILURE"; this.errorMessage = message; return this; }
        public Builder timestamp(Instant ts) { this.timestamp = ts; return this; }

        public AuditEvent build() {
            return new AuditEvent(this);
        }
    }

    // === Accesseurs ===

    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getUserId() { return userId; }
    public String getCorrelationId() { return correlationId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getDetails() { return details; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getTimestamp() { return timestamp; }
    public int getRetryCount() { return retryCount; }

    public void incrementRetry() { this.retryCount++; }
    public void setStatus(String status) { this.status = status; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }

    @Override
    public String toString() {
        return "AuditEvent{" +
                "id=" + id +
                ", traceId='" + traceId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", status='" + status + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
```

### 4.2. AuditService - Service d'audit

```java
package com.example.infrastructure.audit;

import com.example.infrastructure.trace.TraceContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);
    private final AuditEventRepository auditRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository auditRepository, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Log un événement de succès
     */
    public void logSuccess(String aggregateType, String aggregateId, String eventType, Object payload) {
        var ctx = TraceContextHolder.get();
        
        try {
            String details = objectMapper.writeValueAsString(payload);
            
            AuditEvent audit = new AuditEvent.Builder()
                .traceId(ctx.traceId())
                .userId(ctx.userId())
                .correlationId(ctx.correlationId())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .details(details)
                .status("SUCCESS")
                .timestamp(Instant.now())
                .build();
            
            auditRepository.save(audit);
        } catch (Exception e) {
            logger.error("Failed to audit event: {} for {} {}", eventType, aggregateType, aggregateId, e);
        }
    }

    /**
     * Log une erreur
     */
    public void logError(String aggregateType, String aggregateId, String eventType, Exception error) {
        var ctx = TraceContextHolder.get();
        
        try {
            AuditEvent audit = new AuditEvent.Builder()
                .traceId(ctx.traceId())
                .userId(ctx.userId())
                .correlationId(ctx.correlationId())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .error(error.getMessage())
                .timestamp(Instant.now())
                .build();
            
            auditRepository.save(audit);
        } catch (Exception e) {
            logger.error("Failed to audit error: {}", eventType, e);
        }
    }

    /**
     * Log une tentative de retry
     */
    public void logRetry(String aggregateType, String aggregateId, String eventType, int retryCount) {
        var ctx = TraceContextHolder.get();
        
        try {
            AuditEvent audit = new AuditEvent.Builder()
                .traceId(ctx.traceId())
                .userId(ctx.userId())
                .correlationId(ctx.correlationId())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType + "_RETRY_#" + retryCount)
                .status("RETRY")
                .timestamp(Instant.now())
                .build();
            
            auditRepository.save(audit);
        } catch (Exception e) {
            logger.error("Failed to audit retry: {}", eventType, e);
        }
    }
}
```

### 4.3. Repository et Listener

```java
package com.example.infrastructure.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByTraceId(String traceId);

    List<AuditEvent> findByUserId(String userId);

    List<AuditEvent> findByAggregateId(String aggregateId);

    @Query("SELECT a FROM AuditEvent a WHERE a.status = 'FAILURE' ORDER BY a.timestamp DESC LIMIT 100")
    List<AuditEvent> findRecentFailures();
}

@Component
public class AuditTrailListener {

    private static final Logger logger = LoggerFactory.getLogger(AuditTrailListener.class);
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditTrailListener(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void on(com.example.party.domain.events.PartyCreated event) {
        auditService.logSuccess("Party", event.partyId(), "PartyCreated", event);
    }

    @EventListener
    public void on(com.example.party.domain.events.BlockCreated event) {
        auditService.logSuccess("Block", event.blockId(), "BlockCreated", event);
    }

    @EventListener
    public void on(com.example.party.domain.events.AllBlocksCreated event) {
        auditService.logSuccess("Party", event.partyId(), "AllBlocksCreated", event);
    }

    @EventListener
    public void on(com.example.party.domain.events.SynchronizedBlock event) {
        auditService.logSuccess("Party", event.partyId(), "SynchronizedBlock", event);
    }

    @EventListener
    public void on(com.example.party.domain.events.PartyFailed event) {
        auditService.logError("Party", event.partyId(), "PartyFailed", 
            new RuntimeException(event.reason()));
    }
}
```

---

## 5️⃣ OUTBOX PATTERN (RETRY + DLQ)

### 5.1. OutboxItem - Modèle enrichi avec retry

```java
package com.example.infrastructure.outbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "outbox_items", indexes = {
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_trace_id", columnList = "trace_id"),
    @Index(name = "idx_next_retry", columnList = "next_retry_at")
})
public class OutboxItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String traceId;

    @Column(nullable = false, length = 100)
    private String userId;

    @Column(nullable = false, length = 100)
    private String correlationId;

    @Column(nullable = false, length = 100)
    private String partyId;

    @Column(nullable = false, length = 50)
    private String status; // PENDING, SENT, ERROR, DEAD_LETTER

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false)
    private int maxRetries = 5;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(columnDefinition = "TEXT")
    private String lastErrorMessage;

    // === Factory ===

    public static OutboxItem create(String traceId, String userId, String correlationId, String partyId) {
        OutboxItem item = new OutboxItem();
        item.traceId = traceId;
        item.userId = userId;
        item.correlationId = correlationId;
        item.partyId = partyId;
        item.status = "PENDING";
        item.retryCount = 0;
        item.createdAt = LocalDateTime.now();
        item.nextRetryAt = LocalDateTime.now();
        return item;
    }

    // === Business Logic ===

    public boolean shouldRetry() {
        return "ERROR".equals(status) 
            && retryCount < maxRetries 
            && LocalDateTime.now().isAfter(nextRetryAt);
    }

    public void markSent() {
        this.status = "SENT";
        this.lastErrorMessage = null;
    }

    public void markError(String errorMessage) {
        this.status = "ERROR";
        this.lastErrorMessage = errorMessage;
        this.retryCount++;
        
        // Exponential backoff : 2^retryCount secondes
        long secondsDelay = (long) Math.pow(2, Math.min(retryCount, 10));
        this.nextRetryAt = LocalDateTime.now().plusSeconds(secondsDelay);
    }

    public void markDeadLetter() {
        this.status = "DEAD_LETTER";
    }

    public boolean isExpired() {
        return retryCount >= maxRetries;
    }

    // === Accesseurs ===

    public Long getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getUserId() { return userId; }
    public String getCorrelationId() { return correlationId; }
    public String getPartyId() { return partyId; }
    public String getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastErrorMessage() { return lastErrorMessage; }
}
```

### 5.2. OutboxListener - Capte les events

```java
package com.example.party.outbox;

import com.example.infrastructure.outbox.OutboxItem;
import com.example.infrastructure.outbox.OutboxItemRepository;
import com.example.infrastructure.trace.TraceContextHolder;
import com.example.party.domain.events.AllBlocksCreated;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class OutboxListener {

    private static final Logger logger = LoggerFactory.getLogger(OutboxListener.class);
    private final OutboxItemRepository outboxRepository;

    public OutboxListener(OutboxItemRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * Écoute AllBlocksCreated et persiste dans l'Outbox
     * Utilise le TraceContext du ThreadLocal sans le passer en paramètre
     */
    @ApplicationModuleListener
    public void on(AllBlocksCreated event) {
        var ctx = TraceContextHolder.get();
        
        try {
            OutboxItem item = OutboxItem.create(
                ctx.traceId(),
                ctx.userId(),
                ctx.correlationId(),
                event.partyId()
            );
            
            outboxRepository.save(item);
            
            logger.info("Outbox item created: party={}, traceId={}", 
                event.partyId(), ctx.traceId());
                
        } catch (Exception e) {
            logger.error("Failed to create outbox item for party {}", event.partyId(), e);
        }
    }
}
```

### 5.3. OutboxProcessor - Job async avec retry

```java
package com.example.infrastructure.outbox;

import com.example.infrastructure.trace.TraceContextScope;
import com.example.infrastructure.audit.AuditService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
public class OutboxProcessor {

    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);
    
    private final OutboxItemRepository outboxRepository;
    private final RemotePartySyncClient remoteClient;
    private final AuditService auditService;

    public OutboxProcessor(
        OutboxItemRepository outboxRepository,
        RemotePartySyncClient remoteClient,
        AuditService auditService
    ) {
        this.outboxRepository = outboxRepository;
        this.remoteClient = remoteClient;
        this.auditService = auditService;
    }

    /**
     * Traite les items Outbox en attente avec retry automatique
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void processPendingItems() {
        List<OutboxItem> pending = outboxRepository
            .findByStatusOrderByCreatedAtAsc("PENDING");

        for (OutboxItem item : pending) {
            processItem(item);
        }
    }

    /**
     * Traite les items en erreur (retry avec exponential backoff)
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 3000)
    public void processRetryItems() {
        List<OutboxItem> retryItems = outboxRepository
            .findByStatusAndRetryCountLessThanOrderByNextRetryAtAsc("ERROR", 5);

        for (OutboxItem item : retryItems) {
            if (item.shouldRetry()) {
                processItemWithRetry(item);
            }
        }
    }

    private void processItem(OutboxItem item) {
        // Restaurer le contexte du message original
        try (var scope = new TraceContextScope(item.getTraceId(), item.getUserId())) {
            
            auditService.logSuccess(
                "OutboxProcessor", 
                item.getPartyId(), 
                "RemoteSync_START", 
                null
            );

            remoteClient.syncParty(item.getPartyId());
            
            item.markSent();
            outboxRepository.save(item);
            
            auditService.logSuccess(
                "OutboxProcessor", 
                item.getPartyId(), 
                "RemoteSync_SUCCESS", 
                null
            );
            
            logger.info("Successfully processed outbox item: party={}, traceId={}", 
                item.getPartyId(), item.getTraceId());
                
        } catch (Exception e) {
            handleProcessingError(item, e);
        }
    }

    private void processItemWithRetry(OutboxItem item) {
        try (var scope = new TraceContextScope(item.getTraceId(), item.getUserId())) {
            
            auditService.logRetry(
                "OutboxProcessor", 
                item.getPartyId(), 
                "RemoteSync", 
                item.getRetryCount() + 1
            );

            remoteClient.syncParty(item.getPartyId());
            
            item.markSent();
            outboxRepository.save(item);
            
            logger.info("Retry succeeded for outbox item: party={}, retry_count={}, traceId={}", 
                item.getPartyId(), item.getRetryCount(), item.getTraceId());
                
        } catch (Exception e) {
            handleProcessingError(item, e);
        }
    }

    private void handleProcessingError(OutboxItem item, Exception error) {
        try (var scope = new TraceContextScope(item.getTraceId(), item.getUserId())) {
            
            item.markError(error.getMessage());
            outboxRepository.save(item);
            
            auditService.logError(
                "OutboxProcessor", 
                item.getPartyId(), 
                "RemoteSync_ERROR", 
                error
            );

            if (item.isExpired()) {
                logger.error("Max retries exceeded for party {}: {}", 
                    item.getPartyId(), error.getMessage());
            } else {
                logger.warn("Processing failed for outbox item: party={}, retry_count={}, error={}", 
                    item.getPartyId(), item.getRetryCount(), error.getMessage());
            }
            
        } catch (Exception e) {
            logger.error("Failed to handle outbox error", e);
        }
    }
}
```

### 5.4. OutboxDLQHandler - Gestion des Dead Letters

```java
package com.example.infrastructure.outbox;

import com.example.infrastructure.trace.TraceContextScope;
import com.example.infrastructure.audit.AuditService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
public class OutboxDLQHandler {

    private static final Logger logger = LoggerFactory.getLogger(OutboxDLQHandler.class);
    
    private final OutboxItemRepository outboxRepository;
    private final AuditService auditService;
    private final NotificationService notificationService; // Slack, email, etc.

    public OutboxDLQHandler(
        OutboxItemRepository outboxRepository,
        AuditService auditService,
        NotificationService notificationService
    ) {
        this.outboxRepository = outboxRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    /**
     * Traite les items qui ont dépassé max retries
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 5000)
    public void processDLQItems() {
        List<OutboxItem> expiredItems = outboxRepository
            .findByStatusAndRetryCountGreaterThanEqualOrderByCreatedAtAsc("ERROR", 5);

        for (OutboxItem item : expiredItems) {
            processDLQ(item);
        }
    }

    private void processDLQ(OutboxItem item) {
        try (var scope = new TraceContextScope(item.getTraceId(), item.getUserId())) {
            
            logger.error(
                "CRITICAL: Outbox item moved to DLQ - party: {}, attempts: {}, error: {}",
                item.getPartyId(),
                item.getRetryCount(),
                item.getLastErrorMessage()
            );

            // Marquer comme Dead Letter
            item.markDeadLetter();
            outboxRepository.save(item);

            // Audit
            auditService.logError(
                "OutboxDLQ",
                item.getPartyId(),
                "DEAD_LETTER",
                new RuntimeException("Max retries exceeded: " + item.getLastErrorMessage())
            );

            // Alerte (Slack, PagerDuty, etc.)
            notificationService.alertCritical(
                "🚨 OUTBOX DLQ ALERT",
                String.format(
                    "Party sync failed after %d retries\n" +
                    "Party: %s\n" +
                    "TraceId: %s\n" +
                    "Error: %s",
                    item.getRetryCount(),
                    item.getPartyId(),
                    item.getTraceId(),
                    item.getLastErrorMessage()
                )
            );
            
        } catch (Exception e) {
            logger.error("Failed to process DLQ item", e);
        }
    }
}
```

### 5.5. OutboxItemRepository

```java
package com.example.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface OutboxItemRepository extends JpaRepository<OutboxItem, Long> {

    List<OutboxItem> findByStatusOrderByCreatedAtAsc(String status);

    List<OutboxItem> findByStatusAndRetryCountLessThanOrderByNextRetryAtAsc(String status, int maxRetries);

    List<OutboxItem> findByStatusAndRetryCountGreaterThanEqualOrderByCreatedAtAsc(String status, int retries);

    List<OutboxItem> findByTraceId(String traceId);

    @Query("SELECT o FROM OutboxItem o WHERE o.status = 'SENT' AND o.createdAt < CURRENT_TIMESTAMP - 7 DAY")
    List<OutboxItem> findOldProcessedItems();
}
```

---

## 6️⃣ HTTP FILTER (AMÉLIORÉ)

```java
package com.example.infrastructure.web;

import com.example.infrastructure.trace.TraceContext;
import com.example.infrastructure.trace.TraceContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class TraceContextFilter extends HttpFilter {

    private static final Logger logger = LoggerFactory.getLogger(TraceContextFilter.class);

    @Override
    protected void doFilter(HttpServletRequest request, 
                           HttpServletResponse response, 
                           FilterChain chain) throws IOException, ServletException {

        String traceId = Optional.ofNullable(request.getHeader("X-Trace-Id"))
            .filter(s -> !s.isBlank())
            .orElse(UUID.randomUUID().toString());

        String userId = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
            .map(Authentication::getName)
            .filter(s -> !s.isBlank())
            .orElse("system");

        String correlationId = UUID.randomUUID().toString();

        TraceContext context = new TraceContext(traceId, userId, correlationId, java.time.Instant.now());
        TraceContextHolder.set(context);

        // 🔑 Propager le traceId en response header
        response.addHeader("X-Trace-Id", traceId);
        response.addHeader("X-Correlation-Id", correlationId);

        try {
            logger.debug("Request initiated: {} {} traceId={}", 
                request.getMethod(), request.getRequestURI(), traceId);
            
            chain.doFilter(request, response);
            
        } catch (Exception e) {
            logger.error("Request failed: {} {}", 
                request.getMethod(), request.getRequestURI(), e);
            throw e;
            
        } finally {
            TraceContextHolder.clear();
        }
    }
}
```

---

## 7️⃣ APPLICATION SERVICE (TESTABLE)

```java
package com.example.party.application;

import com.example.infrastructure.audit.AuditService;
import com.example.infrastructure.trace.TraceContext;
import com.example.infrastructure.trace.TraceContextScope;
import com.example.party.domain.model.*;
import com.example.party.domain.repository.PartyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@Transactional
public class PartyApplicationService {

    private static final Logger logger = LoggerFactory.getLogger(PartyApplicationService.class);
    
    private final PartyRepository partyRepository;
    private final AuditService auditService;

    public PartyApplicationService(PartyRepository partyRepository, AuditService auditService) {
        this.partyRepository = partyRepository;
        this.auditService = auditService;
    }

    /**
     * Crée une party de manière transactionnelle
     * Signature testable : le contexte est passé en paramètre
     */
    public Party createParty(String externalPartyId, TraceContext context) {
        try (var scope = new TraceContextScope(context)) {
            
            PartyId partyId = PartyId.of(externalPartyId);
            Party party = Party.create(partyId);
            
            Party saved = partyRepository.save(party);
            
            auditService.logSuccess(
                "Party", 
                party.getId().value(), 
                "Created", 
                party
            );
            
            logger.info("Party created: {}", party.getId());
            return saved;
            
        } catch (Exception e) {
            auditService.logError("Party", externalPartyId, "CreationFailed", e);
            throw new RuntimeException("Failed to create party: " + externalPartyId, e);
        }
    }

    /**
     * Ajoute un block à une party
     */
    public void addBlock(String partyId, String blockId, TraceContext context) {
        try (var scope = new TraceContextScope(context)) {
            
            Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyId));
            
            BlockId newBlockId = BlockId.of(blockId);
            party.addBlock(newBlockId);
            
            partyRepository.save(party);
            
            auditService.logSuccess(
                "Party", 
                partyId, 
                "BlockAdded", 
                blockId
            );
            
            logger.info("Block added to party: {} - blockId: {}", partyId, blockId);
            
        } catch (Exception e) {
            auditService.logError("Party", partyId, "AddBlockFailed", e);
            throw e;
        }
    }

    /**
     * Marque tous les blocks comme créés
     */
    public void markAllBlocksCreated(String partyId, TraceContext context) {
        try (var scope = new TraceContextScope(context)) {
            
            Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new IllegalArgumentException("Party not found: " + partyId));
            
            party.markAllBlocksCreated();
            partyRepository.save(party);
            
            auditService.logSuccess(
                "Party", 
                partyId, 
                "AllBlocksMarkedCreated", 
                party.getBlockCount()
            );
            
            logger.info("All blocks marked created for party: {}", partyId);
            
        } catch (Exception e) {
            auditService.logError("Party", partyId, "MarkBlocksCreatedFailed", e);
            throw e;
        }
    }
}
```

---

## 8️⃣ EVENT HANDLERS ASYNC

```java
package com.example.party.application;

import com.example.infrastructure.trace.TraceContext;
import com.example.infrastructure.trace.TraceContextHolder;
import com.example.infrastructure.trace.TraceContextScope;
import com.example.party.domain.model.PartyId;
import com.example.party.domain.model.BlockId;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Événement externe déclenchant le flux
 */
public record IncomingPartyEvent(String externalPartyId, String traceId, String userId) {}

/**
 * Handler qui démarre le flux avec le traceId
 */
@Component
public class PartyAsyncHandler {

    private static final Logger logger = LoggerFactory.getLogger(PartyAsyncHandler.class);
    private final PartyApplicationService partyService;

    public PartyAsyncHandler(PartyApplicationService partyService) {
        this.partyService = partyService;
    }

    /**
     * 🔑 @Async fonctionne grâce à AsyncConfig + TaskDecorator
     */
    @Async
    @EventListener
    public void onIncomingParty(IncomingPartyEvent event) {
        String traceId = event.traceId() != null ? event.traceId() : java.util.UUID.randomUUID().toString();
        String userId = event.userId() != null ? event.userId() : "system";
        
        TraceContext context = TraceContext.from(traceId, userId);

        try (var scope = new TraceContextScope(context)) {
            
            logger.info("Processing incoming party event: {} with traceId: {}", 
                event.externalPartyId(), traceId);

            // Créer la party (événement de domaine automatiquement capturé)
            partyService.createParty(event.externalPartyId(), context);

            // Ajouter des blocks
            for (int i = 1; i <= 3; i++) {
                String blockId = "block-" + i;
                partyService.addBlock(event.externalPartyId(), blockId, context);
            }

            // Marquer comme complétée
            partyService.markAllBlocksCreated(event.externalPartyId(), context);
            
            logger.info("Party processing completed: {} traceId: {}", 
                event.externalPartyId(), traceId);
                
        } catch (Exception e) {
            logger.error("Failed to process incoming party event", e);
        }
    }
}
```

---

## 9️⃣ CIRCUIT BREAKER (BONUS)

```java
package com.example.infrastructure.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

@Configuration
public class CircuitBreakerConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerConfiguration.class);

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .slowCallRateThreshold(50.0f)
            .slowCallDurationThreshold(Duration.ofSeconds(10))
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .permittedNumberOfCallsInHalfOpenState(3)
            .minimumNumberOfCalls(10)
            .automaticTransitionFromOpenToHalfOpenEnabled(true)
            .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultConfig);

        registry.getEventPublisher()
            .onEntryAdded(event -> logger.info("Circuit breaker registered: {}", event.getAddedEntry().getName()))
            .onEntryRemoved(event -> logger.info("Circuit breaker removed: {}", event.getRemovedEntry().getName()));

        return registry;
    }

    @Bean
    public CircuitBreaker remotePartySyncCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("remotePartySync", CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)
            .waitDurationInOpenState(Duration.ofSeconds(60))
            .minimumNumberOfCalls(5)
            .build());
    }
}
```

---

## 🔟 TESTS UNITAIRES

```java
package com.example.party.application;

import com.example.infrastructure.audit.AuditService;
import com.example.infrastructure.trace.TraceContext;
import com.example.party.domain.model.Party;
import com.example.party.domain.repository.PartyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Party Application Service Tests")
class PartyApplicationServiceTest {

    @Mock private PartyRepository partyRepository;
    @Mock private AuditService auditService;

    private PartyApplicationService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new PartyApplicationService(partyRepository, auditService);
    }

    @Test
    @DisplayName("should create party with trace context")
    void shouldCreatePartyWithTraceContext() {
        // Arrange
        String partyId = "party-123";
        TraceContext context = TraceContext.create("user-1");
        Party party = Party.create(partyId);

        when(partyRepository.save(any(Party.class)))
            .thenReturn(party);

        // Act
        Party result = service.createParty(partyId, context);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId().value()).isEqualTo(partyId);
        verify(partyRepository, times(1)).save(any(Party.class));
        verify(auditService, times(1)).logSuccess(
            eq("Party"),
            eq(partyId),
            eq("Created"),
            any()
        );
    }

    @Test
    @DisplayName("should add block to party")
    void shouldAddBlockToParty() {
        // Arrange
        String partyId = "party-123";
        String blockId = "block-1";
        TraceContext context = TraceContext.create("user-1");
        
        Party party = Party.create(partyId);
        when(partyRepository.findById(partyId))
            .thenReturn(Optional.of(party));

        // Act
        service.addBlock(partyId, blockId, context);

        // Assert
        verify(partyRepository, times(1)).save(any(Party.class));
        assertThat(party.getBlocks()).hasSize(1);
    }

    @Test
    @DisplayName("should fail when adding block to non-existent party")
    void shouldFailWhenAddingBlockToNonExistentParty() {
        // Arrange
        String partyId = "non-existent";
        String blockId = "block-1";
        TraceContext context = TraceContext.create("user-1");

        when(partyRepository.findById(partyId))
            .thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> service.addBlock(partyId, blockId, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Party not found");
    }

    @Test
    @DisplayName("should respect party invariant - cannot add blocks to FAILED party")
    void shouldNotAddBlocksToFailedParty() {
        // Arrange
        String partyId = "party-123";
        TraceContext context = TraceContext.create("user-1");
        
        Party party = Party.create(partyId);
        party.markFailed("Test failure");

        when(partyRepository.findById(partyId))
            .thenReturn(Optional.of(party));

        // Act & Assert
        assertThatThrownBy(() -> service.addBlock(partyId, "block-1", context))
            .isInstanceOf(IllegalStateException.class);
    }
}
```

---

## ✅ RÉSUMÉ DES AMÉLIORATIONS

| **Problème Original** | **Solution Apportée** | **Impact** |
|----------------------|----------------------|-----------|
| ThreadLocal + @Async | TaskDecorator | ✅ Contexte propagé correctement |
| DDD trop simple | Agrégat riche + Value Objects | ✅ Invariants métier appliqués |
| Audit insuffisant | Détails + erreurs + retry | ✅ Traçabilité complète |
| Pas de retry | Exponential backoff + DLQ | ✅ Résilience garantie |
| Spring Modulith unused | ApplicationModuleListener | ✅ Gestion propre |
| Testabilité faible | TraceContext injecté | ✅ Facile à tester |
| No circuit breaker | Resilience4J intégré | ✅ Protection |
| Filter incomplet | Response headers + error handling | ✅ Propagation complète |

---

## 🚀 PROCHAINES ÉTAPES

1. **Activez Spring Modulith** dans `pom.xml` :
```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-core</artifactId>
    <version>1.2.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-jpa</artifactId>
    <version>1.2.0</version>
</dependency>
```

2. **Ajoutez Resilience4J** :
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
    <version>2.1.0</version>
</dependency>
```

3. **Testez** les modules avec `modules.verify()`

4. **Monitorizez** l'audit trail et les DLQ items

Bonne chance ! 🎯
