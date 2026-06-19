# Propagation MDC du contexte d’audit — Complément à la solution AOP

**Prérequis** : ce document complète `audit-async-with-aop.md`. Il détaille comment le contexte d’audit (`correlationId`, `partyId`, `sourceSystem`) est propagé via le MDC à travers toutes les étapes du flux.

**Objectif** : garantir que **chaque ligne `audit_entry`** (qu’elle vienne du `GenericAuditListener` ou de l’`AuditableAspect`) ait le bon `correlationId` et le bon `partyId`, **sans que le développeur métier n’ait à coder quoi que ce soit**.

-----

## Table des matières

1. [Vue d’ensemble de la propagation](#1-vue-densemble-de-la-propagation)
1. [Les 3 dimensions du contexte d’audit](#2-les-3-dimensions-du-contexte-daudit)
1. [Étape par étape — où le MDC est posé/restauré](#3-étape-par-étape--où-le-mdc-est-posérestauré)
1. [Points d’entrée — capture du contexte](#4-points-dentrée--capture-du-contexte)
1. [Propagation entre threads](#5-propagation-entre-threads)
1. [Lecture du MDC dans l’aspect et le listener](#6-lecture-du-mdc-dans-laspect-et-le-listener)
1. [Le piège du `partyId` et sa solution](#7-le-piège-du-partyid-et-sa-solution)
1. [Customizer ECS pour exposer le MDC dans les logs JSON](#8-customizer-ecs-pour-exposer-le-mdc-dans-les-logs-json)
1. [Tests de propagation](#9-tests-de-propagation)
1. [Récap complet](#10-récap-complet)

-----

## 1. Vue d’ensemble de la propagation

```
┌─────────────────────────────────────────────────────────────────────┐
│                  POINT D'ENTRÉE (Filter ou Interceptor)              │
│                                                                      │
│  HTTP Request                Kafka message                           │
│       │                            │                                 │
│       ▼                            ▼                                 │
│  CorrelationIdFilter        KafkaCorrelationInterceptor              │
│       │                            │                                 │
│       └──────────┬─────────────────┘                                 │
│                  ▼                                                   │
│       MDC.put(correlation_id, source_system)                         │
└─────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  COUCHE SERVICE (synchrone)                          │
│                                                                      │
│  ReviewController.validate()                                         │
│       │  MDC contient {correlation_id, source_system}                │
│       ▼                                                              │
│  ReviewService.validate(reviewId)                                    │
│       │                                                              │
│       │  PartyContextEnricher (interceptor) charge partyId          │
│       │  → MDC.put(party_id, entity_id)                              │
│       │                                                              │
│       ▼  MDC contient {correlation_id, source_system,                │
│       │              party_id, entity_id}                            │
│       │                                                              │
│       ├─► review.validate()  (peut throw)                            │
│       ├─► repository.save(review)                                    │
│       ├─► publisher.publishEvent(ReviewValidatedEvent)               │
│       └─► COMMIT                                                     │
│                                                                      │
│  Si EXCEPTION :                                                      │
│       AuditableAspect lit le MDC                                     │
│       → INSERT audit_entry FAILURE avec correlation_id + party_id    │
└─────────────────────────────────────────────────────────────────────┘
                  │
                  ▼ (commit OK → events dispatchés)
┌─────────────────────────────────────────────────────────────────────┐
│                  COUCHE LISTENER (asynchrone)                        │
│                                                                      │
│  Modulith dispatch async                                             │
│       │                                                              │
│       │  ContextPropagatingTaskDecorator copie le MDC du publisher  │
│       │                                                              │
│       ▼  MDC contient {correlation_id, source_system,                │
│       │              party_id, entity_id}                            │
│       │                                                              │
│  GenericAuditListener.onDomainEvent(event)                           │
│       → INSERT audit_entry SUCCESS avec correlation_id + party_id    │
└─────────────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                  HANDLER OUTBOX (async, scheduler)                   │
│                                                                      │
│  OutboxDispatcher.dispatch(record)                                   │
│       │  MDC vide (nouveau thread du scheduler)                      │
│       │                                                              │
│       │  Restaure MDC depuis OutboxRecord :                          │
│       │  → MDC.put(correlation_id, party_id, source_system=SCHEDULER)│
│       │                                                              │
│       ▼                                                              │
│  handler.handle(command)  ← @Auditable mode=BOTH                     │
│       │                                                              │
│       └─► AuditableAspect lit le MDC                                 │
│           → INSERT audit_entry SUCCESS ou FAILURE                    │
└─────────────────────────────────────────────────────────────────────┘
```

-----

## 2. Les 3 dimensions du contexte d’audit

Le MDC porte trois familles d’informations distinctes :

|Dimension             |Clé MDC                                  |Source                          |Cycle de vie                                          |
|----------------------|-----------------------------------------|--------------------------------|------------------------------------------------------|
|**Trace technique**   |`traceId`, `spanId`                      |Micrometer auto                 |Une exécution technique                               |
|**Corrélation métier**|`business.correlation_id`                |Filter HTTP / Kafka interceptor |Une transaction métier (peut couvrir plusieurs traces)|
|**Contexte d’entité** |`business.party_id`, `business.entity_id`|Service métier (chargement repo)|La durée d’une opération sur une entité               |

**Ce qu’on ajoute par rapport au document original** : la **dimension contexte d’entité** (`party_id`, `entity_id`). C’est ce qui permet à l’aspect AOP de poser le `partyId` même en cas d’échec où le SpEL `#review.customerId` ne peut pas l’extraire.

### 2.1 Constantes MDC enrichies

```java
package com.crok4it.audit;

public final class MdcKeys {
    private MdcKeys() {}

    // === Corrélation métier (capture aux points d'entrée) ===
    public static final String CORRELATION_ID = "business.correlation_id";
    public static final String SOURCE_SYSTEM = "business.source_system";

    // === Contexte d'entité (posé pendant l'exécution du service) ===
    public static final String PARTY_ID = "business.party_id";
    public static final String ENTITY_ID = "business.entity_id";
    public static final String ENTITY_TYPE = "business.entity_type";
}
```

-----

## 3. Étape par étape — où le MDC est posé/restauré

### 3.1 Tableau récapitulatif

|Étape                                |Composant                                  |Action MDC                                   |Clés concernées                              |
|-------------------------------------|-------------------------------------------|---------------------------------------------|---------------------------------------------|
|1. Réception HTTP                    |`CorrelationIdFilter`                      |`put` puis `remove` (try/finally)            |`correlation_id`, `source_system`            |
|2. Réception Kafka                   |`KafkaCorrelationInterceptor`              |`put` (intercept) puis `remove` (afterRecord)|`correlation_id`, `source_system`            |
|3. Entrée service (chargement entité)|Service lui-même OU `AuditContextEnricher` |`put` puis `remove` (try/finally)            |`party_id`, `entity_id`, `entity_type`       |
|4. Publication d’event               |Pas d’action — le MDC est juste lu         |—                                            |(lu par l’aspect ou propagé)                 |
|5. Listener async (Modulith)         |`ContextPropagatingTaskDecorator`          |Copie auto du MDC du publisher               |toutes                                       |
|6. Sortie listener async             |Auto par `ContextPropagatingTaskDecorator` |Cleanup auto                                 |toutes                                       |
|7. Job scheduler (outbox)            |Dispatcher restaure depuis l’`OutboxRecord`|`put` puis `remove` (try/finally)            |`correlation_id`, `party_id`, `source_system`|
|8. Aspect AOP `@Auditable`           |Lit le MDC (pas de pose)                   |`get`                                        |toutes                                       |
|9. Listener générique d’audit        |Lit le MDC (pas de pose)                   |`get`                                        |toutes                                       |

### 3.2 Le flux complet visualisé

```
T+0    HTTP arrive
       CorrelationIdFilter pose correlation_id + source_system
       │
T+1    Controller appelle service
       │  MDC = {correlation_id, source_system}
       │
T+2    Service charge l'entité Review
       AuditContextEnricher (intercepteur de service) pose party_id + entity_id
       │  MDC = {correlation_id, source_system, party_id, entity_id, entity_type}
       │
T+3    review.validate() → throw BusinessException
       │
T+3    AuditableAspect intercepte
       │  Lit le MDC → INSERT audit_entry FAILURE avec TOUS les champs
       │  (correlation_id, party_id, entity_id depuis MDC ; entityType, action depuis annotation)
       │
T+3    AuditContextEnricher cleanup (finally) party_id, entity_id, entity_type
       │
T+3    Filter cleanup (finally) correlation_id, source_system
       │
T+3    Exception remonte au controller HTTP → 400
```

Pour un cas SUCCESS :

```
T+0    Idem (entrée HTTP, MDC posé)
T+2    Service charge l'entité
       MDC = {correlation_id, source_system, party_id, entity_id, entity_type}
       │
T+3    review.validate() OK
T+4    publisher.publishEvent(ReviewValidatedEvent)
       │  L'event est mis en attente, sera dispatché après commit
T+5    COMMIT
       │
T+5    Modulith dispatch les events post-commit
       │  ContextPropagatingTaskDecorator COPIE le MDC actuel
       │  vers le thread du listener async
       │
T+5    GenericAuditListener.onDomainEvent(event) (thread async)
       │  MDC = {correlation_id, source_system, party_id, entity_id, entity_type}
       │  Lit le MDC → INSERT audit_entry SUCCESS
       │
T+5    Listener termine, MDC du thread async cleanup auto
       │
T+5    Le thread d'origine continue son cleanup (finally)
```

-----

## 4. Points d’entrée — capture du contexte

### 4.1 `CorrelationIdFilter` (HTTP)

```java
package com.crok4it.audit.entry;

import com.crok4it.audit.MdcKeys;
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

    private static final String HEADER = "X-Correlation-Id";

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

### 4.2 `KafkaCorrelationInterceptor` (Kafka)

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
        MDC.put(MdcKeys.SOURCE_SYSTEM, "KAFKA");
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

### 4.3 `OutboxDispatcher` (scheduler) — restauration depuis l’`OutboxRecord`

```java
package com.crok4it.outbox;

import com.crok4it.audit.MdcKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {

    private final OutboxRepository repository;
    private final OutboxHandlerRegistry handlerRegistry;

    @Scheduled(fixedDelay = 10000)
    public void dispatchPending() {
        List<OutboxRecord> pending = repository.findPending(50);
        for (OutboxRecord record : pending) {
            dispatch(record);
        }
    }

    private void dispatch(OutboxRecord record) {
        // Restaure le contexte d'audit depuis l'OutboxRecord
        MDC.put(MdcKeys.CORRELATION_ID, record.getCorrelationId());
        MDC.put(MdcKeys.SOURCE_SYSTEM, "SCHEDULER");
        if (record.getPartyId() != null) {
            MDC.put(MdcKeys.PARTY_ID, record.getPartyId());
        }
        MDC.put(MdcKeys.ENTITY_TYPE, record.getAggregateType());
        MDC.put(MdcKeys.ENTITY_ID, record.getAggregateId());

        try {
            var handler = handlerRegistry.findHandler(record.getCommandType());
            handler.handle(record.deserializeCommand());
            // L'aspect @Auditable(mode=BOTH) sur le handler logge le SUCCESS

        } catch (Throwable t) {
            // L'aspect @Auditable a déjà loggé le FAILURE
            log.error("Outbox dispatch failed for record {}", record.getId(), t);

        } finally {
            MDC.remove(MdcKeys.CORRELATION_ID);
            MDC.remove(MdcKeys.SOURCE_SYSTEM);
            MDC.remove(MdcKeys.PARTY_ID);
            MDC.remove(MdcKeys.ENTITY_TYPE);
            MDC.remove(MdcKeys.ENTITY_ID);
        }
    }
}
```

-----

## 5. Propagation entre threads

### 5.1 Pourquoi `ContextPropagatingTaskDecorator` est crucial

Quand Modulith dispatch un `@ApplicationModuleListener` async, il utilise un thread du pool `applicationTaskExecutor`. Sans décorateur, le MDC du thread publisher **n’est pas copié** vers le thread listener. Tu te retrouves avec :

```
Thread publisher : MDC = {correlation_id=corr-001, party_id=cust-42, ...}
       │
       ▼ publishEvent (Modulith met en queue)
Thread listener : MDC = {} (vide)
       │
       ▼ GenericAuditListener.onDomainEvent(event)
       INSERT audit_entry avec correlation_id=NULL, party_id=NULL  ← perdu !
```

### 5.2 La configuration correcte

```java
package com.crok4it.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    /**
     * Executor utilisé par Modulith pour les @ApplicationModuleListener async.
     *
     * ContextPropagatingTaskDecorator (Spring Framework 6+) copie automatiquement
     * le MDC + ContextSnapshot Micrometer du thread appelant vers le thread
     * d'exécution. Sans ce decorator, le MDC est perdu au passage async.
     */
    @Bean(name = "applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("audit-async-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());  // ◄── critical
        executor.initialize();
        return executor;
    }
}
```

### 5.3 Comment ça fonctionne en interne

Le `ContextPropagatingTaskDecorator` fonctionne ainsi :

1. **Au moment de la soumission** d’une tâche au pool, il **snapshote** le MDC du thread courant.
1. Quand la tâche s’exécute dans un thread du pool, il **applique** le snapshot au MDC du thread d’exécution.
1. À la fin de la tâche, il **restaure** le MDC précédent du thread du pool.

Donc dans le listener async :

```java
@ApplicationModuleListener
public void onDomainEvent(DomainEventInterface event) {
    // Le MDC contient EXACTEMENT ce qu'il y avait au moment du publishEvent
    String correlationId = MDC.get(MdcKeys.CORRELATION_ID);  // OK
    String partyId = MDC.get(MdcKeys.PARTY_ID);              // OK
}
```

-----

## 6. Lecture du MDC dans l’aspect et le listener

### 6.1 Lecture dans l’`AuditableAspect`

L’aspect lit le MDC pour enrichir l’`audit_entry` avec le contexte qui n’est pas dans l’annotation :

```java
private AuditEntry buildEntry(ProceedingJoinPoint pjp, Auditable auditable,
                               AuditEntry.Outcome outcome,
                               String errorCategory, String errorType,
                               String errorMessage,
                               Instant startedAt, long startNanos) {
    Instant completedAt = Instant.now();
    return AuditEntry.builder()
        .id(UUID.randomUUID())

        // Depuis l'annotation
        .entityType(auditable.entityType())
        .actionType(auditable.action())

        // Depuis SpEL (sur les arguments de la méthode)
        .entityId(spelEvaluator.evaluate(auditable.entityId(), pjp))
        .entitySubType(spelEvaluator.evaluate(auditable.entitySubType(), pjp))

        // Depuis le MDC (avec fallback SpEL si SpEL fournit, sinon MDC)
        .partyId(resolveValue(auditable.partyId(), pjp, MdcKeys.PARTY_ID))

        // Toujours depuis le MDC
        .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
        .traceId(MDC.get("traceId"))
        .sourceSystem(MDC.get(MdcKeys.SOURCE_SYSTEM))

        // Erreur si applicable
        .outcome(outcome)
        .errorCategory(errorCategory)
        .errorType(errorType)
        .errorMessage(errorMessage)

        // Timing
        .startedAt(startedAt)
        .completedAt(completedAt)
        .durationMs(Duration.between(startedAt, completedAt).toMillis())
        .build();
}

/**
 * Résout une valeur en cascade :
 *   1. Évalue le SpEL si fourni dans l'annotation
 *   2. Sinon, lit depuis le MDC
 *   3. Sinon, null
 */
private String resolveValue(String spelExpression, ProceedingJoinPoint pjp,
                             String mdcKey) {
    if (spelExpression != null && !spelExpression.isBlank()) {
        String fromSpel = spelEvaluator.evaluate(spelExpression, pjp);
        if (fromSpel != null) return fromSpel;
    }
    return MDC.get(mdcKey);
}
```

→ **Cascade SpEL → MDC → null**. Si l’annotation fournit `partyId="#review.customerId"` et que le SpEL réussit, on prend cette valeur. Sinon, on récupère ce qu’il y a dans le MDC. Sinon, null.

### 6.2 Lecture dans le `GenericAuditListener`

Le listener générique lit l’`event` ET le MDC. L’event apporte les données métier (entityType, entityId, partyId, status), le MDC apporte le contexte d’infrastructure (correlationId, traceId, sourceSystem) :

```java
@ApplicationModuleListener
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void onDomainEvent(DomainEventInterface event) {
    try {
        var entry = AuditEntry.builder()
            .id(UUID.randomUUID())

            // Depuis l'event
            .entityType(event.getEntityType())
            .entityId(event.getEntityId())
            .partyId(event.getPartyId())          // ← l'event a son propre partyId
            .actionType(event.getActionType())
            .statusAtEvent(event.getStatus())
            .entitySubType(event.getEntitySubType())

            // Depuis le MDC (propagé depuis le thread publisher)
            .correlationId(MDC.get(MdcKeys.CORRELATION_ID))
            .traceId(MDC.get("traceId"))
            .sourceSystem(MDC.get(MdcKeys.SOURCE_SYSTEM))

            // Toujours SUCCESS dans ce listener
            .outcome(AuditEntry.Outcome.SUCCESS)

            // Timing
            .startedAt(event.getOccurredAt())
            .completedAt(Instant.now())
            .durationMs(Duration.between(event.getOccurredAt(), Instant.now()).toMillis())

            .build();
        repository.save(entry);

    } catch (Throwable t) {
        log.error("Failed to persist SUCCESS audit (sent to Kafka ELK)", t);
    }
}
```

→ **Pas de doublon** : le `partyId` vient de l’event (canonique), le `correlationId` vient du MDC (technique).

-----

## 7. Le piège du `partyId` et sa solution

### 7.1 Le problème

Imagine cette méthode auditée :

```java
@Auditable(
    entityType = "REVIEW",
    action = "VALIDATE",
    entityId = "#reviewId",
    partyId = "#review.customerId"  // ← #review n'existe pas !
)
public void validate(String reviewId) {
    var review = repository.findById(reviewId).orElseThrow();
    review.validate();
}
```

Le SpEL `#review.customerId` **échoue** car `review` n’est pas un paramètre de la méthode. L’aspect appelle `spelEvaluator.evaluate(...)` qui retourne `null` (cf. tolérance aux erreurs).

Résultat : si `validate()` throw → `audit_entry` FAILURE avec `party_id=NULL`. Asymétrique avec les SUCCESS qui ont le partyId via l’event.

### 7.2 Trois solutions ordonnées

#### Solution A — Changer la signature pour accepter l’entité

```java
@Auditable(
    entityType = "REVIEW",
    action = "VALIDATE",
    entityId = "#review.id",
    partyId = "#review.customerId"
)
public void validate(Review review) {
    review.validate();
    repository.save(review);
    publisher.publishEvent(...);
}
```

✅ Solution idéale. Le SpEL fonctionne, partyId capturé partout.

❌ Mais ça force un refactor de tes services et controllers qui chargent eux-mêmes la review.

#### Solution B — Poser le partyId dans le MDC depuis le service

```java
@Auditable(
    entityType = "REVIEW",
    action = "VALIDATE",
    entityId = "#reviewId"
    // partyId pas dans l'annotation : sera lu du MDC par le resolveValue
)
public void validate(String reviewId) {
    var review = repository.findById(reviewId).orElseThrow();
    MDC.put(MdcKeys.PARTY_ID, review.getCustomerId());
    try {
        review.validate();
        repository.save(review);
        publisher.publishEvent(...);
    } finally {
        MDC.remove(MdcKeys.PARTY_ID);
    }
}
```

✅ Pas de refactor de signature.

❌ Boilerplate dans chaque service.

#### Solution C — `AuditContextEnricher` automatique

C’est la solution la plus propre. Un aspect dédié qui charge l’entité **avant** la méthode métier et pose les infos en MDC.

```java
package com.crok4it.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Enrichit le MDC avec les informations d'entité AVANT que le service métier
 * ne s'exécute, en utilisant un EntityContextResolver pour charger l'entité
 * et extraire son partyId.
 *
 * S'exécute AVANT AuditableAspect (Order 30 vs 50) pour que l'aspect d'audit
 * trouve le contexte déjà enrichi.
 */
@Aspect
@Component
@Order(30)
@RequiredArgsConstructor
@Slf4j
public class AuditContextEnricherAspect {

    private final EntityContextResolverRegistry resolverRegistry;

    @Around("@annotation(auditable)")
    public Object enrich(ProceedingJoinPoint pjp, Auditable auditable) throws Throwable {
        EntityContextResolver resolver = resolverRegistry.findResolver(auditable.entityType());

        if (resolver == null) {
            // Pas de resolver pour ce type d'entité, on continue sans enrichir
            return pjp.proceed();
        }

        // Snapshot des valeurs MDC précédentes (au cas où elles existent déjà)
        Map<String, String> previous = snapshotMdc();

        try {
            // Résolution du contexte (charge l'entité, extrait partyId, etc.)
            EntityContext ctx = resolver.resolve(pjp.getArgs(), auditable);

            if (ctx != null) {
                MDC.put(MdcKeys.ENTITY_TYPE, auditable.entityType());
                if (ctx.entityId() != null) MDC.put(MdcKeys.ENTITY_ID, ctx.entityId());
                if (ctx.partyId() != null) MDC.put(MdcKeys.PARTY_ID, ctx.partyId());
            }

            return pjp.proceed();

        } finally {
            // Restauration stricte
            MDC.remove(MdcKeys.ENTITY_TYPE);
            MDC.remove(MdcKeys.ENTITY_ID);
            MDC.remove(MdcKeys.PARTY_ID);
            previous.forEach(MDC::put);
        }
    }

    private Map<String, String> snapshotMdc() {
        Map<String, String> snap = new HashMap<>();
        for (String key : new String[]{MdcKeys.ENTITY_TYPE, MdcKeys.ENTITY_ID, MdcKeys.PARTY_ID}) {
            String value = MDC.get(key);
            if (value != null) snap.put(key, value);
        }
        return snap;
    }
}
```

```java
package com.crok4it.audit;

public record EntityContext(
    String entityId,
    String partyId
) {}
```

```java
package com.crok4it.audit;

/**
 * Implémenté par chaque module pour fournir la stratégie de chargement
 * d'une entité depuis les arguments de la méthode auditée.
 */
public interface EntityContextResolver {
    String supportedEntityType();
    EntityContext resolve(Object[] methodArgs, Auditable auditable);
}
```

```java
package com.crok4it.review;

import com.crok4it.audit.Auditable;
import com.crok4it.audit.EntityContext;
import com.crok4it.audit.EntityContextResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewContextResolver implements EntityContextResolver {

    private final ReviewRepository repository;

    @Override
    public String supportedEntityType() {
        return "REVIEW";
    }

    @Override
    public EntityContext resolve(Object[] methodArgs, Auditable auditable) {
        // Récupère le reviewId depuis le premier argument String
        for (Object arg : methodArgs) {
            if (arg instanceof String reviewId) {
                return repository.findById(reviewId)
                    .map(r -> new EntityContext(r.getId(), r.getCustomerId()))
                    .orElse(new EntityContext(reviewId, null));
            }
            if (arg instanceof Review review) {
                return new EntityContext(review.getId(), review.getCustomerId());
            }
        }
        return null;
    }
}
```

```java
package com.crok4it.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class EntityContextResolverRegistry {

    private final Map<String, EntityContextResolver> byEntityType;

    public EntityContextResolverRegistry(List<EntityContextResolver> resolvers) {
        this.byEntityType = resolvers.stream()
            .collect(Collectors.toMap(
                EntityContextResolver::supportedEntityType,
                r -> r));
    }

    public EntityContextResolver findResolver(String entityType) {
        return byEntityType.get(entityType);
    }
}
```

✅ Découplage propre : chaque module définit son resolver, l’aspect d’enrichissement les utilise génériquement.

❌ 1 fichier à créer par type d’entité auditée. Acceptable selon le nombre de types.

### 7.3 Recommandation

**Pour ton cas** : démarre avec **Solution B** (MDC manuel dans les services qui chargent l’entité). C’est 2 lignes par méthode, tu vois ce qui se passe, c’est explicite. Si ça devient pénible (>10 services), passe en **Solution C** (resolver auto).

La **Solution A** est l’idéal mais demande de refactorer les signatures.

-----

## 8. Customizer ECS pour exposer le MDC dans les logs JSON

Pour que les logs Logback ECS exposent ton MDC custom :

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
        members.add("business", event -> mapBusiness(event.getMDCPropertyMap()));
    }

    private Map<String, String> mapBusiness(Map<String, String> mdc) {
        if (mdc.isEmpty()) return null;

        Map<String, String> map = new LinkedHashMap<>();
        addIfPresent(map, mdc, MdcKeys.CORRELATION_ID, "correlation_id");
        addIfPresent(map, mdc, MdcKeys.SOURCE_SYSTEM, "source_system");
        addIfPresent(map, mdc, MdcKeys.PARTY_ID, "party_id");
        addIfPresent(map, mdc, MdcKeys.ENTITY_ID, "entity_id");
        addIfPresent(map, mdc, MdcKeys.ENTITY_TYPE, "entity_type");

        return map.isEmpty() ? null : map;
    }

    private void addIfPresent(Map<String, String> dst, Map<String, String> mdc,
                              String mdcKey, String jsonKey) {
        String v = mdc.get(mdcKey);
        if (v != null) dst.put(jsonKey, v);
    }
}
```

`application.yaml` :

```yaml
logging:
  structured:
    format:
      console: ecs
    json:
      customizer: com.crok4it.config.EcsBusinessFieldsCustomizer
```

→ Tes logs JSON ECS contiendront le bloc `business.*` avec **toutes les dimensions** que ton MDC porte à ce moment-là.

-----

## 9. Tests de propagation

### 9.1 Test unitaire — propagation dans le service

```java
package com.crok4it.review;

import com.crok4it.audit.MdcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;

@SpringBootTest
class ReviewServiceAuditPropagationTest {

    @Autowired ReviewService reviewService;
    @MockBean ReviewRepository repository;
    @Autowired AuditEntryRepository auditRepository;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldPropagateCorrelationIdInFailureAudit() {
        MDC.put(MdcKeys.CORRELATION_ID, "test-corr-001");
        MDC.put(MdcKeys.SOURCE_SYSTEM, "TEST");

        var review = mockReview("rev-001", "cust-42", ReviewStatus.LOCKED);
        when(repository.findById("rev-001")).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.validate("rev-001"))
            .isInstanceOf(BusinessException.class);

        // Vérifier qu'une ligne audit_entry FAILURE existe avec correlation_id
        var entries = auditRepository.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getCorrelationId()).isEqualTo("test-corr-001");
        assertThat(entries.get(0).getSourceSystem()).isEqualTo("TEST");
        assertThat(entries.get(0).getEntityId()).isEqualTo("rev-001");
        assertThat(entries.get(0).getOutcome()).isEqualTo(AuditEntry.Outcome.BUSINESS_FAILURE);
    }
}
```

### 9.2 Test d’intégration — propagation à travers le listener async

```java
@SpringBootTest
class AsyncAuditPropagationIT {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired AuditEntryRepository auditRepository;

    @Test
    void shouldPropagateMdcThroughAsyncListener() throws InterruptedException {
        MDC.put(MdcKeys.CORRELATION_ID, "test-corr-002");
        MDC.put(MdcKeys.SOURCE_SYSTEM, "TEST");

        publisher.publishEvent(new ReviewValidatedEvent(
            "rev-002", "cust-43", "VALIDATED", "PREMIUM", Instant.now()
        ));

        // Attendre que le listener async termine
        await().atMost(5, SECONDS).untilAsserted(() -> {
            var entries = auditRepository.findAll();
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getCorrelationId()).isEqualTo("test-corr-002");
            // ContextPropagatingTaskDecorator a fait son job
        });
    }
}
```

### 9.3 Test d’intégration — propagation depuis Kafka

```java
@SpringBootTest
@EmbeddedKafka(topics = "reviews.received")
class KafkaAuditPropagationIT {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired AuditEntryRepository auditRepository;

    @Test
    void shouldPropagateCorrelationIdFromKafkaHeader() {
        var record = new ProducerRecord<>("reviews.received", "rev-003", "{...}");
        record.headers().add(KafkaCorrelationInterceptor.CORRELATION_HEADER,
            "test-corr-003".getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record);

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var entries = auditRepository.findByEntityId("rev-003");
            assertThat(entries).isNotEmpty();
            assertThat(entries.get(0).getCorrelationId()).isEqualTo("test-corr-003");
            assertThat(entries.get(0).getSourceSystem()).isEqualTo("KAFKA");
        });
    }
}
```

-----

## 10. Récap complet

### 10.1 Composants ajoutés / modifiés

|Composant                      |Statut                           |Rôle                                                   |
|-------------------------------|---------------------------------|-------------------------------------------------------|
|`MdcKeys` (enrichi)            |Modifié                          |+ `PARTY_ID`, `ENTITY_ID`, `ENTITY_TYPE`               |
|`CorrelationIdFilter`          |Inchangé                         |Capture HTTP                                           |
|`KafkaCorrelationInterceptor`  |Inchangé                         |Capture Kafka                                          |
|`OutboxDispatcher`             |Modifié                          |Restaure aussi `PARTY_ID`, `ENTITY_ID`, `ENTITY_TYPE`  |
|`AsyncConfig`                  |Inchangé                         |`ContextPropagatingTaskDecorator`                      |
|`AuditableAspect`              |Modifié                          |`resolveValue` avec cascade SpEL → MDC                 |
|`GenericAuditListener`         |Inchangé                         |Lit l’event + le MDC                                   |
|`EcsBusinessFieldsCustomizer`  |Modifié                          |Expose `party_id`, `entity_id`, `entity_type` dans logs|
|`AuditContextEnricherAspect`   |**Nouveau** (Solution C)         |Pose `PARTY_ID` automatiquement avant la méthode métier|
|`EntityContextResolver`        |**Nouveau** (Solution C)         |Interface pour les resolvers par module                |
|`EntityContextResolverRegistry`|**Nouveau** (Solution C)         |Registre des resolvers                                 |
|`ReviewContextResolver`        |**Nouveau** (Solution C, exemple)|Implémentation pour REVIEW                             |

### 10.2 Garanties de propagation

|Étape                    |`correlation_id`              |`source_system`   |`party_id`              |`entity_id`             |`entity_type`           |
|-------------------------|------------------------------|------------------|------------------------|------------------------|------------------------|
|HTTP Filter              |✅ posé                        |✅ posé            |—                       |—                       |—                       |
|Kafka Interceptor        |✅ posé                        |✅ posé            |—                       |—                       |—                       |
|Service métier           |✅ hérité                      |✅ hérité          |✅ posé (Sol B/C)        |✅ posé (Sol B/C)        |✅ posé (Sol B/C)        |
|AuditableAspect (FAILURE)|✅ lu                          |✅ lu              |✅ lu                    |✅ lu                    |✅ lu                    |
|Publication event        |✅ MDC actif                   |✅ MDC actif       |✅ MDC actif             |✅ MDC actif             |✅ MDC actif             |
|Listener async (SUCCESS) |✅ propagé via decorator       |✅ propagé         |✅ propagé (depuis event)|✅ propagé (depuis event)|✅ propagé (depuis event)|
|Outbox handler           |✅ restauré depuis OutboxRecord|✅ posé “SCHEDULER”|✅ restauré              |✅ restauré              |✅ restauré              |
|AuditableAspect (handler)|✅ lu                          |✅ lu              |✅ lu                    |✅ lu                    |✅ lu                    |

→ **Toutes les lignes `audit_entry`** ont leurs champs `correlation_id` et `party_id` peuplés correctement, **quelle que soit la source** (event de domaine ou aspect AOP, succès ou échec, exécution normale ou retry).

### 10.3 Choix recommandé

|Situation                                        |Solution recommandée                                     |
|-------------------------------------------------|---------------------------------------------------------|
|Démarrage rapide, < 10 méthodes auditées         |**Solution B** : `MDC.put` manuel dans les services      |
|Beaucoup de méthodes auditées, + de structuration|**Solution C** : `AuditContextEnricherAspect` + resolvers|
|Refactor possible des signatures                 |**Solution A** : passer l’entité en paramètre + SpEL     |

Tu peux **commencer** avec la Solution B et **migrer** vers C quand le coût du boilerplate devient gênant. Aucun changement requis dans les autres composants.

-----

## Conclusion

Avec ces ajouts au document `audit-async-with-aop.md`, ton flux d’audit garantit la propagation **complète et cohérente** du contexte dans tous les cas :

- ✅ Le `correlationId` traverse HTTP / Kafka / async listener / scheduler
- ✅ Le `partyId` est disponible **même en cas d’échec** (via Solution B ou C)
- ✅ Les lignes `audit_entry` sont **uniformément peuplées** (success ET failure)
- ✅ Les logs JSON ECS exposent toutes les dimensions du MDC
- ✅ Tests d’intégration vérifient la propagation à chaque étape
- ✅ Pas de code spécifique à l’audit dans le métier (juste `@Auditable` + `MDC.put` ponctuel)

Tu as maintenant une solution **complète, propre et testable** adaptée à ton archi exacte.
