# Enrichissement transparent des events & propagation du correlationId

**Objectif** : Le dev écrit `publisher.publishEvent(new OrderPlacedEvent(...))` sans aucun code d’audit. Le `correlationId` est :

- **Capturé** automatiquement à l’entrée (HTTP, Kafka, Scheduler)
- **Propagé** sur toute la chaîne (event → listener → event enfant…)
- **Restauré** au replay Modulith

## 1. Vue d’ensemble du mécanisme

```
HTTP Request (header X-Correlation-Id ou généré)
    │
    ▼
[1] CorrelationIdFilter
    │   MDC[business.correlation_id] = "corr-001"
    │   MDC[business.source_system]  = "HTTP"
    ▼
[2] OrderService.placeOrder()
    │   publisher.publishEvent(new OrderPlacedEvent(..., null))  ← null = transparent
    ▼
[3] AuditAwareEventPublisher (@Primary)
    │   Détecte AuditableEvent + auditMetadata=null
    │   Lit MDC + CurrentAuditHolder
    │   Appelle event.withAuditMetadata(metadata)
    │   Délègue au multicaster Spring
    ▼
[4] @ApplicationModuleListener invoqué
    │   AuditContextRestorerAspect 
    intercepte AVANT exécution
    │   Restaure MDC depuis event.auditMetadata()
    │   Pose CurrentAuditHolder pour propagation aux events enfants
    │   Détecte replay si publishedAt > 30s
    ▼
[5] Listener publie un event enfant
    │   publisher.publishEvent(new OrderShippedEvent(..., null))
    ▼
[3'] AuditAwareEventPublisher
    │   CurrentAuditHolder.get() retourne le metadata du parent
    │   → forChildEvent(parent, ...) → MÊME correlationId, chainDepth+1
    ▼
... la chaîne continue, correlationId préservé
```

-----

## 2. `MdcKeys` — constantes centralisées

```java
package com.crok4it.observability.audit;

public final class MdcKeys {
    private MdcKeys() {}

    public static final String CORRELATION_ID = "business.correlation_id";
    public static final String CAUSATION_ID = "business.causation_id";
    public static final String SOURCE_SYSTEM = "business.source_system";
    public static final String USER_ID = "business.user_id";
    public static final String TENANT_ID = "business.tenant_id";
    public static final String CHAIN_DEPTH = "business.chain_depth";
}
```

-----

## 3. `CurrentAuditHolder` — propagation parent → enfant via ThreadLocal

```java
package com.crok4it.observability.audit;

/**
 * ThreadLocal qui contient l'AuditMetadata du listener actuellement en cours.
 * Permet aux events publiés à l'intérieur d'hériter automatiquement du contexte parent.
 *
 * Géré par AuditContextRestorerAspect (set à l'entrée du listener, clear en finally).
 *
 * Si tu utilises @Async ou un scheduler, configure les executors avec
 * ContextPropagatingTaskDecorator pour propager ce ThreadLocal entre threads.
 */
public final class CurrentAuditHolder {

    private static final ThreadLocal<AuditMetadata> CURRENT = new ThreadLocal<>();

    private CurrentAuditHolder() {}

    public static void set(AuditMetadata metadata) {
        CURRENT.set(metadata);
    }

    public static AuditMetadata get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
```

-----

## 4. `AuditAwareEventPublisher` — enrichissement transparent (LE point clé)

```java
package com.crok4it.observability.audit;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Wrapper @Primary de ApplicationEventPublisher qui enrichit automatiquement
 * les AuditableEvent publiés avec auditMetadata=null.
 *
 * IMPORTANT : on délègue à ApplicationEventMulticaster (et pas à un autre
 * ApplicationEventPublisher) pour éviter une boucle infinie — sinon @Primary
 * nous redirigerait vers nous-même.
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
        publishEvent(event, null);
    }

    @Override
    public void publishEvent(Object event, ResolvableType eventType) {
        Object enriched = enrichIfNeeded(event);

        // Adapte tout objet en ApplicationEvent comme le fait l'ApplicationContext
        ApplicationEvent applicationEvent = enriched instanceof ApplicationEvent ae
            ? ae
            : new PayloadApplicationEvent<>(this, enriched);

        multicaster.multicastEvent(applicationEvent, eventType);
    }

    private Object enrichIfNeeded(Object event) {
        // Event non auditable → passthrough
        if (!(event instanceof AuditableEvent auditable)) {
            return event;
        }
        // Déjà enrichi (ex: replay Modulith) → ne pas écraser
        if (auditable.auditMetadata() != null) {
            return event;
        }

        AuditMetadata metadata = buildMetadata();
        log.debug("Enriching {} correlationId={} chainDepth={} source={}",
            event.getClass().getSimpleName(),
            metadata.correlationId(),
            metadata.chainDepth(),
            metadata.sourceSystem());

        return auditable.withAuditMetadata(metadata);
    }

    /**
     * Construit l'AuditMetadata selon le contexte courant :
     *   - parent dans CurrentAuditHolder → event ENFANT (héritage correlationId)
     *   - sinon → event RACINE (depuis MDC ou nouveau correlationId)
     */
    private AuditMetadata buildMetadata() {
        AuditMetadata parent = CurrentAuditHolder.get();
        if (parent != null) {
            String causationId = MDC.get(MdcKeys.CAUSATION_ID);
            return AuditMetadata.forChildEvent(parent,
                causationId != null ? causationId : UUID.randomUUID().toString());
        }
        String sourceSystem = MDC.get(MdcKeys.SOURCE_SYSTEM);
        return AuditMetadata.forRootEvent(
            sourceSystem != null ? sourceSystem : detectSourceSystem());
    }

    private String detectSourceSystem() {
        String threadName = Thread.currentThread().getName();
        if (threadName.startsWith("http-")) return "HTTP";
        if (threadName.contains("KafkaConsumer")) return "KAFKA";
        if (threadName.startsWith("sched-")) return "SCHEDULER";
        if (threadName.startsWith("async-")) return "ASYNC";
        return "INTERNAL";
    }
}
```

-----

## 5. `AuditContextRestorerAspect` — restauration MDC à l’entrée du listener

```java
package com.crok4it.observability.audit;

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
 * Intercepte les @ApplicationModuleListener et :
 *   1. Restaure le MDC depuis event.auditMetadata()
 *   2. Pose CurrentAuditHolder pour que les events publiés à l'intérieur héritent
 *   3. Détecte les replays (publishedAt > 30s) → marque source_system = REPLAY
 *   4. Restaure le MDC précédent en finally (pas de fuite entre listeners)
 *
 * Le développeur du listener n'a RIEN à coder pour l'audit.
 */
@Aspect
@Component
@Order(0)
@Slf4j
public class AuditContextRestorerAspect {

    private static final Duration REPLAY_THRESHOLD = Duration.ofSeconds(30);
    private static final String[] MANAGED_KEYS = {
        MdcKeys.CORRELATION_ID, MdcKeys.CAUSATION_ID, MdcKeys.SOURCE_SYSTEM,
        MdcKeys.USER_ID, MdcKeys.TENANT_ID, MdcKeys.CHAIN_DEPTH
    };

    @Around("@annotation(org.springframework.modulith.events.ApplicationModuleListener) && args(event,..)")
    public Object restoreContext(ProceedingJoinPoint pjp, Object event) throws Throwable {

        if (!(event instanceof AuditableEvent auditable) || auditable.auditMetadata() == null) {
            return pjp.proceed();
        }

        AuditMetadata metadata = auditable.auditMetadata();
        if (isReplayContext(metadata)) {
            metadata = metadata.asReplay();
            log.debug("Detected replay correlationId={} (publishedAt={})",
                metadata.correlationId(), metadata.publishedAt());
        }

        // Snapshot pour restauration STRICTE en finally
        Map<String, String> previousMdc = snapshotMdc();
        AuditMetadata previousHolder = CurrentAuditHolder.get();

        try {
            applyToMdc(metadata);
            CurrentAuditHolder.set(metadata);

            // ID stable pour cet event → causationId des events enfants publiés ici
            String eventId = generateEventId(event);
            MDC.put(MdcKeys.CAUSATION_ID, eventId);

            return pjp.proceed();

        } finally {
            // Restauration ThreadLocal
            if (previousHolder != null) {
                CurrentAuditHolder.set(previousHolder);
            } else {
                CurrentAuditHolder.clear();
            }
            // Restauration MDC
            restoreMdc(previousMdc);
        }
    }

    private boolean isReplayContext(AuditMetadata metadata) {
        return metadata.publishedAt() != null
            && Duration.between(metadata.publishedAt(), Instant.now())
                .compareTo(REPLAY_THRESHOLD) > 0;
    }

    private void applyToMdc(AuditMetadata m) {
        MDC.put(MdcKeys.CORRELATION_ID, m.correlationId());
        MDC.put(MdcKeys.SOURCE_SYSTEM, m.sourceSystem());
        if (m.userId() != null) MDC.put(MdcKeys.USER_ID, m.userId());
        if (m.tenantId() != null) MDC.put(MdcKeys.TENANT_ID, m.tenantId());
        MDC.put(MdcKeys.CHAIN_DEPTH, String.valueOf(m.chainDepth()));
        if (m.causationEventId() != null) {
            MDC.put(MdcKeys.CAUSATION_ID, m.causationEventId());
        }
    }

    private Map<String, String> snapshotMdc() {
        Map<String, String> snapshot = new HashMap<>();
        for (String key : MANAGED_KEYS) {
            String value = MDC.get(key);
            if (value != null) snapshot.put(key, value);
        }
        return snapshot;
    }

    private void restoreMdc(Map<String, String> snapshot) {
        for (String key : MANAGED_KEYS) {
            MDC.remove(key);
        }
        snapshot.forEach(MDC::put);
    }

    private String generateEventId(Object event) {
        return event.getClass().getSimpleName() + ":" +
            Integer.toHexString(System.identityHashCode(event));
    }
}
```

-----

## 6. `CorrelationIdFilter` — capture à l’entrée HTTP

```java
package com.crok4it.observability.audit.entry;

import com.crok4it.observability.audit.MdcKeys;
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

/**
 * Capture (ou génère) le correlationId au plus tôt dans la requête HTTP.
 * Pose le MDC, l'event publisher en hérite automatiquement.
 */
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

        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        MDC.put(MdcKeys.SOURCE_SYSTEM, "HTTP");
        response.setHeader(HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.CORRELATION_ID);
            MDC.remove(MdcKeys.SOURCE_SYSTEM);
        }
    }
}
```

-----

## 7. Capture côté Kafka consumer (équivalent du filter HTTP)

```java
package com.crok4it.observability.audit.entry;

import com.crok4it.observability.audit.MdcKeys;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Pose le correlationId dans le MDC à partir du header Kafka.
 * Si absent (premier message d'une chaîne), en génère un.
 */
@Component
public class KafkaCorrelationInterceptor implements RecordInterceptor<String, Object> {

    public static final String CORRELATION_HEADER = "x-business-correlation-id";

    @Override
    public ConsumerRecord<String, Object> intercept(
            ConsumerRecord<String, Object> record,
            Consumer<String, Object> consumer) {

        Header header = record.headers().lastHeader(CORRELATION_HEADER);
        String correlationId = header != null
            ? new String(header.value(), StandardCharsets.UTF_8)
            : UUID.randomUUID().toString();

        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        MDC.put(MdcKeys.SOURCE_SYSTEM, "KAFKA");
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, Object> record,
                            Consumer<String, Object> consumer) {
        MDC.remove(MdcKeys.CORRELATION_ID);
        MDC.remove(MdcKeys.SOURCE_SYSTEM);
    }
}
```

À déclarer sur la factory :

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
        ConsumerFactory<String, Object> consumerFactory,
        KafkaCorrelationInterceptor interceptor) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
    factory.setConsumerFactory(consumerFactory);
    factory.setRecordInterceptor(interceptor);
    return factory;
}
```

-----

## 8. Configuration des dépendances (pour AOP)

`pom.xml` :

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-core</artifactId>
</dependency>
```

-----

## 9. Démonstration — flux complet sans code d’audit

### Service métier (ZÉRO code d’audit)

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final ApplicationEventPublisher publisher;

    @Transactional
    public void placeOrder(String customerId, BigDecimal amount) {
        // Aucun code d'audit. AuditAwareEventPublisher fait le travail.
        publisher.publishEvent(
            new OrderPlacedEvent("ord-001", customerId, amount, "EUR"));
    }
}
```

### Listener (ZÉRO code d’audit)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderShippingListener {

    private final ApplicationEventPublisher publisher;

    @ApplicationModuleListener
    void onOrderPlaced(OrderPlacedEvent event) {
        // MDC déjà restauré par l'aspect → ce log a business.correlation_id, etc.
        log.info("Preparing shipping for order {}", event.orderId());

        // Le nouvel event hérite AUTOMATIQUEMENT du correlationId du parent
        publisher.publishEvent(
            new OrderShippedEvent(event.orderId(), "TRACK-12345"));
    }
}
```

### Trace observée

```bash
curl -X POST http://localhost:8080/orders \
     -H "X-Correlation-Id: ord-001" \
     -d '{"customerId":"cust-42","amount":150.00}'
```

Logs :

```
correlation_id=ord-001  source=HTTP  depth=0  Order persisted
correlation_id=ord-001  source=HTTP  depth=1  Preparing shipping for order ord-001
```

Si replay 6h plus tard :

```
correlation_id=ord-001  source=REPLAY  depth=1  Preparing shipping for order ord-001
```

→ Recherche Kibana `business.correlation_id : "ord-001"` ramène **toute** la chaîne, y compris les replays.

-----

## 10. Récap — les 5 pièces

|#|Composant                                              |Rôle                                                         |Effort dev par event|
|-|-------------------------------------------------------|-------------------------------------------------------------|--------------------|
|1|`MdcKeys`                                              |Constantes MDC                                               |—                   |
|2|`CurrentAuditHolder`                                   |ThreadLocal pour propagation parent → enfant                 |—                   |
|3|`AuditAwareEventPublisher` (`@Primary`)                |Enrichissement transparent à la publication                  |—                   |
|4|`AuditContextRestorerAspect`                           |Restaure MDC + pose CurrentAuditHolder à l’entrée du listener|—                   |
|5|`CorrelationIdFilter` (+ `KafkaCorrelationInterceptor`)|Amorce du correlationId aux entrées                          |—                   |

**Total boilerplate par event** : 1 ligne (`withAuditMetadata`, déjà couvert dans le doc précédent).

**Le développeur métier** :

- Crée son event avec `auditMetadata=null`
- Le publie normalement
- Tous les logs (publication + listener + chaîne) ont automatiquement `business.correlation_id`
- Le replay Modulith fonctionne tout seul (correlationId préservé)
