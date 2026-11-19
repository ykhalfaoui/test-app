# Spécification d’implémentation — Flux complet avec propagation correlationId

**Stack** : Java 17 · Spring Boot 3.4 · Spring Modulith 1.4 · Kafka · PostgreSQL

**Objectif** : spécifier l’implémentation complète du flux `Kafka/HTTP → Inbox → Domain → Outbox → Dispatcher` en garantissant que le `correlationId` traverse toutes les étapes sans interruption.

-----

## Table des matières

1. [Vue d’ensemble du flux](#1-vue-densemble-du-flux)
1. [Découpage des modules Modulith](#2-découpage-des-modules-modulith)
1. [Propagation du correlationId](#3-propagation-du-correlationid)
1. [Schema de base de données](#4-schema-de-base-de-données)
1. [Contrats d’interface](#5-contrats-dinterface)
1. [Implémentation — Flux Kafka](#6-implémentation--flux-kafka)
1. [Implémentation — Flux HTTP](#7-implémentation--flux-http)
1. [Implémentation — Domain Events](#8-implémentation--domain-events)
1. [Implémentation — Outbox Pattern](#9-implémentation--outbox-pattern)
1. [Implémentation — Dispatcher](#10-implémentation--dispatcher)
1. [Implémentation — Audit](#11-implémentation--audit)
1. [Configuration](#12-configuration)
1. [Tests par composant](#13-tests-par-composant)

-----

## 1. Vue d’ensemble du flux

### 1.1 Flux A — Entrée Kafka

```
┌─────────────────────────────────────────────────────────────────────────┐
│  KAFKA (header x-correlation-id ou UUID généré si absent)               │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  [1] KafkaCorrelationInterceptor                                         │
│      → MDC.put(correlation_id)                                           │
│      → MDC.put(source_system = "KAFKA")                                  │
│                                                                          │
│  [2] BlockInboxConsumer (@KafkaListener)                                 │
│      → INSERT inbox_record(PENDING, correlation_id)                      │
│      → Audit : RECEIVE_KAFKA via @Auditable                              │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼ (toutes les 5s)
┌─────────────────────────────────────────────────────────────────────────┐
│  [3] InboxDispatcher (@Scheduled)                                        │
│      → SELECT inbox_record WHERE status=PENDING ORDER BY created_at      │
│      → MDC.put(correlation_id) restauré depuis inbox_record              │
│      → appelle BlockService.submit(command)                              │
│      → mark inbox_record PROCESSED ou FAILED                             │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  [4] BlockService.submit() @Transactional                                │
│      → block.submit()                                                    │
│      → INSERT block (status=SUBMITTED)                                   │
│      → publishEvent(BlockSubmittedEvent)  ◄── DomainEvent1               │
│                                                                          │
│      ┌─────────────────────────────────────────────────────────────┐    │
│      │  @TransactionalEventListener(BEFORE_COMMIT)                  │    │
│      │  OutboxWriterListener1                                       │    │
│      │  → INSERT outbox_record (BlockSubmittedCommand, PENDING)    │    │
│      └─────────────────────────────────────────────────────────────┘    │
│                                                                          │
│      COMMIT (block + outbox_record1 atomiques)                           │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼ (async, post-commit, Modulith)
┌─────────────────────────────────────────────────────────────────────────┐
│  [5] BlockCreationListener (@ApplicationModuleListener)                  │
│      MDC propagé par ContextPropagatingTaskDecorator                     │
│      → block.create()                                                    │
│      → UPDATE block (status=CREATED)                                     │
│      → publishEvent(BlockCreatedEvent)  ◄── DomainEvent2                 │
│                                                                          │
│      ┌─────────────────────────────────────────────────────────────┐    │
│      │  @TransactionalEventListener(BEFORE_COMMIT)                  │    │
│      │  OutboxWriterListener2                                       │    │
│      │  → INSERT outbox_record (BlockCreatedCommand, PENDING)      │    │
│      └─────────────────────────────────────────────────────────────┘    │
│                                                                          │
│      COMMIT (block update + outbox_record2 atomiques)                    │
└───────────────────────────┬─────────────────────────────────────────────┘
                            │
                            ▼ (toutes les 10s)
┌─────────────────────────────────────────────────────────────────────────┐
│  [6] OutboxDispatcher (@Scheduled)                                       │
│      → SELECT outbox_record WHERE status=PENDING ORDER BY created_at     │
│      → MDC.put(correlation_id) restauré depuis outbox_record             │
│      → pour chaque record : appel HTTP                                   │
│      → mark SUCCESS / FAILED / DEAD_LETTER                               │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Flux B — Entrée HTTP

```
┌─────────────────────────────────────────────────────────────────────────┐
│  HTTP Request (header X-Correlation-Id ou UUID généré si absent)         │
│       │                                                                  │
│       ▼                                                                  │
│  CorrelationIdFilter                                                     │
│  → MDC.put(correlation_id)                                               │
│  → MDC.put(source_system = "HTTP")                                       │
│       │                                                                  │
│       ▼                                                                  │
│  BlockController.submit()                                                │
│  → BlockService.submit(command)  ◄── même chemin qu'au-dessus           │
│                                       depuis publishEvent(...)           │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.3 Points clés de conception

|Décision         |Choix                                |Justification                                                              |
|-----------------|-------------------------------------|---------------------------------------------------------------------------|
|Outbox writer    |`BEFORE_COMMIT`                      |Atomicité avec le domain event : si la tx rollback, l’outbox ne s’écrit pas|
|Listener async   |`@ApplicationModuleListener`         |Replayable par Modulith, async par défaut                                  |
|correlationId    |MDC direct                           |Équipe junior, monolithe, scope interne                                    |
|Inbox dispatcher |`@Scheduled` + poll table            |Contrôle du retry, pas d’event intermédiaire                               |
|Outbox dispatcher|`@Scheduled` + ORDER BY created_at   |Garantit l’ordre FIFO des appels HTTP                                      |
|Audit            |`DomainEventInterface` + `@Auditable`|Un listener générique pour les success, AOP pour les failures              |

-----

## 2. Découpage des modules Modulith

```
com.crok4it
    ├── block/                          ← Module BLOCK
    │   ├── api/                        ← API publique du module
    │   │   ├── BlockService.java       ← service exposé inter-modules
    │   │   └── BlockCommand.java       ← command d'entrée
    │   ├── domain/
    │   │   ├── Block.java              ← agrégat
    │   │   └── BlockRepository.java
    │   ├── events/
    │   │   ├── BlockSubmittedEvent.java ← DomainEvent1 (implements DomainEventInterface)
    │   │   └── BlockCreatedEvent.java  ← DomainEvent2 (implements DomainEventInterface)
    │   ├── listeners/
    │   │   └── BlockCreationListener.java ← écoute BlockSubmittedEvent
    │   └── inbox/                      ← sous-package du module BLOCK
    │       ├── InboxRecord.java
    │       ├── InboxRecordRepository.java
    │       ├── BlockInboxConsumer.java ← @KafkaListener
    │       └── InboxDispatcher.java   ← @Scheduled
    │
    ├── outbox/                         ← Module OUTBOX (transversal)
    │   ├── OutboxRecord.java
    │   ├── OutboxRecordRepository.java
    │   ├── OutboxWriterListener.java   ← écoute DomainEventInterface via BEFORE_COMMIT
    │   └── OutboxDispatcher.java      ← @Scheduled
    │
    └── audit/                          ← Module AUDIT (transversal)
        ├── DomainEventInterface.java
        ├── MdcKeys.java
        ├── AuditEntry.java
        ├── AuditEntryRepository.java
        ├── AuditPersistenceService.java
        ├── GenericAuditListener.java
        ├── Auditable.java
        ├── AuditableAspect.java
        └── SpelEvaluator.java
```

**Règle Modulith** :

- Le module `outbox` ne dépend d’aucun module métier — il écoute `DomainEventInterface` (contrat commun dans `audit`)
- Le module `block` ne connaît pas le module `outbox` — il publie juste ses events
- Le module `audit` est transversal — il ne dépend d’aucun module métier

-----

## 3. Propagation du correlationId

### 3.1 Tableau de propagation complet

|Étape                                  |Thread            |Source du correlationId            |Mécanisme                                  |MDC[correlation_id]|
|---------------------------------------|------------------|-----------------------------------|-------------------------------------------|-------------------|
|Réception Kafka                        |consumer-thread-X |Header Kafka ou UUID               |`KafkaCorrelationInterceptor`              |✅ posé             |
|`BlockInboxConsumer`                   |consumer-thread-X |MDC hérité                         |—                                          |✅ présent          |
|Persiste `InboxRecord`                 |consumer-thread-X |MDC → sauvegardé en BD             |`correlationId` colonne                    |✅ présent          |
|Fin consumer                           |consumer-thread-X |—                                  |`KafkaCorrelationInterceptor.afterRecord()`|❌ nettoyé          |
|`InboxDispatcher` (@Scheduled)         |scheduler-thread-Y|BD (`inbox_record.correlation_id`) |`MDC.put` manuel                           |✅ restauré         |
|`BlockService.submit()`                |scheduler-thread-Y|MDC hérité                         |—                                          |✅ présent          |
|`OutboxWriterListener1` (BEFORE_COMMIT)|scheduler-thread-Y|MDC hérité (même thread)           |—                                          |✅ présent          |
|`BlockCreationListener` (async)        |async-thread-Z    |MDC copié                          |`ContextPropagatingTaskDecorator`          |✅ propagé          |
|`OutboxWriterListener2` (BEFORE_COMMIT)|async-thread-Z    |MDC hérité (même thread)           |—                                          |✅ présent          |
|`OutboxDispatcher` (@Scheduled)        |scheduler-thread-W|BD (`outbox_record.correlation_id`)|`MDC.put` manuel                           |✅ restauré         |
|`GenericAuditListener` (async)         |async-thread-V    |MDC copié                          |`ContextPropagatingTaskDecorator`          |✅ propagé          |
|Réception HTTP                         |http-thread-H     |Header HTTP ou UUID                |`CorrelationIdFilter`                      |✅ posé             |
|`BlockController`                      |http-thread-H     |MDC hérité                         |—                                          |✅ présent          |

### 3.2 Diagramme de threads

```
consumer-thread-X (Kafka)
  KafkaCorrelationInterceptor → MDC[corr=corr-001]
      BlockInboxConsumer.consume()
          INSERT inbox_record (corr-001 sauvegardé)
      afterRecord() → MDC nettoyé

scheduler-thread-Y (InboxDispatcher, toutes les 5s)
  MDC.put(corr=corr-001) depuis inbox_record
      BlockService.submit()
          publishEvent(BlockSubmittedEvent)
              OutboxWriterListener1 (BEFORE_COMMIT, même thread)
                  INSERT outbox_record1 (corr-001 sauvegardé)
          COMMIT

async-thread-Z (Modulith pool, ContextPropagatingTaskDecorator copie MDC)
  MDC[corr=corr-001] copié depuis scheduler-thread-Y
      BlockCreationListener.onBlockSubmitted()
          publishEvent(BlockCreatedEvent)
              OutboxWriterListener2 (BEFORE_COMMIT, même thread)
                  INSERT outbox_record2 (corr-001 sauvegardé)
          COMMIT

async-thread-V (Modulith pool, audit listener)
  MDC[corr=corr-001] copié
      GenericAuditListener.onDomainEvent(BlockSubmittedEvent)
          INSERT audit_entry SUCCESS

async-thread-V2 (Modulith pool, audit listener)
  MDC[corr=corr-001] copié
      GenericAuditListener.onDomainEvent(BlockCreatedEvent)
          INSERT audit_entry SUCCESS

scheduler-thread-W (OutboxDispatcher, toutes les 10s)
  MDC.put(corr=corr-001) depuis outbox_record
      httpClient.execute(command)
      mark SUCCESS
```

-----

## 4. Schema de base de données

```sql
-- ======================================================
-- Module BLOCK
-- ======================================================

CREATE TABLE block (
    id              VARCHAR(128) PRIMARY KEY,
    party_id        VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,   -- SUBMITTED, CREATED, FAILED
    sub_type        VARCHAR(64),
    payload         TEXT,
    correlation_id  VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_block_party ON block(party_id, status);
CREATE INDEX idx_block_correlation ON block(correlation_id);

-- ======================================================
-- Module INBOX
-- ======================================================

CREATE TABLE inbox_record (
    id              UUID         PRIMARY KEY,
    message_id      VARCHAR(128) NOT NULL UNIQUE,   -- idempotence
    topic           VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    correlation_id  VARCHAR(64)  NOT NULL,
    status          VARCHAR(32)  NOT NULL,   -- PENDING, PROCESSING, PROCESSED, FAILED
    error_message   TEXT,
    attempt_count   INT          NOT NULL DEFAULT 0,
    received_at     TIMESTAMP    NOT NULL,
    processed_at    TIMESTAMP,
    next_attempt_at TIMESTAMP
);

CREATE INDEX idx_inbox_dispatch ON inbox_record(status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_inbox_message ON inbox_record(message_id);

-- ======================================================
-- Module OUTBOX
-- ======================================================

CREATE TABLE outbox_record (
    id              UUID         PRIMARY KEY,
    correlation_id  VARCHAR(64)  NOT NULL,
    party_id        VARCHAR(64),
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(128) NOT NULL,
    command_type    VARCHAR(128) NOT NULL,
    command_payload TEXT         NOT NULL,
    target_system   VARCHAR(64)  NOT NULL,
    caused_by_event VARCHAR(128),
    status          VARCHAR(32)  NOT NULL,   -- PENDING, IN_PROGRESS, SUCCESS, FAILED, DEAD_LETTER
    attempt_count   INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    last_error      TEXT,
    created_at      TIMESTAMP    NOT NULL,
    next_attempt_at TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP
);

-- Index principal pour le dispatcher (FIFO)
CREATE INDEX idx_outbox_dispatch ON outbox_record(status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX idx_outbox_correlation ON outbox_record(correlation_id);
CREATE INDEX idx_outbox_aggregate ON outbox_record(aggregate_type, aggregate_id);

-- ======================================================
-- Module AUDIT
-- ======================================================

CREATE TABLE audit_entry (
    id              UUID         PRIMARY KEY,
    entity_type     VARCHAR(64)  NOT NULL,
    entity_id       VARCHAR(128) NOT NULL,
    entity_sub_type VARCHAR(64),
    party_id        VARCHAR(64),
    action_type     VARCHAR(64)  NOT NULL,
    outcome         VARCHAR(32)  NOT NULL,
    status_at_event VARCHAR(64),
    error_category  VARCHAR(32),
    error_type      VARCHAR(255),
    error_message   TEXT,
    correlation_id  VARCHAR(64),
    trace_id        VARCHAR(64),
    source_system   VARCHAR(32),
    started_at      TIMESTAMP    NOT NULL,
    completed_at    TIMESTAMP,
    duration_ms     BIGINT,
    details         TEXT
);

CREATE INDEX idx_audit_correlation ON audit_entry(correlation_id);
CREATE INDEX idx_audit_entity ON audit_entry(entity_type, entity_id);
CREATE INDEX idx_audit_party ON audit_entry(party_id, started_at);
CREATE INDEX idx_audit_outcome ON audit_entry(outcome, started_at)
    WHERE outcome != 'SUCCESS';
```

-----

## 5. Contrats d’interface

### 5.1 `DomainEventInterface`

```java
package com.crok4it.audit;

import java.time.Instant;

public interface DomainEventInterface {
    String getEntityType();
    String getEntityId();
    String getPartyId();
    String getActionType();
    String getStatus();
    default String getEntitySubType() { return null; }
    Instant getOccurredAt();
}
```

### 5.2 `OutboxableEvent`

Interface supplémentaire pour les events qui génèrent un outbox record. Permet à `OutboxWriterListener` de construire la command sans connaître le type exact de l’event.

```java
package com.crok4it.outbox;

/**
 * Contrat pour les domain events qui doivent générer un OutboxRecord.
 *
 * Un event qui implémente cette interface sera automatiquement intercepté
 * par OutboxWriterListener (BEFORE_COMMIT) et persisté en outbox.
 */
public interface OutboxableEvent {

    /** Type de la command à générer. Ex: "BlockSubmittedCommand" */
    String getCommandType();

    /** Cible HTTP du dispatcher. Ex: "SALESFORCE", "NOTIFICATION_API" */
    String getTargetSystem();

    /** Payload JSON de la command (sérialisé au moment de la publication) */
    String getCommandPayload();

    /** Aggregate auquel appartient cet event */
    String getAggregateType();

    /** ID de l'aggregate */
    String getAggregateId();

    /** Party ID (pour traçabilité dans outbox) */
    String getPartyId();

    /** Nombre max de tentatives. Default 5. */
    default int getMaxAttempts() { return 5; }
}
```

### 5.3 `BlockSubmittedEvent` (DomainEvent1)

```java
package com.crok4it.block.events;

import com.crok4it.audit.DomainEventInterface;
import com.crok4it.outbox.OutboxableEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import java.time.Instant;

/**
 * Publié par BlockService.submit() après submission réussie.
 *
 * Implémente DomainEventInterface → audité par GenericAuditListener
 * Implémente OutboxableEvent → un OutboxRecord est créé via BEFORE_COMMIT
 */
public record BlockSubmittedEvent(
    String blockId,
    String partyId,
    String subType,
    String payload,
    Instant occurredAt
) implements DomainEventInterface, OutboxableEvent {

    // === DomainEventInterface ===
    @Override public String getEntityType() { return "BLOCK"; }
    @Override public String getEntityId() { return blockId; }
    @Override public String getPartyId() { return partyId; }
    @Override public String getActionType() { return "SUBMITTED"; }
    @Override public String getStatus() { return "SUBMITTED"; }
    @Override public String getEntitySubType() { return subType; }
    @Override public Instant getOccurredAt() { return occurredAt; }

    // === OutboxableEvent ===
    @Override public String getCommandType() { return "BlockSubmittedCommand"; }
    @Override public String getTargetSystem() { return "SALESFORCE"; }
    @Override public String getAggregateType() { return "BLOCK"; }
    @Override public String getAggregateId() { return blockId; }

    @Override
    @SneakyThrows
    public String getCommandPayload() {
        // Sérialisation du payload pour l'outbox
        // En production : injecter ObjectMapper via un helper static
        return new ObjectMapper().writeValueAsString(
            new BlockSubmittedCommand(blockId, partyId, subType, payload)
        );
    }
}
```

### 5.4 `BlockCreatedEvent` (DomainEvent2)

```java
package com.crok4it.block.events;

import com.crok4it.audit.DomainEventInterface;
import com.crok4it.outbox.OutboxableEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import java.time.Instant;

/**
 * Publié par BlockCreationListener après création du block.
 */
public record BlockCreatedEvent(
    String blockId,
    String partyId,
    String subType,
    Instant occurredAt
) implements DomainEventInterface, OutboxableEvent {

    // === DomainEventInterface ===
    @Override public String getEntityType() { return "BLOCK"; }
    @Override public String getEntityId() { return blockId; }
    @Override public String getPartyId() { return partyId; }
    @Override public String getActionType() { return "CREATED"; }
    @Override public String getStatus() { return "CREATED"; }
    @Override public String getEntitySubType() { return subType; }
    @Override public Instant getOccurredAt() { return occurredAt; }

    // === OutboxableEvent ===
    @Override public String getCommandType() { return "BlockCreatedCommand"; }
    @Override public String getTargetSystem() { return "NOTIFICATION_API"; }
    @Override public String getAggregateType() { return "BLOCK"; }
    @Override public String getAggregateId() { return blockId; }

    @Override
    @SneakyThrows
    public String getCommandPayload() {
        return new ObjectMapper().writeValueAsString(
            new BlockCreatedCommand(blockId, partyId, subType)
        );
    }
}
```

-----

## 6. Implémentation — Flux Kafka

### 6.1 `KafkaCorrelationInterceptor`

```java
package com.crok4it.audit;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@Slf4j
public class KafkaCorrelationInterceptor implements RecordInterceptor<String, String> {

    public static final String KAFKA_CORRELATION_HEADER = "x-correlation-id";

    @Override
    public ConsumerRecord<String, String> intercept(
            ConsumerRecord<String, String> record,
            Consumer<String, String> consumer) {

        Header header = record.headers().lastHeader(KAFKA_CORRELATION_HEADER);
        String correlationId = (header != null)
            ? new String(header.value(), StandardCharsets.UTF_8)
            : UUID.randomUUID().toString();

        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        MDC.put(MdcKeys.SOURCE_SYSTEM, "KAFKA");

        log.debug("Kafka message received topic={} correlationId={}",
            record.topic(), correlationId);

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

### 6.2 `BlockInboxConsumer`

```java
package com.crok4it.block.inbox;

import com.crok4it.audit.Auditable;
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
public class BlockInboxConsumer {

    private final InboxRecordRepository repository;

    /**
     * Réception du message Kafka.
     * - L'interceptor a déjà posé MDC[correlation_id]
     * - Persiste UNIQUEMENT l'InboxRecord (idempotence par messageId)
     * - Le traitement métier est délégué à InboxDispatcher (@Scheduled)
     */
    @Auditable(
        entityType = "INBOX",
        action = "RECEIVE_KAFKA",
        entityId = "#record.key()"
    )
    @KafkaListener(
        topics = "${app.kafka.topics.block-submitted}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        String messageId = record.key();

        if (repository.existsByMessageId(messageId)) {
            log.info("Duplicate message {} ignored", messageId);
            return;
        }

        repository.save(InboxRecord.builder()
            .id(UUID.randomUUID())
            .messageId(messageId)
            .topic(record.topic())
            .payload(record.value())
            .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
            .status(InboxRecord.Status.PENDING)
            .attemptCount(0)
            .receivedAt(Instant.now())
            .nextAttemptAt(Instant.now())
            .build());

        log.info("InboxRecord persisted messageId={}", messageId);
    }
}
```

### 6.3 `InboxDispatcher`

```java
package com.crok4it.block.inbox;

import com.crok4it.audit.MdcKeys;
import com.crok4it.block.api.BlockService;
import com.crok4it.block.api.SubmitBlockCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class InboxDispatcher {

    private final InboxRecordRepository repository;
    private final BlockService blockService;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 5;

    /**
     * Toutes les 5 secondes :
     *  1. Charge les InboxRecords PENDING (ordre de réception)
     *  2. Pour chaque record : restaure le MDC, appelle le service métier
     *  3. Mark PROCESSED ou FAILED
     */
    @Scheduled(fixedDelayString = "${app.inbox.dispatch-delay-ms:5000}")
    public void dispatch() {
        List<InboxRecord> pending = repository.findPendingForDispatch(
            Instant.now(), BATCH_SIZE);

        if (pending.isEmpty()) return;

        log.debug("InboxDispatcher processing {} records", pending.size());

        for (InboxRecord record : pending) {
            processOne(record);
        }
    }

    private void processOne(InboxRecord record) {
        // Restauration du correlationId dans le MDC
        MDC.put(MdcKeys.CORRELATION_ID, record.getCorrelationId());
        MDC.put(MdcKeys.SOURCE_SYSTEM, "KAFKA");

        try {
            record.markProcessing();
            repository.save(record);

            SubmitBlockCommand command = objectMapper.readValue(
                record.getPayload(), SubmitBlockCommand.class);
            blockService.submit(command);

            record.markProcessed();
            log.info("InboxRecord {} processed successfully", record.getId());

        } catch (Throwable t) {
            log.error("InboxRecord {} failed (attempt {}/{})",
                record.getId(), record.getAttemptCount(), MAX_ATTEMPTS, t);

            if (record.getAttemptCount() >= MAX_ATTEMPTS) {
                record.markFailed(t.getMessage());
            } else {
                record.scheduleRetry(t.getMessage());
            }

        } finally {
            repository.save(record);
            MDC.remove(MdcKeys.CORRELATION_ID);
            MDC.remove(MdcKeys.SOURCE_SYSTEM);
        }
    }
}
```

### 6.4 `InboxRecord`

```java
package com.crok4it.block.inbox;

import jakarta.persistence.*;
import lombok.*;

import java.time.Duration;
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

    private String topic;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(nullable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant processedAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    public enum Status { PENDING, PROCESSING, PROCESSED, FAILED }

    public void markProcessing() {
        this.status = Status.PROCESSING;
        this.attemptCount++;
    }

    public void markProcessed() {
        this.status = Status.PROCESSED;
        this.processedAt = Instant.now();
        this.errorMessage = null;
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.errorMessage = error;
    }

    public void scheduleRetry(String error) {
        this.status = Status.PENDING;
        this.errorMessage = error;
        // Backoff exponentiel : 1min, 2min, 4min, 8min, 16min
        long backoffMinutes = (long) Math.pow(2, Math.min(attemptCount - 1, 4));
        this.nextAttemptAt = Instant.now().plus(Duration.ofMinutes(backoffMinutes));
    }
}
```

### 6.5 `InboxRecordRepository`

```java
package com.crok4it.block.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface InboxRecordRepository extends JpaRepository<InboxRecord, UUID> {

    boolean existsByMessageId(String messageId);

    @Query("""
        SELECT r FROM InboxRecord r
        WHERE r.status = 'PENDING'
          AND r.nextAttemptAt <= :now
        ORDER BY r.receivedAt ASC
        LIMIT :limit
        """)
    List<InboxRecord> findPendingForDispatch(
        @Param("now") Instant now,
        @Param("limit") int limit);
}
```

-----

## 7. Implémentation — Flux HTTP

### 7.1 `CorrelationIdFilter`

```java
package com.crok4it.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, correlationId);

        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        MDC.put(MdcKeys.SOURCE_SYSTEM, "HTTP");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.CORRELATION_ID);
            MDC.remove(MdcKeys.SOURCE_SYSTEM);
        }
    }
}
```

### 7.2 `BlockController`

```java
package com.crok4it.block.api;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/blocks")
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    /**
     * Entrée HTTP. Le correlationId est déjà dans le MDC via CorrelationIdFilter.
     * On délègue directement au service sans logique supplémentaire.
     */
    @PostMapping
    public ResponseEntity<Void> submit(@RequestBody SubmitBlockCommand command) {
        blockService.submit(command);
        return ResponseEntity.accepted().build();
    }
}
```

-----

## 8. Implémentation — Domain Events

### 8.1 `Block` (agrégat)

```java
package com.crok4it.block.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "block")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Block {

    @Id
    private String id;

    @Column(nullable = false)
    private String partyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private String subType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String correlationId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public enum Status { SUBMITTED, CREATED, FAILED }

    /**
     * Transition PENDING → SUBMITTED.
     * La validation métier se fait ici.
     */
    public void submit() {
        if (this.status != null) {
            throw new BlockAlreadySubmittedException(id);
        }
        this.status = Status.SUBMITTED;
        this.updatedAt = Instant.now();
    }

    /**
     * Transition SUBMITTED → CREATED.
     */
    public void create() {
        if (this.status != Status.SUBMITTED) {
            throw new InvalidBlockStateException(id, status, Status.SUBMITTED);
        }
        this.status = Status.CREATED;
        this.updatedAt = Instant.now();
    }
}
```

### 8.2 `BlockService`

```java
package com.crok4it.block.api;

import com.crok4it.audit.Auditable;
import com.crok4it.audit.MdcKeys;
import com.crok4it.block.domain.Block;
import com.crok4it.block.domain.BlockRepository;
import com.crok4it.block.events.BlockSubmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlockService {

    private final BlockRepository repository;
    private final ApplicationEventPublisher publisher;

    /**
     * Point d'entrée commun Kafka + HTTP.
     * - Capture FAILURE via @Auditable (SUCCESS géré par GenericAuditListener)
     * - Publie BlockSubmittedEvent (qui génère l'outbox via BEFORE_COMMIT)
     */
    @Auditable(
        entityType = "BLOCK",
        action = "SUBMIT",
        entityId = "#command.blockId()",
        partyId = "#command.partyId()"
    )
    @Transactional
    public void submit(SubmitBlockCommand command) {
        // Pose le partyId dans le MDC pour l'aspect @Auditable
        MDC.put(MdcKeys.PARTY_ID, command.partyId());
        try {
            var block = Block.builder()
                .id(command.blockId() != null
                    ? command.blockId()
                    : UUID.randomUUID().toString())
                .partyId(command.partyId())
                .subType(command.subType())
                .payload(command.payload())
                .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

            block.submit();
            repository.save(block);

            publisher.publishEvent(new BlockSubmittedEvent(
                block.getId(),
                block.getPartyId(),
                block.getSubType(),
                block.getPayload(),
                Instant.now()
            ));

            log.info("Block {} submitted partyId={}", block.getId(), block.getPartyId());

        } finally {
            MDC.remove(MdcKeys.PARTY_ID);
        }
    }
}
```

### 8.3 `BlockCreationListener`

```java
package com.crok4it.block.listeners;

import com.crok4it.audit.Auditable;
import com.crok4it.audit.MdcKeys;
import com.crok4it.block.domain.Block;
import com.crok4it.block.domain.BlockRepository;
import com.crok4it.block.events.BlockCreatedEvent;
import com.crok4it.block.events.BlockSubmittedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class BlockCreationListener {

    private final BlockRepository repository;
    private final ApplicationEventPublisher publisher;

    /**
     * Écoute BlockSubmittedEvent (async, post-commit).
     * MDC[correlation_id] est propagé par ContextPropagatingTaskDecorator.
     *
     * Publie BlockCreatedEvent :
     *  - GenericAuditListener l'auditeras en async (SUCCESS)
     *  - OutboxWriterListener le capturera en BEFORE_COMMIT → OutboxRecord2
     */
    @Auditable(
        entityType = "BLOCK",
        action = "CREATE",
        entityId = "#event.blockId()",
        partyId = "#event.partyId()"
    )
    @ApplicationModuleListener
    @Transactional
    public void onBlockSubmitted(BlockSubmittedEvent event) {
        MDC.put(MdcKeys.PARTY_ID, event.partyId());
        try {
            var block = repository.findById(event.blockId())
                .orElseThrow(() -> new BlockNotFoundException(event.blockId()));

            block.create();
            repository.save(block);

            publisher.publishEvent(new BlockCreatedEvent(
                block.getId(),
                block.getPartyId(),
                block.getSubType(),
                Instant.now()
            ));

            log.info("Block {} created", block.getId());

        } finally {
            MDC.remove(MdcKeys.PARTY_ID);
        }
    }
}
```

-----

## 9. Implémentation — Outbox Pattern

### 9.1 `OutboxRecord`

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
    private String correlationId;

    private String partyId;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String commandType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String commandPayload;

    @Column(nullable = false)
    private String targetSystem;

    private String causedByEvent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    private int attemptCount;
    private int maxAttempts;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant completedAt;

    public enum Status { PENDING, IN_PROGRESS, SUCCESS, FAILED, DEAD_LETTER }

    public void markInProgress() {
        this.status = Status.IN_PROGRESS;
        this.attemptCount++;
    }

    public void markSuccess() {
        this.status = Status.SUCCESS;
        this.completedAt = Instant.now();
        this.lastError = null;
    }

    public void markDeadLetter(String error) {
        this.status = Status.DEAD_LETTER;
        this.lastError = error;
        this.completedAt = Instant.now();
    }

    public void scheduleRetry(String error) {
        this.status = Status.FAILED;
        this.lastError = error;
        long backoffSeconds = 30L * (long) Math.pow(2, Math.min(attemptCount - 1, 4));
        this.nextAttemptAt = Instant.now().plus(Duration.ofSeconds(backoffSeconds));
    }

    public boolean hasExceededMaxAttempts() {
        return attemptCount >= maxAttempts;
    }
}
```

### 9.2 `OutboxRecordRepository`

```java
package com.crok4it.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRecordRepository extends JpaRepository<OutboxRecord, UUID> {

    /**
     * Sélection FIFO : PENDING + FAILED dont le nextAttemptAt est passé.
     * Order by created_at garantit l'ordre de traitement.
     */
    @Query("""
        SELECT r FROM OutboxRecord r
        WHERE r.status IN ('PENDING', 'FAILED')
          AND r.nextAttemptAt <= :now
        ORDER BY r.createdAt ASC
        LIMIT :limit
        """)
    List<OutboxRecord> findReadyForDispatch(
        @Param("now") Instant now,
        @Param("limit") int limit);
}
```

### 9.3 `OutboxWriterListener`

```java
package com.crok4it.outbox;

import com.crok4it.audit.MdcKeys;
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

    private final OutboxRecordRepository repository;

    /**
     * BEFORE_COMMIT : s'exécute dans la même transaction que le service qui a publié.
     * Garantit que l'OutboxRecord et le domain aggregate sont atomiques.
     *
     * Si le service rollback → l'OutboxRecord rollback aussi → cohérence garantie.
     *
     * Un seul listener générique pour tous les OutboxableEvent.
     * L'event porte toutes les informations nécessaires via l'interface.
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOutboxableEvent(OutboxableEvent event) {
        try {
            var record = OutboxRecord.builder()
                .id(UUID.randomUUID())
                .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
                .partyId(event.getPartyId())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .commandType(event.getCommandType())
                .commandPayload(event.getCommandPayload())
                .targetSystem(event.getTargetSystem())
                .causedByEvent(event.getClass().getSimpleName())
                .status(OutboxRecord.Status.PENDING)
                .attemptCount(0)
                .maxAttempts(event.getMaxAttempts())
                .createdAt(Instant.now())
                .nextAttemptAt(Instant.now())
                .build();

            repository.save(record);

            log.debug("OutboxRecord created commandType={} aggregateId={} correlationId={}",
                event.getCommandType(), event.getAggregateId(),
                MDC.get(MdcKeys.CORRELATION_ID));

        } catch (Throwable t) {
            // CRITIQUE : si on swallow ici, l'outbox ne sera pas écrit
            // On re-throw pour faire rollback la tx principale
            log.error("Failed to create OutboxRecord for {} aggregateId={}",
                event.getCommandType(), event.getAggregateId(), t);
            throw t;
        }
    }
}
```

-----

## 10. Implémentation — Dispatcher

### 10.1 `OutboxDispatcher`

```java
package com.crok4it.outbox;

import com.crok4it.audit.MdcKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {

    private final OutboxRecordRepository repository;
    private final HttpDispatcherRegistry dispatcherRegistry;

    private static final int BATCH_SIZE = 50;

    /**
     * Toutes les 10 secondes :
     *  1. Sélectionne les records PENDING/FAILED (FIFO par created_at)
     *  2. Pour chaque record :
     *     - Restaure le correlationId dans le MDC
     *     - Appel HTTP
     *     - Mark SUCCESS / scheduleRetry / DEAD_LETTER
     */
    @Scheduled(fixedDelayString = "${app.outbox.dispatch-delay-ms:10000}")
    public void dispatch() {
        List<OutboxRecord> ready = repository.findReadyForDispatch(
            Instant.now(), BATCH_SIZE);

        if (ready.isEmpty()) return;

        log.debug("OutboxDispatcher processing {} records", ready.size());

        for (OutboxRecord record : ready) {
            dispatchOne(record);
        }
    }

    @Transactional
    public void dispatchOne(OutboxRecord record) {
        // Restauration du correlationId dans le MDC
        MDC.put(MdcKeys.CORRELATION_ID, record.getCorrelationId());
        MDC.put(MdcKeys.SOURCE_SYSTEM, "OUTBOX_DISPATCHER");
        if (record.getPartyId() != null) {
            MDC.put(MdcKeys.PARTY_ID, record.getPartyId());
        }

        try {
            record.markInProgress();
            repository.save(record);

            // Délégation au dispatcher HTTP selon le targetSystem
            HttpDispatcher dispatcher = dispatcherRegistry
                .findDispatcher(record.getTargetSystem());
            dispatcher.dispatch(record);

            record.markSuccess();
            log.info("OutboxRecord {} dispatched OK commandType={} correlationId={}",
                record.getId(), record.getCommandType(),
                record.getCorrelationId());

        } catch (Throwable t) {
            log.error("OutboxRecord {} failed commandType={} attempt={}/{}",
                record.getId(), record.getCommandType(),
                record.getAttemptCount(), record.getMaxAttempts(), t);

            if (record.hasExceededMaxAttempts()) {
                record.markDeadLetter(t.getMessage());
            } else {
                record.scheduleRetry(t.getMessage());
            }

        } finally {
            repository.save(record);
            MDC.remove(MdcKeys.CORRELATION_ID);
            MDC.remove(MdcKeys.SOURCE_SYSTEM);
            MDC.remove(MdcKeys.PARTY_ID);
        }
    }
}
```

### 10.2 `HttpDispatcher` et `HttpDispatcherRegistry`

```java
package com.crok4it.outbox;

/**
 * Contrat pour les dispatchers HTTP par système cible.
 */
public interface HttpDispatcher {
    String getSupportedTargetSystem();
    void dispatch(OutboxRecord record);
}
```

```java
package com.crok4it.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class HttpDispatcherRegistry {

    private final Map<String, HttpDispatcher> byTargetSystem;

    public HttpDispatcherRegistry(List<HttpDispatcher> dispatchers) {
        this.byTargetSystem = dispatchers.stream()
            .collect(Collectors.toMap(
                HttpDispatcher::getSupportedTargetSystem,
                d -> d));
    }

    public HttpDispatcher findDispatcher(String targetSystem) {
        var dispatcher = byTargetSystem.get(targetSystem);
        if (dispatcher == null) {
            throw new NoDispatcherFoundException(targetSystem);
        }
        return dispatcher;
    }
}
```

**Exemple d’implémentation pour Salesforce** :

```java
package com.crok4it.outbox.dispatchers;

import com.crok4it.outbox.HttpDispatcher;
import com.crok4it.outbox.OutboxRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SalesforceHttpDispatcher implements HttpDispatcher {

    private final SalesforceClient salesforceClient;

    @Override
    public String getSupportedTargetSystem() { return "SALESFORCE"; }

    @Override
    public void dispatch(OutboxRecord record) {
        salesforceClient.upsert(record.getCommandPayload());
    }
}
```

-----

## 11. Implémentation — Audit

### 11.1 `GenericAuditListener`

```java
package com.crok4it.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class GenericAuditListener {

    private final AuditPersistenceService persistenceService;

    /**
     * Écoute TOUS les events qui implémentent DomainEventInterface.
     * Async + replayable via Modulith.
     * MDC propagé par ContextPropagatingTaskDecorator.
     */
    @ApplicationModuleListener
    public void onDomainEvent(DomainEventInterface event) {
        try {
            persistenceService.persist(AuditEntry.builder()
                .id(UUID.randomUUID())
                .entityType(event.getEntityType())
                .entityId(event.getEntityId())
                .entitySubType(event.getEntitySubType())
                .partyId(event.getPartyId())
                .actionType(event.getActionType())
                .outcome(AuditEntry.Outcome.SUCCESS)
                .statusAtEvent(event.getStatus())
                .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
                .traceId(MDC.get("traceId"))
                .sourceSystem(MDC.get(MdcKeys.SOURCE_SYSTEM))
                .startedAt(event.getOccurredAt())
                .completedAt(Instant.now())
                .durationMs(Duration.between(
                    event.getOccurredAt(), Instant.now()).toMillis())
                .build());

        } catch (Throwable t) {
            log.error("Failed to persist SUCCESS audit for {}/{} action={}",
                event.getEntityType(), event.getEntityId(),
                event.getActionType(), t);
            // SWALLOW : l'audit ne doit jamais faire échouer le flux métier
        }
    }
}
```

### 11.2 `AsyncConfig`

```java
package com.crok4it.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("modulith-async-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
```

-----

## 12. Configuration

### 12.1 `application.yaml`

```yaml
spring:
  application:
    name: block-service

  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/blockdb}
    username: ${DB_USER:block}
    password: ${DB_PASSWORD:block}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: true
      completion-mode: update
      republish-outstanding-events-on-restart: false

  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    consumer:
      group-id: block-service
      auto-offset-reset: earliest
      enable-auto-commit: false
    listener:
      ack-mode: MANUAL_IMMEDIATE
      observation-enabled: true

management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0

logging:
  structured:
    format:
      console: ecs
    json:
      customizer: com.crok4it.config.EcsBusinessFieldsCustomizer
  level:
    com.crok4it: INFO

app:
  kafka:
    topics:
      block-submitted: ${KAFKA_TOPIC_BLOCK_SUBMITTED:blocks.submitted}
  inbox:
    dispatch-delay-ms: ${INBOX_DISPATCH_DELAY_MS:5000}
  outbox:
    dispatch-delay-ms: ${OUTBOX_DISPATCH_DELAY_MS:10000}
```

### 12.2 `KafkaConfig`

```java
package com.crok4it.config;

import com.crok4it.audit.KafkaCorrelationInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

@Configuration
public class KafkaConfig {

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
}
```

-----

## 13. Tests par composant

### 13.1 Test — `KafkaCorrelationInterceptor`

```java
@ExtendWith(MockitoExtension.class)
class KafkaCorrelationInterceptorTest {

    private final KafkaCorrelationInterceptor interceptor =
        new KafkaCorrelationInterceptor();

    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void shouldCaptureCorrelationFromKafkaHeader() {
        var record = new ConsumerRecord<>("blocks.submitted", 0, 0L, "key", "{}");
        record.headers().add(new RecordHeader(
            KafkaCorrelationInterceptor.KAFKA_CORRELATION_HEADER,
            "kafka-corr-001".getBytes(UTF_8)));

        interceptor.intercept(record, null);

        assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isEqualTo("kafka-corr-001");
        assertThat(MDC.get(MdcKeys.SOURCE_SYSTEM)).isEqualTo("KAFKA");
    }

    @Test
    void shouldGenerateCorrelationIfHeaderAbsent() {
        var record = new ConsumerRecord<>("topic", 0, 0L, "key", "{}");
        interceptor.intercept(record, null);

        assertThat(MDC.get(MdcKeys.CORRELATION_ID))
            .isNotNull().matches("[a-f0-9-]{36}");
    }

    @Test
    void shouldCleanupAfterRecord() {
        var record = new ConsumerRecord<>("topic", 0, 0L, "key", "{}");
        interceptor.intercept(record, null);
        interceptor.afterRecord(record, null);

        assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isNull();
        assertThat(MDC.get(MdcKeys.SOURCE_SYSTEM)).isNull();
    }
}
```

### 13.2 Test — `BlockInboxConsumer`

```java
@ExtendWith(MockitoExtension.class)
class BlockInboxConsumerTest {

    @Mock InboxRecordRepository repository;
    @InjectMocks BlockInboxConsumer consumer;

    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void shouldPersistInboxRecordWithCorrelationId() {
        MDC.put(MdcKeys.CORRELATION_ID, "test-corr");
        var record = new ConsumerRecord<>("topic", 0, 0L, "msg-001", "{}");
        when(repository.existsByMessageId("msg-001")).thenReturn(false);

        consumer.consume(record);

        var captor = ArgumentCaptor.forClass(InboxRecord.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("test-corr");
        assertThat(captor.getValue().getMessageId()).isEqualTo("msg-001");
        assertThat(captor.getValue().getStatus()).isEqualTo(InboxRecord.Status.PENDING);
    }

    @Test
    void shouldIgnoreDuplicateMessage() {
        var record = new ConsumerRecord<>("topic", 0, 0L, "msg-dup", "{}");
        when(repository.existsByMessageId("msg-dup")).thenReturn(true);

        consumer.consume(record);

        verify(repository, never()).save(any());
    }
}
```

### 13.3 Test — `InboxDispatcher`

```java
@ExtendWith(MockitoExtension.class)
class InboxDispatcherTest {

    @Mock InboxRecordRepository repository;
    @Mock BlockService blockService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks InboxDispatcher dispatcher;

    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void shouldRestoreCorrelationIdFromInboxRecord() throws Exception {
        var record = pendingRecord("corr-restored");
        when(repository.findPendingForDispatch(any(), anyInt()))
            .thenReturn(List.of(record));
        when(objectMapper.readValue(anyString(), eq(SubmitBlockCommand.class)))
            .thenReturn(mock(SubmitBlockCommand.class));

        // Capture le MDC au moment de l'appel au service
        doAnswer(inv -> {
            assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isEqualTo("corr-restored");
            return null;
        }).when(blockService).submit(any());

        dispatcher.dispatch();

        // MDC nettoyé après
        assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    void shouldMarkProcessedOnSuccess() throws Exception {
        var record = pendingRecord("corr-001");
        when(repository.findPendingForDispatch(any(), anyInt()))
            .thenReturn(List.of(record));
        when(objectMapper.readValue(anyString(), eq(SubmitBlockCommand.class)))
            .thenReturn(mock(SubmitBlockCommand.class));

        dispatcher.dispatch();

        assertThat(record.getStatus()).isEqualTo(InboxRecord.Status.PROCESSED);
    }

    @Test
    void shouldScheduleRetryOnFailure() throws Exception {
        var record = pendingRecord("corr-001");
        when(repository.findPendingForDispatch(any(), anyInt()))
            .thenReturn(List.of(record));
        when(objectMapper.readValue(anyString(), eq(SubmitBlockCommand.class)))
            .thenReturn(mock(SubmitBlockCommand.class));
        doThrow(new RuntimeException("DB down")).when(blockService).submit(any());

        dispatcher.dispatch();

        assertThat(record.getStatus()).isEqualTo(InboxRecord.Status.PENDING);
        assertThat(record.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isNull();
    }

    private InboxRecord pendingRecord(String correlationId) {
        return InboxRecord.builder()
            .id(UUID.randomUUID())
            .messageId("msg-001")
            .payload("{}")
            .correlationId(correlationId)
            .status(InboxRecord.Status.PENDING)
            .attemptCount(0)
            .receivedAt(Instant.now())
            .nextAttemptAt(Instant.now())
            .build();
    }
}
```

### 13.4 Test — `BlockService`

```java
@ExtendWith(MockitoExtension.class)
class BlockServiceTest {

    @Mock BlockRepository repository;
    @Mock ApplicationEventPublisher publisher;
    @InjectMocks BlockService service;

    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void shouldPublishBlockSubmittedEvent() {
        MDC.put(MdcKeys.CORRELATION_ID, "corr-001");
        var command = new SubmitBlockCommand(null, "cust-42", "PREMIUM", "{}");

        service.submit(command);

        var captor = ArgumentCaptor.forClass(BlockSubmittedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().partyId()).isEqualTo("cust-42");
        assertThat(captor.getValue().getEntityType()).isEqualTo("BLOCK");
    }

    @Test
    void shouldSaveBlockWithCorrelationId() {
        MDC.put(MdcKeys.CORRELATION_ID, "corr-001");
        var command = new SubmitBlockCommand(null, "cust-42", "PREMIUM", "{}");

        service.submit(command);

        var captor = ArgumentCaptor.forClass(Block.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo("corr-001");
        assertThat(captor.getValue().getStatus()).isEqualTo(Block.Status.SUBMITTED);
    }

    @Test
    void shouldCleanupPartyIdMdcEvenOnException() {
        MDC.put(MdcKeys.CORRELATION_ID, "corr-001");
        var command = new SubmitBlockCommand(null, "cust-42", null, null);
        doThrow(new RuntimeException("DB")).when(repository).save(any());

        assertThatThrownBy(() -> service.submit(command));

        assertThat(MDC.get(MdcKeys.PARTY_ID)).isNull();
    }
}
```

### 13.5 Test — `OutboxWriterListener`

```java
@ExtendWith(MockitoExtension.class)
class OutboxWriterListenerTest {

    @Mock OutboxRecordRepository repository;
    @InjectMocks OutboxWriterListener listener;

    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void shouldCreateOutboxRecordWithCorrelationId() {
        MDC.put(MdcKeys.CORRELATION_ID, "corr-outbox");

        var event = new BlockSubmittedEvent(
            "blk-001", "cust-42", "PREMIUM", "{}", Instant.now());

        listener.onOutboxableEvent(event);

        var captor = ArgumentCaptor.forClass(OutboxRecord.class);
        verify(repository).save(captor.capture());
        var record = captor.getValue();

        assertThat(record.getCorrelationId()).isEqualTo("corr-outbox");
        assertThat(record.getAggregateId()).isEqualTo("blk-001");
        assertThat(record.getCommandType()).isEqualTo("BlockSubmittedCommand");
        assertThat(record.getTargetSystem()).isEqualTo("SALESFORCE");
        assertThat(record.getStatus()).isEqualTo(OutboxRecord.Status.PENDING);
    }

    @Test
    void shouldRethrowOnPersistenceError() {
        doThrow(new RuntimeException("DB down"))
            .when(repository).save(any());

        var event = new BlockSubmittedEvent(
            "blk-001", "cust-42", "PREMIUM", "{}", Instant.now());

        assertThatThrownBy(() -> listener.onOutboxableEvent(event))
            .isInstanceOf(RuntimeException.class);
    }
}
```

### 13.6 Test — `OutboxDispatcher`

```java
@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {

    @Mock OutboxRecordRepository repository;
    @Mock HttpDispatcherRegistry dispatcherRegistry;
    @Mock HttpDispatcher httpDispatcher;
    @InjectMocks OutboxDispatcher dispatcher;

    @AfterEach void clearMdc() { MDC.clear(); }

    @BeforeEach
    void setUp() {
        when(dispatcherRegistry.findDispatcher(any())).thenReturn(httpDispatcher);
    }

    @Test
    void shouldRestoreCorrelationIdFromOutboxRecord() {
        var record = pendingRecord("corr-outbox-restored");
        when(repository.findReadyForDispatch(any(), anyInt()))
            .thenReturn(List.of(record));

        doAnswer(inv -> {
            assertThat(MDC.get(MdcKeys.CORRELATION_ID))
                .isEqualTo("corr-outbox-restored");
            assertThat(MDC.get(MdcKeys.SOURCE_SYSTEM)).isEqualTo("OUTBOX_DISPATCHER");
            return null;
        }).when(httpDispatcher).dispatch(any());

        dispatcher.dispatch();

        assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    void shouldMarkSuccessAfterDispatch() {
        var record = pendingRecord("corr-001");
        when(repository.findReadyForDispatch(any(), anyInt()))
            .thenReturn(List.of(record));

        dispatcher.dispatch();

        assertThat(record.getStatus()).isEqualTo(OutboxRecord.Status.SUCCESS);
    }

    @Test
    void shouldScheduleRetryOnTransientFailure() {
        var record = pendingRecord("corr-001");
        when(repository.findReadyForDispatch(any(), anyInt()))
            .thenReturn(List.of(record));
        doThrow(new RuntimeException("HTTP 503")).when(httpDispatcher).dispatch(any());

        dispatcher.dispatch();

        assertThat(record.getStatus()).isEqualTo(OutboxRecord.Status.FAILED);
        assertThat(record.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(MDC.get(MdcKeys.CORRELATION_ID)).isNull();
    }

    @Test
    void shouldMarkDeadLetterAfterMaxAttempts() {
        var record = pendingRecord("corr-001");
        record.setAttemptCount(5);
        when(repository.findReadyForDispatch(any(), anyInt()))
            .thenReturn(List.of(record));
        doThrow(new RuntimeException("final failure")).when(httpDispatcher).dispatch(any());

        dispatcher.dispatch();

        assertThat(record.getStatus()).isEqualTo(OutboxRecord.Status.DEAD_LETTER);
    }

    private OutboxRecord pendingRecord(String correlationId) {
        return OutboxRecord.builder()
            .id(UUID.randomUUID())
            .correlationId(correlationId)
            .partyId("cust-42")
            .aggregateType("BLOCK")
            .aggregateId("blk-001")
            .commandType("BlockSubmittedCommand")
            .commandPayload("{}")
            .targetSystem("SALESFORCE")
            .status(OutboxRecord.Status.PENDING)
            .attemptCount(0)
            .maxAttempts(5)
            .createdAt(Instant.now())
            .nextAttemptAt(Instant.now())
            .build();
    }
}
```

### 13.7 Test d’intégration Modulith — propagation cross-module

```java
@SpringBootTest
@ApplicationModuleTest(mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES)
class BlockModuleIntegrationTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired AuditEntryRepository auditRepository;
    @Autowired OutboxRecordRepository outboxRepository;

    @BeforeEach void setup() {
        auditRepository.deleteAll();
        outboxRepository.deleteAll();
        MDC.clear();
    }

    @AfterEach void clearMdc() { MDC.clear(); }

    @Test
    void shouldPropagateCorrelationIdThroughFullFlow() throws Exception {
        MDC.put(MdcKeys.CORRELATION_ID, "integration-corr-001");
        MDC.put(MdcKeys.SOURCE_SYSTEM, "TEST");

        // Publication de BlockSubmittedEvent simule la fin de BlockService.submit()
        publisher.publishEvent(new BlockSubmittedEvent(
            "blk-int-001", "cust-42", "PREMIUM", "{}", Instant.now()
        ));

        // OutboxWriterListener1 doit avoir créé un OutboxRecord (BEFORE_COMMIT)
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var outboxRecords = outboxRepository.findAll();
            assertThat(outboxRecords)
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    assertThat(r.getCorrelationId()).isEqualTo("integration-corr-001");
                    assertThat(r.getAggregateId()).isEqualTo("blk-int-001");
                    assertThat(r.getStatus()).isEqualTo(OutboxRecord.Status.PENDING);
                });
        });

        // GenericAuditListener doit avoir audité BlockSubmittedEvent (async)
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var auditEntries = auditRepository
                .findByCorrelationIdOrderByStartedAtAsc("integration-corr-001");
            assertThat(auditEntries)
                .anySatisfy(e -> {
                    assertThat(e.getActionType()).isEqualTo("SUBMITTED");
                    assertThat(e.getOutcome()).isEqualTo(AuditEntry.Outcome.SUCCESS);
                    assertThat(e.getCorrelationId()).isEqualTo("integration-corr-001");
                });
        });
    }
}
```

-----

## Récap des composants

|Composant                                       |Module         |Rôle                                   |
|------------------------------------------------|---------------|---------------------------------------|
|`KafkaCorrelationInterceptor`                   |audit          |Capture correlation depuis header Kafka|
|`CorrelationIdFilter`                           |audit          |Capture correlation depuis header HTTP |
|`MdcKeys`                                       |audit          |Constantes MDC                         |
|`DomainEventInterface`                          |audit          |Contrat audit pour les events          |
|`AuditEntry`, `AuditEntryRepository`            |audit          |Persistance audit                      |
|`AuditPersistenceService`                       |audit          |Wrapper REQUIRES_NEW                   |
|`GenericAuditListener`                          |audit          |Audit SUCCESS async sur tous les events|
|`@Auditable`, `AuditableAspect`, `SpelEvaluator`|audit          |Audit FAILURE via AOP                  |
|`AsyncConfig`                                   |config         |ContextPropagatingTaskDecorator        |
|`KafkaConfig`                                   |config         |Injection de l’interceptor             |
|`EcsBusinessFieldsCustomizer`                   |config         |Logs JSON ECS enrichis                 |
|`InboxRecord`, `InboxRecordRepository`          |block/inbox    |Table inbox Kafka                      |
|`BlockInboxConsumer`                            |block/inbox    |Reception Kafka idempotente            |
|`InboxDispatcher`                               |block/inbox    |Poll + dispatch inbox FIFO             |
|`Block`, `BlockRepository`                      |block/domain   |Agrégat                                |
|`BlockSubmittedEvent`                           |block/events   |DomainEvent1 (auditable + outboxable)  |
|`BlockCreatedEvent`                             |block/events   |DomainEvent2 (auditable + outboxable)  |
|`BlockService`                                  |block/api      |Entrée commune Kafka + HTTP            |
|`BlockController`                               |block/api      |Entrée HTTP                            |
|`BlockCreationListener`                         |block/listeners|Ecoute BlockSubmittedEvent async       |
|`OutboxableEvent`                               |outbox         |Contrat outbox pour les events         |
|`OutboxRecord`, `OutboxRecordRepository`        |outbox         |Table outbox                           |
|`OutboxWriterListener`                          |outbox         |Ecoute OutboxableEvent BEFORE_COMMIT   |
|`OutboxDispatcher`                              |outbox         |Poll + dispatch HTTP FIFO              |
|`HttpDispatcher`, `HttpDispatcherRegistry`      |outbox         |Dispatchers HTTP par target system     |

**Total : 29 composants**, propagation `correlationId` garantie sur tout le flux.
