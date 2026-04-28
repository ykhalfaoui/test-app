# Audit complet avec propagation du correlationId — Solution sans retry

**Stack** : Spring Boot 3.4 · Java 17 · Spring Modulith · Kafka inbox · Outbox Salesforce

**Objectif** : Tracer toutes les actions métier (succès et échecs) à travers le flux complet `Kafka → Domain → Outbox → Listeners async`, avec un `correlationId` qui les relie toutes pour pouvoir répondre à la question :

> *« Que s’est-il passé pour la review rev-123 ? »*

via une seule requête SQL.

**Hors scope de ce document** : retry programmatique des events Modulith incomplets (sera couvert dans un second document).

-----

## Table des matières

1. [Architecture du flux](#1-architecture-du-flux)
1. [Principes de design](#2-principes-de-design)
1. [Configuration](#3-configuration)
1. [Composants communs](#4-composants-communs)
1. [Étape 1 — Réception Kafka (Inbox)](#5-étape-1--réception-kafka-inbox)
1. [Étape 2 — Processing asynchrone](#6-étape-2--processing-asynchrone)
1. [Étape 3 — Service métier](#7-étape-3--service-métier)
1. [Étape 4 — Outbox writer](#8-étape-4--outbox-writer)
1. [Étape 5 — Listener async (chain)](#9-étape-5--listener-async-chain)
1. [Étape 6 — Outbox dispatcher](#10-étape-6--outbox-dispatcher)
1. [Vue d’ensemble par requêtes SQL](#11-vue-densemble-par-requêtes-sql)
1. [Récap des composants](#12-récap-des-composants)

-----

## 1. Architecture du flux

```
┌──────────────────────────────────────────────────────────────────┐
│                          Kafka topic                              │
│                  (avec header x-business-correlation-id)          │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│ [1] ReviewInboxConsumer (@KafkaListener)                          │
│     KafkaCorrelationInterceptor pose MDC[correlation_id]          │
│     Persiste InboxRecord (idempotence par messageId)              │
│     AuditTrail : RECEIVE_KAFKA                                    │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼  (différé)
┌──────────────────────────────────────────────────────────────────┐
│ [2] InboxProcessor (@Scheduled)                                   │
│     Restaure MDC[correlation_id] depuis InboxRecord               │
│     Appelle ReviewService.validate(reviewId)                      │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌──────────────────────────────────────────────────────────────────┐
│ [3] ReviewService.validate() (@Transactional)                     │
│     AuditTrail : VALIDATE                                         │
│     review.validate() → registerEvent(ReviewValidatedEvent)       │
│     COMMIT                                                        │
└──────────────────────────────────────────────────────────────────┘
                              │
                ┌─────────────┴─────────────┐
                ▼                           ▼
┌────────────────────────────┐  ┌────────────────────────────────┐
│ [4] OutboxWriterListener   │  │ [5] BlockCreationListener       │
│     @TransactionalEvent..  │  │     @ApplicationModuleListener  │
│     AFTER_COMMIT (sync)    │  │     (async par défaut Modulith) │
│                            │  │                                 │
│  ReviewValidatedEvent →    │  │  ReviewValidatedEvent →         │
│  UpsertReviewCommand       │  │  block.create()                 │
│  → OutboxRecord PENDING    │  │  → registerEvent(BlockCreated)  │
│                            │  │  → AuditTrail : CREATE_BLOCK    │
│  AuditTrail : WRITE_OUTBOX │  │                                 │
└────────────────────────────┘  └────────────────────────────────┘
                                            │
                                            ▼
                                  (chaîne d'events possible)
                              │
                              ▼  (toutes les 10s)
┌──────────────────────────────────────────────────────────────────┐
│ [6] OutboxDispatcher (@Scheduled)                                 │
│     Restaure MDC[correlation_id] depuis OutboxRecord              │
│     HTTP Salesforce                                               │
│     AuditTrail : SYNC_SALESFORCE                                  │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                          Salesforce
```

-----

## 2. Principes de design

### 2.1 Trois principes fondamentaux

**Principe 1 — Domain events purs**

Pas d’`AuditableEvent`, pas d’`AuditMetadata` dans les events. Les domain events ne portent que des données métier. C’est la règle DDD canonique.

```java
// ✓ Pur, recommandé
public record ReviewValidatedEvent(
    String reviewId,
    String customerId,
    String newStatus,
    Instant occurredAt
) {}
```

**Principe 2 — `AuditTrail` explicite, pas d’AOP**

3 lignes par méthode auditée, lisibles, testables, débuggables. Pas de magie, pas de proxy CGLIB qui pollue les stack traces.

```java
var trail = auditTrail.start("REVIEW", reviewId, "VALIDATE", review.getStatus());
try {
    review.validate();
    trail.success(review.getStatus());
} catch (BusinessException be) {
    trail.businessFailure(be);
    throw be;
}
```

**Principe 3 — `correlationId` propagé via MDC**

Le MDC SLF4J porte le `correlationId` à travers toute la chaîne. Pas de ThreadLocal custom, pas de wrapper du publisher Spring. Le seul effort est de **restaurer** le MDC quand on change de thread (scheduler, async listener).

### 2.2 Quels events nécessitent l’audit ?

**Tous les domain events de SUCCÈS** sont automatiquement tracés via `AuditTrail.success()` dans le service qui les publie. Pas besoin de listener générique.

**Les ÉCHECS** (qui n’ont pas de domain event puisque l’action n’a pas eu lieu) sont tracés par `AuditTrail.businessFailure()` ou `technicalFailure()` dans le `catch`.

### 2.3 Survie au rollback

`AuditTrail.persist()` utilise `Propagation.REQUIRES_NEW`. Si la transaction métier rollback à cause d’une exception, l’audit de l’échec est **quand même persisté** dans une transaction séparée.

-----

## 3. Configuration

### 3.1 `application.yaml`

```yaml
spring:
  application:
    name: review-service

  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: true
      completion-mode: update    # garde l'historique des events traités

  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    consumer:
      group-id: review-service
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: MANUAL_IMMEDIATE
      observation-enabled: true   # propage le tracing W3C

logging:
  structured:
    format:
      console: ecs
    ecs:
      service:
        name: ${spring.application.name}

management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0
```

### 3.2 Schema SQL

```sql
-- Table Modulith (auto-créée si schema-initialization=true)
-- event_publication

-- Notre table d'audit unifiée
CREATE TABLE audit_entry (
    id              UUID PRIMARY KEY,
    
    entity_type     VARCHAR(64)  NOT NULL,
    entity_id       VARCHAR(128) NOT NULL,
    party_id        VARCHAR(64),
    action_type     VARCHAR(64)  NOT NULL,
    
    outcome         VARCHAR(32)  NOT NULL,
    
    status_before   VARCHAR(64),
    status_after    VARCHAR(64),
    
    error_category  VARCHAR(32),
    error_type      VARCHAR(255),
    error_message   TEXT,
    
    correlation_id  VARCHAR(64)  NOT NULL,
    causation_id    VARCHAR(64),
    trace_id        VARCHAR(64),
    
    source_system   VARCHAR(32),
    
    started_at      TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP,
    duration_ms     BIGINT,
    
    details         TEXT
);

CREATE INDEX idx_audit_entity ON audit_entry(entity_type, entity_id);
CREATE INDEX idx_audit_correlation ON audit_entry(correlation_id);
CREATE INDEX idx_audit_party ON audit_entry(party_id, started_at);
CREATE INDEX idx_audit_action ON audit_entry(action_type, outcome, started_at);

-- Table inbox Kafka
CREATE TABLE inbox_record (
    id              UUID PRIMARY KEY,
    message_id      VARCHAR(128) NOT NULL UNIQUE,
    review_id       VARCHAR(128) NOT NULL,
    payload         TEXT NOT NULL,
    correlation_id  VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,    -- PENDING, PROCESSED, FAILED
    received_at     TIMESTAMP    NOT NULL,
    processed_at    TIMESTAMP,
    error_message   TEXT
);

CREATE INDEX idx_inbox_status ON inbox_record(status, received_at);

-- Table outbox Salesforce
CREATE TABLE outbox_record (
    id                UUID PRIMARY KEY,
    
    command_type      VARCHAR(128) NOT NULL,
    command_payload   TEXT NOT NULL,
    target_system     VARCHAR(64)  NOT NULL,
    
    aggregate_type    VARCHAR(64),
    aggregate_id      VARCHAR(128),
    party_id          VARCHAR(64),
    
    correlation_id    VARCHAR(64)  NOT NULL,
    caused_by_event   VARCHAR(128),
    
    status            VARCHAR(32)  NOT NULL,   -- PENDING, IN_PROGRESS, SUCCESS, FAILED, DEAD_LETTER
    attempt_count     INT          NOT NULL DEFAULT 0,
    max_attempts      INT          NOT NULL DEFAULT 5,
    
    last_http_status  INT,
    last_error        TEXT,
    
    created_at        TIMESTAMP    NOT NULL,
    next_attempt_at   TIMESTAMP,
    completed_at      TIMESTAMP
);

CREATE INDEX idx_outbox_dispatch ON outbox_record(status, next_attempt_at);
CREATE INDEX idx_outbox_aggregate ON outbox_record(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_correlation ON outbox_record(correlation_id);
```

-----

## 4. Composants communs

### 4.1 `MdcKeys.java`

```java
package com.crok4it.audit;

public final class MdcKeys {
    private MdcKeys() {}

    public static final String CORRELATION_ID = "business.correlation_id";
    public static final String CAUSATION_ID = "business.causation_id";
    public static final String SOURCE_SYSTEM = "business.source_system";
}
```

### 4.2 `AuditEntry.java` — l’entité JPA

```java
package com.crok4it.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_entry")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditEntry {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String entityType;

    @Column(nullable = false)
    private String entityId;

    private String partyId;

    @Column(nullable = false)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    private String statusBefore;
    private String statusAfter;

    private String errorCategory;
    private String errorType;
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private String correlationId;
    private String causationId;
    private String traceId;

    private String sourceSystem;

    @Column(nullable = false)
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String details;

    public enum Outcome {
        SUCCESS,
        BUSINESS_FAILURE,
        TECHNICAL_FAILURE,
        DEGRADED
    }
}
```

### 4.3 `AuditEntryRepository.java`

```java
package com.crok4it.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {
}
```

### 4.4 `AuditTrail.java` — la façade utilisée par les services

```java
package com.crok4it.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Façade explicite pour tracer une action métier.
 *
 * Usage standard :
 *   var trail = auditTrail.start("REVIEW", reviewId, "VALIDATE", currentStatus);
 *   try {
 *       // ... métier ...
 *       trail.success(newStatus);
 *   } catch (BusinessException be) {
 *       trail.businessFailure(be);
 *       throw be;
 *   } catch (Throwable t) {
 *       trail.technicalFailure(t);
 *       throw t;
 *   }
 *
 * Persistance en REQUIRES_NEW : l'audit survit même si la tx métier rollback.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditTrail {

    private final AuditEntryRepository repository;

    public AuditContext start(String entityType, String entityId,
                              String actionType, String statusBefore) {
        return new AuditContext(entityType, entityId, actionType, statusBefore, this);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(AuditEntry entry) {
        try {
            repository.save(entry);
        } catch (Exception e) {
            // L'audit ne doit JAMAIS faire échouer le métier
            log.error("Failed to persist audit entry for {}/{} action={}",
                entry.getEntityType(), entry.getEntityId(), entry.getActionType(), e);
        }
    }

    /**
     * Contexte mutable retourné par start(), exposant success/failure.
     * Capture le contexte d'infrastructure depuis le MDC à la création.
     */
    public static class AuditContext {

        private final UUID id = UUID.randomUUID();
        private final String entityType;
        private final String entityId;
        private final String actionType;
        private final String statusBefore;
        private final Instant startedAt = Instant.now();
        private final AuditTrail parent;

        // Snapshot du MDC au moment du start
        private final String correlationId = MDC.get(MdcKeys.CORRELATION_ID);
        private final String causationId = MDC.get(MdcKeys.CAUSATION_ID);
        private final String traceId = MDC.get("traceId");
        private final String sourceSystem = MDC.get(MdcKeys.SOURCE_SYSTEM);

        private String partyId;
        private String details;

        AuditContext(String entityType, String entityId, String actionType,
                     String statusBefore, AuditTrail parent) {
            this.entityType = entityType;
            this.entityId = entityId;
            this.actionType = actionType;
            this.statusBefore = statusBefore;
            this.parent = parent;
        }

        public AuditContext withPartyId(String partyId) {
            this.partyId = partyId;
            return this;
        }

        public AuditContext withDetails(String details) {
            this.details = details;
            return this;
        }

        public void success() {
            persist(AuditEntry.Outcome.SUCCESS, null, null, null, null);
        }

        public void success(String statusAfter) {
            persist(AuditEntry.Outcome.SUCCESS, statusAfter, null, null, null);
        }

        public void businessFailure(Throwable t) {
            persist(AuditEntry.Outcome.BUSINESS_FAILURE, null,
                "BUSINESS", t.getClass().getName(), t.getMessage());
        }

        public void technicalFailure(Throwable t) {
            persist(AuditEntry.Outcome.TECHNICAL_FAILURE, null,
                "TECHNICAL", t.getClass().getName(), t.getMessage());
        }

        public void integrationFailure(Throwable t) {
            persist(AuditEntry.Outcome.TECHNICAL_FAILURE, null,
                "INTEGRATION", t.getClass().getName(), t.getMessage());
        }

        public void degraded(String statusAfter, String reason) {
            persist(AuditEntry.Outcome.DEGRADED, statusAfter,
                "BUSINESS", null, reason);
        }

        private void persist(AuditEntry.Outcome outcome, String statusAfter,
                             String errorCategory, String errorType, String errorMessage) {
            Instant completedAt = Instant.now();
            var entry = AuditEntry.builder()
                .id(id)
                .entityType(entityType)
                .entityId(entityId)
                .partyId(partyId)
                .actionType(actionType)
                .outcome(outcome)
                .statusBefore(statusBefore)
                .statusAfter(statusAfter)
                .errorCategory(errorCategory)
                .errorType(errorType)
                .errorMessage(errorMessage)
                .correlationId(correlationId != null ? correlationId : "MISSING")
                .causationId(causationId)
                .traceId(traceId)
                .sourceSystem(sourceSystem)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationMs(Duration.between(startedAt, completedAt).toMillis())
                .details(details)
                .build();
            parent.persist(entry);
        }
    }
}
```

### 4.5 `KafkaCorrelationInterceptor.java` — capture à l’entrée Kafka

```java
package com.crok4it.audit.entry;

import com.crok4it.audit.MdcKeys;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pose le correlationId dans le MDC dès la réception du message Kafka.
 * Si le header est absent (premier message d'une chaîne), un UUID est généré.
 */
@Component
public class KafkaCorrelationInterceptor implements RecordInterceptor<String, String> {

    public static final String CORRELATION_HEADER = "x-business-correlation-id";

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        Header header = record.headers().lastHeader(CORRELATION_HEADER);
        String correlationId = header != null
            ? new String(header.value(), StandardCharsets.UTF_8)
            : UUID.randomUUID().toString();

        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        MDC.put(MdcKeys.SOURCE_SYSTEM, "KAFKA_INBOX");
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record,
                            Consumer<String, String> consumer) {
        MDC.remove(MdcKeys.CORRELATION_ID);
        MDC.remove(MdcKeys.SOURCE_SYSTEM);
    }
}
```

### 4.6 Configuration Kafka et async

```java
package com.crok4it.config;

import com.crok4it.audit.entry.KafkaCorrelationInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class IntegrationConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
            kafkaListenerContainerFactory(
                ConsumerFactory<String, String> consumerFactory,
                KafkaCorrelationInterceptor interceptor) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setRecordInterceptor(interceptor);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    /**
     * Executor utilisé par Modulith pour les @ApplicationModuleListener async.
     * ContextPropagatingTaskDecorator copie le MDC du thread appelant
     * → le correlationId est préservé entre publisher et listener async.
     */
    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
```

### 4.7 Customizer ECS pour exposer les champs MDC dans les logs JSON

```java
package com.crok4it.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.crok4it.audit.MdcKeys;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.boot.logging.structured.json.JsonWriter.Members;

import java.util.LinkedHashMap;
import java.util.Map;

public class EcsBusinessFieldsCustomizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(Members<ILoggingEvent> members) {
        members.add("business", event -> {
            Map<String, String> mdc = event.getMDCPropertyMap();
            if (!mdc.containsKey(MdcKeys.CORRELATION_ID)) return null;

            Map<String, String> map = new LinkedHashMap<>();
            map.put("correlation_id", mdc.get(MdcKeys.CORRELATION_ID));
            if (mdc.containsKey(MdcKeys.CAUSATION_ID))
                map.put("causation_id", mdc.get(MdcKeys.CAUSATION_ID));
            if (mdc.containsKey(MdcKeys.SOURCE_SYSTEM))
                map.put("source_system", mdc.get(MdcKeys.SOURCE_SYSTEM));
            return map;
        });
    }
}
```

À référencer dans `application.yaml` :

```yaml
logging:
  structured:
    json:
      customizer: com.crok4it.config.EcsBusinessFieldsCustomizer
```

-----

## 5. Étape 1 — Réception Kafka (Inbox)

### 5.1 `InboxRecord.java`

```java
package com.crok4it.review.inbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_record")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InboxRecord {

    @Id
    private UUID id;

    @Column(unique = true, nullable = false)
    private String messageId;

    @Column(nullable = false)
    private String reviewId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InboxStatus status;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant processedAt;
    private String errorMessage;

    public enum InboxStatus {
        PENDING, PROCESSED, FAILED
    }
}
```

### 5.2 `ReviewInboxConsumer.java`

```java
package com.crok4it.review.inbox;

import com.crok4it.audit.AuditTrail;
import com.crok4it.audit.MdcKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewInboxConsumer {

    private final InboxRecordRepository repository;
    private final AuditTrail auditTrail;

    /**
     * Réception Kafka.
     * - L'interceptor a déjà posé MDC[business.correlation_id]
     * - On persiste UNIQUEMENT l'InboxRecord (idempotence par messageId)
     * - Le métier sera fait plus tard par InboxProcessor
     *
     * Pourquoi : si on traite directement et que le métier échoue,
     * on doit re-consommer le message. Avec l'inbox, on commit Kafka
     * dès la persistance et le retry métier est local.
     */
    @KafkaListener(topics = "reviews.received", groupId = "review-service")
    public void consume(ConsumerRecord<String, String> record) {
        String messageId = record.key();
        String reviewId = extractReviewId(record.value());

        var trail = auditTrail.start("INBOX_RECORD", messageId, "RECEIVE_KAFKA", null);

        try {
            // Idempotence
            if (repository.existsByMessageId(messageId)) {
                log.info("Duplicate message {}, skipping", messageId);
                trail.degraded(null, "duplicate_message");
                return;
            }

            repository.save(InboxRecord.builder()
                .id(UUID.randomUUID())
                .messageId(messageId)
                .reviewId(reviewId)
                .payload(record.value())
                .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
                .status(InboxRecord.InboxStatus.PENDING)
                .receivedAt(Instant.now())
                .build());

            log.info("Inbox record persisted for review {}", reviewId);
            trail.success();

        } catch (Throwable t) {
            log.error("Failed to persist inbox record {}", messageId, t);
            trail.technicalFailure(t);
            throw t;  // Kafka redélivrera
        }
    }

    private String extractReviewId(String payload) {
        // Parsing JSON omis pour la lisibilité
        return "rev-extracted-from-payload";
    }
}
```

**Audit produit** :

```
INBOX_RECORD | msg-001 | RECEIVE_KAFKA | SUCCESS | source=KAFKA_INBOX | correlation_id=corr-001
```

-----

## 6. Étape 2 — Processing asynchrone

```java
package com.crok4it.review.inbox;

import com.crok4it.audit.MdcKeys;
import com.crok4it.review.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class InboxProcessor {

    private final InboxRecordRepository repository;
    private final ReviewService reviewService;

    /**
     * Toutes les 5 secondes, traite les InboxRecord PENDING.
     * Pour chaque record, restaure le MDC avant d'appeler le service métier
     * → le correlationId hérité du message Kafka est propagé partout.
     */
    @Scheduled(fixedDelay = 5000)
    public void processPending() {
        List<InboxRecord> pending = repository.findTop50ByStatusOrderByReceivedAtAsc(
            InboxRecord.InboxStatus.PENDING);
        if (pending.isEmpty()) return;

        for (InboxRecord record : pending) {
            // Restauration du contexte avant exécution
            MDC.put(MdcKeys.CORRELATION_ID, record.getCorrelationId());
            MDC.put(MdcKeys.SOURCE_SYSTEM, "KAFKA_INBOX");

            try {
                reviewService.validate(record.getReviewId());
                record.setStatus(InboxRecord.InboxStatus.PROCESSED);
                record.setProcessedAt(Instant.now());

            } catch (Throwable t) {
                log.error("Failed to process inbox record {}", record.getId(), t);
                record.setStatus(InboxRecord.InboxStatus.FAILED);
                record.setErrorMessage(t.getMessage());
            } finally {
                repository.save(record);
                MDC.remove(MdcKeys.CORRELATION_ID);
                MDC.remove(MdcKeys.SOURCE_SYSTEM);
            }
        }
    }
}
```

-----

## 7. Étape 3 — Service métier

### 7.1 Le domain event (PUR)

```java
package com.crok4it.review.events;

import java.time.Instant;

public record ReviewValidatedEvent(
    String reviewId,
    String customerId,
    String newStatus,
    Instant occurredAt
) {}
```

### 7.2 Le service métier

```java
package com.crok4it.review;

import com.crok4it.audit.AuditTrail;
import com.crok4it.review.events.ReviewValidatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private final ReviewRepository repository;
    private final ApplicationEventPublisher publisher;
    private final AuditTrail auditTrail;

    @Transactional
    public void validate(String reviewId) {
        var review = repository.findById(reviewId)
            .orElseThrow(() -> new ReviewNotFoundException(reviewId));

        var trail = auditTrail.start("REVIEW", reviewId, "VALIDATE",
                                     review.getStatus().name())
            .withPartyId(review.getCustomerId());

        try {
            review.validate();   // peut lever BusinessException
            repository.save(review);

            // Domain event pur, sans audit metadata
            publisher.publishEvent(new ReviewValidatedEvent(
                reviewId,
                review.getCustomerId(),
                review.getStatus().name(),
                Instant.now()
            ));

            log.info("Review {} validated", reviewId);
            trail.success(review.getStatus().name());

        } catch (BusinessException be) {
            log.warn("Review {} validation rejected: {}", reviewId, be.getMessage());
            trail.businessFailure(be);
            throw be;
        } catch (Throwable t) {
            log.error("Review {} validation failed (technical)", reviewId, t);
            trail.technicalFailure(t);
            throw t;
        }
    }
}
```

**Audit produit** (succès) :

```
REVIEW | rev-001 | VALIDATE | SUCCESS | DRAFT → VALIDATED | party=cust-42 | correlation_id=corr-001
```

**Audit produit** (échec métier) :

```
REVIEW | rev-001 | VALIDATE | BUSINESS_FAILURE | DRAFT → null | error=ReviewNotInDraftStatus
```

-----

## 8. Étape 4 — Outbox writer

### 8.1 `OutboxRecord.java`

```java
package com.crok4it.outbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_record")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxRecord {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String commandType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String commandPayload;

    @Column(nullable = false)
    private String targetSystem;

    private String aggregateType;
    private String aggregateId;
    private String partyId;

    @Column(nullable = false)
    private String correlationId;
    private String causedByEvent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    @Column(nullable = false)
    private int attemptCount;
    @Column(nullable = false)
    private int maxAttempts;

    private Integer lastHttpStatus;
    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;
    private Instant nextAttemptAt;
    private Instant completedAt;

    public enum SyncStatus {
        PENDING, IN_PROGRESS, SUCCESS, FAILED, DEAD_LETTER
    }

    public void markInProgress() {
        this.status = SyncStatus.IN_PROGRESS;
        this.attemptCount++;
    }

    public void markSuccess(Duration duration) {
        this.status = SyncStatus.SUCCESS;
        this.completedAt = Instant.now();
        this.lastError = null;
    }

    public void markDeadLetter(Throwable t) {
        this.status = SyncStatus.DEAD_LETTER;
        this.completedAt = Instant.now();
        this.lastError = t.getMessage();
    }

    public void scheduleRetry(Duration backoff, Throwable t) {
        this.status = SyncStatus.FAILED;
        this.nextAttemptAt = Instant.now().plus(backoff);
        this.lastError = t.getMessage();
    }
}
```

### 8.2 `OutboxWriterListener.java`

```java
package com.crok4it.outbox;

import com.crok4it.audit.AuditTrail;
import com.crok4it.audit.MdcKeys;
import com.crok4it.review.events.ReviewValidatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxWriterListener {

    private final OutboxRepository repository;
    private final AuditTrail auditTrail;
    private final ObjectMapper objectMapper;

    /**
     * Listener synchrone APRÈS COMMIT de la transaction métier.
     *
     * Le MDC est encore présent (même thread que le publisher).
     * Si on était en BEFORE_COMMIT et que le métier rollback, l'outbox
     * serait écrit alors que la review n'existe pas → incohérence.
     * AFTER_COMMIT garantit la cohérence.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewValidated(ReviewValidatedEvent event) throws Exception {
        MDC.put(MdcKeys.CAUSATION_ID, "ReviewValidatedEvent:" + event.reviewId());

        var trail = auditTrail.start("OUTBOX", event.reviewId(),
                                     "WRITE_OUTBOX", null)
            .withPartyId(event.customerId());

        try {
            // Conversion domain event → command Salesforce
            var command = new UpsertReviewSalesforceCommand(
                event.reviewId(), event.customerId(), event.newStatus()
            );

            repository.save(OutboxRecord.builder()
                .id(UUID.randomUUID())
                .commandType("UpsertReviewSalesforceCommand")
                .commandPayload(objectMapper.writeValueAsString(command))
                .targetSystem("SALESFORCE")
                .aggregateType("REVIEW")
                .aggregateId(event.reviewId())
                .partyId(event.customerId())
                .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
                .causedByEvent("ReviewValidatedEvent:" + event.reviewId())
                .status(OutboxRecord.SyncStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(5)
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now())
                .build());

            log.info("Outbox record created for review {}", event.reviewId());
            trail.success();

        } catch (Throwable t) {
            log.error("Failed to write outbox for review {}", event.reviewId(), t);
            trail.technicalFailure(t);
            throw t;
        } finally {
            MDC.remove(MdcKeys.CAUSATION_ID);
        }
    }

    record UpsertReviewSalesforceCommand(
        String reviewId,
        String customerId,
        String status
    ) {}
}
```

-----

## 9. Étape 5 — Listener async (chain)

### 9.1 Le domain event

```java
package com.crok4it.block.events;

import java.time.Instant;

public record BlockCreatedEvent(
    String blockId,
    String reviewId,
    String customerId,
    Instant occurredAt
) {}
```

### 9.2 Le listener

```java
package com.crok4it.block;

import com.crok4it.audit.AuditTrail;
import com.crok4it.audit.MdcKeys;
import com.crok4it.block.events.BlockCreatedEvent;
import com.crok4it.review.events.ReviewValidatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class BlockCreationListener {

    private final BlockRepository repository;
    private final ApplicationEventPublisher publisher;
    private final AuditTrail auditTrail;

    /**
     * @ApplicationModuleListener = ASYNC + transactionnel + persisté Modulith.
     *
     * Le MDC est propagé par ContextPropagatingTaskDecorator (cf. IntegrationConfig).
     * Donc le correlationId est disponible dès l'entrée du listener.
     */
    @ApplicationModuleListener
    public void onReviewValidated(ReviewValidatedEvent event) {
        MDC.put(MdcKeys.CAUSATION_ID, "ReviewValidatedEvent:" + event.reviewId());

        String blockId = "blk-" + UUID.randomUUID();
        var trail = auditTrail.start("BLOCK", blockId, "CREATE_BLOCK", null)
            .withPartyId(event.customerId());

        try {
            var block = new Block(blockId, event.reviewId(), event.customerId());
            block.activate();   // peut lever BusinessException
            repository.save(block);

            // Nouveau domain event publié → traité par d'autres listeners
            publisher.publishEvent(new BlockCreatedEvent(
                blockId, event.reviewId(), event.customerId(), Instant.now()
            ));

            log.info("Block {} created for review {}", blockId, event.reviewId());
            trail.success(block.getStatus().name());

        } catch (BusinessException be) {
            log.warn("Block creation rejected: {}", be.getMessage());
            trail.businessFailure(be);
            throw be;   // re-throw → Modulith garde l'event incomplet (pour retry futur)
        } catch (Throwable t) {
            log.error("Block creation failed (technical)", t);
            trail.technicalFailure(t);
            throw t;
        } finally {
            MDC.remove(MdcKeys.CAUSATION_ID);
        }
    }
}
```

-----

## 10. Étape 6 — Outbox dispatcher

```java
package com.crok4it.outbox;

import com.crok4it.audit.AuditTrail;
import com.crok4it.audit.MdcKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {

    private final OutboxRepository repository;
    private final SalesforceClient salesforceClient;
    private final AuditTrail auditTrail;

    @Scheduled(fixedDelay = 10000)
    public void dispatchReady() {
        List<OutboxRecord> ready = repository.findReadyForDispatch(
            OutboxRecord.SyncStatus.PENDING, Instant.now(), 50);
        for (OutboxRecord record : ready) {
            dispatch(record);
        }
    }

    @Transactional
    public void dispatch(OutboxRecord record) {
        // Restaure le contexte d'origine
        MDC.put(MdcKeys.CORRELATION_ID, record.getCorrelationId());
        MDC.put(MdcKeys.SOURCE_SYSTEM, "SCHEDULER");
        MDC.put(MdcKeys.CAUSATION_ID, record.getCausedByEvent());

        var trail = auditTrail.start("OUTBOX",
                                     record.getId().toString(),
                                     "SYNC_SALESFORCE",
                                     record.getStatus().name())
            .withPartyId(record.getPartyId())
            .withDetails("attempt=" + (record.getAttemptCount() + 1));

        Instant start = Instant.now();
        try {
            record.markInProgress();
            repository.save(record);

            salesforceClient.execute(record.getCommandPayload());

            record.markSuccess(Duration.between(start, Instant.now()));
            repository.save(record);

            log.info("Salesforce sync OK for outbox {} (attempt {})",
                record.getId(), record.getAttemptCount());
            trail.success(record.getStatus().name());

        } catch (SalesforceClientException ex) {
            handleSalesforceFailure(record, ex, trail);

        } catch (Throwable t) {
            record.markDeadLetter(t);
            repository.save(record);
            trail.technicalFailure(t);
            log.error("Salesforce sync failed permanently for outbox {}", record.getId(), t);

        } finally {
            MDC.remove(MdcKeys.CORRELATION_ID);
            MDC.remove(MdcKeys.SOURCE_SYSTEM);
            MDC.remove(MdcKeys.CAUSATION_ID);
        }
    }

    private void handleSalesforceFailure(OutboxRecord record,
                                          SalesforceClientException ex,
                                          AuditTrail.AuditContext trail) {
        if (ex.isPermanent() || record.getAttemptCount() >= record.getMaxAttempts()) {
            record.markDeadLetter(ex);
            trail.integrationFailure(ex);
            log.error("Salesforce sync DEAD_LETTER for outbox {}: {}",
                record.getId(), ex.getMessage());
        } else {
            Duration backoff = Duration.ofSeconds(
                30L * (long) Math.pow(2, record.getAttemptCount()));
            record.scheduleRetry(backoff, ex);
            trail.degraded(record.getStatus().name(),
                "will_retry_attempt=" + (record.getAttemptCount() + 1));
            log.warn("Salesforce sync failed (will retry) for outbox {}: {}",
                record.getId(), ex.getMessage());
        }
        repository.save(record);
    }
}
```

-----

## 11. Vue d’ensemble par requêtes SQL

### Vue 360° d’une review

```sql
SELECT 
    started_at, entity_type, entity_id, action_type, outcome,
    status_before, status_after, source_system, error_message
FROM audit_entry
WHERE correlation_id = (
    SELECT correlation_id FROM audit_entry 
    WHERE entity_type = 'REVIEW' AND entity_id = 'rev-001'
    LIMIT 1
)
ORDER BY started_at;
```

Résultat type :

```
10:00:00 | INBOX_RECORD | msg-001  | RECEIVE_KAFKA   | SUCCESS          | -      | -         | KAFKA_INBOX
10:00:05 | REVIEW       | rev-001  | VALIDATE        | SUCCESS          | DRAFT  | VALIDATED | KAFKA_INBOX
10:00:05 | OUTBOX       | rev-001  | WRITE_OUTBOX    | SUCCESS          | -      | -         | KAFKA_INBOX
10:00:06 | BLOCK        | blk-xxx  | CREATE_BLOCK    | SUCCESS          | -      | ACTIVE    | KAFKA_INBOX
10:00:15 | OUTBOX       | <id>     | SYNC_SALESFORCE | SUCCESS          | -      | -         | SCHEDULER
```

### Reviews validées mais pas synchronisées

```sql
SELECT v.entity_id, v.completed_at AS validated_at, s.outcome AS sync_outcome
FROM audit_entry v
LEFT JOIN audit_entry s
    ON s.correlation_id = v.correlation_id
   AND s.action_type = 'SYNC_SALESFORCE'
WHERE v.entity_type = 'REVIEW'
  AND v.action_type = 'VALIDATE'
  AND v.outcome = 'SUCCESS'
  AND (s.outcome IS NULL OR s.outcome != 'SUCCESS');
```

### Taux de succès par action sur 24h

```sql
SELECT 
    action_type,
    COUNT(*) FILTER (WHERE outcome = 'SUCCESS') as successes,
    COUNT(*) FILTER (WHERE outcome LIKE '%FAILURE%') as failures,
    ROUND(100.0 * COUNT(*) FILTER (WHERE outcome = 'SUCCESS') / COUNT(*), 2) as success_rate
FROM audit_entry
WHERE started_at > NOW() - INTERVAL '24 hours'
GROUP BY action_type
ORDER BY success_rate;
```

### Tous les échecs métier de la dernière heure

```sql
SELECT entity_type, entity_id, action_type, error_message, started_at
FROM audit_entry
WHERE outcome = 'BUSINESS_FAILURE'
  AND started_at > NOW() - INTERVAL '1 hour'
ORDER BY started_at DESC;
```

-----

## 12. Récap des composants

|Composant                          |Rôle                                           |Lignes approx|
|-----------------------------------|-----------------------------------------------|-------------|
|`MdcKeys`                          |Constantes MDC                                 |10           |
|`AuditEntry` (entité JPA)          |Modèle d’audit unifié                          |60           |
|`AuditEntryRepository`             |Persistance audit                              |5            |
|`AuditTrail` (façade)              |API simple : start/success/failure             |130          |
|`KafkaCorrelationInterceptor`      |Capture correlation à l’entrée Kafka           |35           |
|`IntegrationConfig`                |Kafka factory + ContextPropagatingTaskDecorator|35           |
|`EcsBusinessFieldsCustomizer`      |Expose MDC dans logs JSON                      |25           |
|`InboxRecord` (entité)             |Réception Kafka idempotente                    |35           |
|`ReviewInboxConsumer`              |Étape 1 — réception                            |50           |
|`InboxProcessor`                   |Étape 2 — processing async                     |35           |
|`ReviewService.validate()`         |Étape 3 — métier                               |35           |
|`ReviewValidatedEvent` (record pur)|Domain event                                   |8            |
|`OutboxRecord` (entité)            |État outbox + machine d’états                  |70           |
|`OutboxWriterListener`             |Étape 4 — domain event → command               |60           |
|`BlockCreationListener`            |Étape 5 — listener async                       |50           |
|`BlockCreatedEvent` (record pur)   |Domain event                                   |8            |
|`OutboxDispatcher`                 |Étape 6 — sync HTTP avec backoff               |80           |

**Total** : ~700 lignes pour un système d’audit complet sur 6 étapes.

-----

## Caractéristiques de la solution

- **Domain events purs** : aucun champ d’infrastructure
- **Audit explicite** : 3 lignes par méthode auditée, lisibles, testables
- **CorrelationId préservé** sur tout le flux via MDC + `ContextPropagatingTaskDecorator`
- **Survit au rollback** : audit en `Propagation.REQUIRES_NEW`
- **Symétrie totale** : SUCCESS / BUSINESS_FAILURE / TECHNICAL_FAILURE / DEGRADED dans la même table
- **Vue 360°** : une seule requête SQL par `correlation_id` ramène toute l’histoire
- **Zéro magie AOP** : pas de proxy, pas de wrapper Spring
- **Reporting trivial** : SQL standard sur `audit_entry`

-----

## Prochain document

Le **retry programmatique** des events Modulith incomplets, qui s’ajoutera à cette base sans la modifier :

- Capture du contexte à la publication (table `event_context`)
- Restauration du correlationId au replay (aspect ciblé)
- Job de retry contrôlé avec compteur de tentatives
- Quarantaine des poison pills
- Endpoints admin pour gestion manuelle
- Métriques Prometheus pour alerting
