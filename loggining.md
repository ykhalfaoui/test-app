# Observabilité Spring Boot 3.4 — Implémentation détaillée

**Stack** : Spring Boot 3.4 · Java 17 · Spring Modulith · Kafka (consumer + producer + retry/DLT) · ShedLock manuel · Outbox pattern · K8s/K3s · Filebeat → ELK

-----

## Table des matières

1. [Architecture cible](#1-architecture-cible)
1. [Tableau de synthèse](#2-tableau-de-synthèse)
1. [Dépendances Maven](#3-dépendances-maven)
1. [Configuration YAML complète](#4-configuration-yaml-complète)
1. [Bootstrap & service identity](#5-bootstrap--service-identity)
1. [Customizers ECS pour MDC custom](#6-customizers-ecs-pour-mdc-custom)
1. [Kafka — observation, retry, DLT corrélation](#7-kafka--observation-retry-dlt-corrélation)
1. [Async & event listeners](#8-async--event-listeners)
1. [Modulith — events et replay](#9-modulith--events-et-replay)
1. [ShedLock manuel — wrapper observé complet](#10-shedlock-manuel--wrapper-observé-complet)
1. [SchedulingConfigurer dynamique](#11-schedulingconfigurer-dynamique)
1. [Outbox — propagation businessCorrelationId](#12-outbox--propagation-businesscorrelationid)
1. [Batch — span par item](#13-batch--span-par-item)
1. [Classification d’erreurs & masquage PII](#14-classification-derreurs--masquage-pii)
1. [Healthcheck jobs](#15-healthcheck-jobs)
1. [Tests d’intégration](#16-tests-dintégration)
1. [Infrastructure — index template, Filebeat, OTel collector](#17-infrastructure)
1. [Exemples de logs JSON attendus](#18-exemples-de-logs-json-attendus)
1. [Checklist de validation](#19-checklist-de-validation)
1. [Pièges & dépannage](#20-pièges--dépannage)

-----

## 1. Architecture cible

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Spring Boot 3.4 microservice                    │
│                                                                       │
│  HTTP entrant ──► Controller ──► Service ──► Repository              │
│       │                              │                                │
│       │ traceparent W3C              │                                │
│       │                              ▼                                │
│       ▼                       KafkaTemplate ──► topic                 │
│  Observation                  (observationEnabled=true)               │
│  (auto)                              │                                │
│                                      │ traceparent dans headers       │
│                                      ▼                                │
│  @KafkaListener ◄─── topic                                           │
│  (factory observation=true)                                          │
│  + RecordInterceptor                                                  │
│       │                                                               │
│       ▼                                                               │
│  Service ──► Modulith event ──► @ApplicationModuleListener           │
│                  │                       │                            │
│                  │                       │ AuditContextRestorerAspect │
│                  ▼                       ▼                            │
│           AuditableEvent          Observation enfant                  │
│           (correlationId)                                             │
│                                                                       │
│  ┌──── Background ────┐                                              │
│  │                    │                                               │
│  │  TaskScheduler ────┴──► ObservedJobRunner.wrap(JobDef, runnable)  │
│  │  (programmatique)       │                                          │
│  │                         ▼                                          │
│  │                  Observation("scheduled.job")                      │
│  │                         │                                          │
│  │                         ▼                                          │
│  │                  ShedLock executeWithLock                          │
│  │                         │                                          │
│  │                         ├─ executed → Timer + healthIndicator      │
│  │                         ├─ skipped_locked → debug + counter        │
│  │                         └─ failed → error + error tag              │
│  └────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  Logs Logback ──► console JSON ECS ──► stdout container               │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Filebeat DaemonSet (decode_json_fields)                             │
│  + add_kubernetes_metadata                                            │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Elasticsearch (index template ECS)                                  │
│  + Kibana dashboards                                                  │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│  OTel Collector (tail-sampling) ──► Tempo / Jaeger                   │
└─────────────────────────────────────────────────────────────────────┘
```

-----

## 2. Tableau de synthèse

|# |Préoccupation                  |Mécanisme                                          |Fichier(s)                                                             |Section |
|--|-------------------------------|---------------------------------------------------|-----------------------------------------------------------------------|--------|
|1 |Bridge tracing OTLP            |dépendances                                        |`pom.xml`                                                              |§3      |
|2 |Format JSON ECS console        |propriétés Boot 3.4                                |`application.yaml`                                                     |§4      |
|3 |service.name dès startup       |bootstrap                                          |`bootstrap.yaml`                                                       |§5      |
|4 |MDC custom → champs ECS        |`StructuredLoggingJsonMembersCustomizer`           |`EcsCustomFieldsCustomizer.java`                                       |§6      |
|5 |Kafka consumer observation     |`setObservationEnabled(true)`                      |`KafkaObservabilityConfig.java`                                        |§7.1    |
|6 |Kafka producer observation     |`setObservationEnabled(true)`                      |`KafkaObservabilityConfig.java`                                        |§7.1    |
|7 |Retry/DLT corrélation          |header `x-original-trace-id` + `RecordInterceptor` |`TraceCorrelationInterceptor.java` + `OutboundTraceHeaderProducer.java`|§7.2-7.3|
|8 |DLT publisher                  |`DeadLetterPublishingRecoverer` custom             |`KafkaErrorHandlingConfig.java`                                        |§7.4    |
|9 |@Async propagation             |`ContextPropagatingTaskDecorator`                  |`AsyncConfig.java`                                                     |§8      |
|10|Modulith events observés       |`spring-modulith-observability`                    |`pom.xml`                                                              |§3      |
|11|Replay Modulith → traceId      |AOP sur `@ApplicationModuleListener`               |`AuditableEvent.java` + `AuditContextRestorerAspect.java`              |§9      |
|12|Jobs ShedLock manuels          |`Observation` + `Timer` + outcome                  |`ObservedJobRunner.java` + `JobDefinition.java`                        |§10     |
|13|Detection lock dépassé         |`elapsedMs > lockAtMostFor`                        |`ObservedJobRunner.java`                                               |§10     |
|14|Configuration jobs externalisée|`@ConfigurationProperties` + `SchedulingConfigurer`|`JobsProperties.java` + `JobsSchedulingConfig.java`                    |§11     |
|15|Outbox correlation propagation |header dédié + restauration au dispatch            |`OutboxDispatcher.java`                                                |§12     |
|16|Span par item de batch         |`Observation` enfant + MDC `business.item.id`      |`BatchItemProcessor.java`                                              |§13     |
|17|Erreurs métier vs techniques   |`sealed interface` + log level dédié               |`ApplicationException.java`                                            |§14.1   |
|18|Masquage PII                   |customizer ECS sur `message`                       |`PiiMaskingCustomizer.java`                                            |§14.2   |
|19|Healthcheck jobs               |`HealthIndicator` custom                           |`JobsHealthIndicator.java`                                             |§15     |
|20|Test propagation Kafka         |`@SpringBootTest` + `@EmbeddedKafka`               |`KafkaTracePropagationIT.java`                                         |§16.1   |
|21|Test propagation jobs          |test du wrapper                                    |`ObservedJobRunnerIT.java`                                             |§16.2   |
|22|Index template ECS             |bootstrap script                                   |`infra/es/ecs-template.json`                                           |§17.1   |
|23|Filebeat parsing JSON          |DaemonSet K8s                                      |`infra/k8s/filebeat-daemonset.yaml`                                    |§17.2   |
|24|OTel collector tail-sampling   |`tail_sampling` processor                          |`infra/otel/collector-config.yaml`                                     |§17.3   |
|25|Convention naming              |tableau de référence                               |(doc)                                                                  |§17.4   |

-----

## 3. Dépendances Maven

`pom.xml` :

```xml
<properties>
    <java.version>17</java.version>
    <spring-boot.version>3.4.0</spring-boot.version>
    <spring-modulith.version>1.3.0</spring-modulith.version>
    <shedlock.version>5.16.0</shedlock.version>
</properties>

<dependencies>
    <!-- ===== Core Boot ===== -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>

    <!-- ===== Observabilité — INDISPENSABLE ===== -->
    <!-- Sans ces 3 dépendances, trace.id reste vide partout -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- ===== Modulith ===== -->
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-starter-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-events-jpa</artifactId>
    </dependency>
    <!-- Ajoute observation auto sur publication/consommation events -->
    <dependency>
        <groupId>org.springframework.modulith</groupId>
        <artifactId>spring-modulith-observability</artifactId>
    </dependency>

    <!-- ===== Kafka ===== -->
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>

    <!-- ===== ShedLock ===== -->
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-spring</artifactId>
        <version>${shedlock.version}</version>
    </dependency>
    <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-provider-jdbc-template</artifactId>
        <version>${shedlock.version}</version>
    </dependency>

    <!-- ===== Test ===== -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.awaitility</groupId>
        <artifactId>awaitility</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

-----

## 4. Configuration YAML complète

`src/main/resources/application.yaml` :

```yaml
spring:
  application:
    name: ${SERVICE_NAME:my-service}

  # Kafka
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    consumer:
      group-id: ${spring.application.name}
      auto-offset-reset: earliest
      enable-auto-commit: false
      properties:
        spring.json.trusted.packages: "com.crok4it.*"
    producer:
      acks: all
      properties:
        enable.idempotence: true
    listener:
      ack-mode: MANUAL_IMMEDIATE
      observation-enabled: true   # active aussi côté Boot auto-config

  # Modulith - publication asynchrone obligatoire pour observability
  modulith:
    events:
      jdbc:
        schema-initialization:
          enabled: true
      republish-outstanding-events-on-restart: true   # déclenche le replay

# ===== Observabilité =====
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
  tracing:
    enabled: true
    sampling:
      probability: 1.0           # head=100%, le tail-sampling se fait dans OTel collector
    propagation:
      type: w3c                   # standard W3C traceparent
  otlp:
    tracing:
      endpoint: ${OTLP_ENDPOINT:http://otel-collector:4318/v1/traces}
      compression: gzip
      timeout: 10s
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:local}

# ===== Logs JSON ECS =====
logging:
  structured:
    format:
      console: ecs              # natif Boot 3.4
    ecs:
      service:
        name: ${spring.application.name}
        version: ${APP_VERSION:unknown}
        environment: ${ENVIRONMENT:local}
        node:
          name: ${HOSTNAME:unknown}
  include-application-name: true

  # Niveaux ajustés pour limiter le bruit
  level:
    root: INFO
    com.crok4it: ${APP_LOG_LEVEL:INFO}
    org.springframework.kafka: INFO
    org.apache.kafka: WARN
    org.apache.kafka.clients.NetworkClient: ERROR  # bruit reconnexion
    org.hibernate.SQL: WARN
    org.springframework.modulith.events: INFO

# ===== Jobs externalisés =====
jobs:
  definitions:
    - name: salesforce-sync
      cron: "0 */5 * * * *"
      lockAtMostFor: PT15M
      lockAtLeastFor: PT30S
      logSkips: false
      bean: salesforceSyncJob
      method: run
      expectedFrequency: PT5M

    - name: outbox-dispatcher
      cron: "*/10 * * * * *"
      lockAtMostFor: PT1M
      lockAtLeastFor: PT0S
      logSkips: false           # tourne toutes les 10s, sinon flood
      bean: outboxDispatcherJob
      method: dispatchAll
      expectedFrequency: PT10S

    - name: cleanup-stale-data
      cron: "0 0 2 * * *"
      lockAtMostFor: PT2H
      lockAtLeastFor: PT5M
      logSkips: true            # quotidien, OK de logger les skips
      bean: cleanupJob
      method: cleanup
      expectedFrequency: PT24H
```

-----

## 5. Bootstrap & service identity

`src/main/resources/bootstrap.yaml` (lu **avant** application.yaml, garantit que `service.name` est disponible dès les premiers logs) :

```yaml
spring:
  application:
    name: ${SERVICE_NAME:my-service}
```

`Dockerfile` (variables d’env passées au runtime) :

```dockerfile
FROM eclipse-temurin:17-jre

ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

COPY target/*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV ENVIRONMENT=production

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
```

`infra/k8s/deployment.yaml` (extrait) :

```yaml
spec:
  template:
    spec:
      containers:
        - name: my-service
          image: registry.crok4it.lu/my-service:1.4.0
          env:
            - name: SERVICE_NAME
              value: my-service
            - name: APP_VERSION
              value: "1.4.0"
            - name: ENVIRONMENT
              value: production
            - name: HOSTNAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: OTLP_ENDPOINT
              value: http://otel-collector.observability.svc.cluster.local:4318/v1/traces
          resources:
            requests:
              cpu: "1"
              memory: "1Gi"
            limits:
              cpu: "2"
              memory: "2Gi"
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
```

-----

## 6. Customizers ECS pour MDC custom

### 6.1 Pourquoi un customizer

Par défaut, le format ECS de Boot 3.4 sérialise tout le MDC sous `labels.*` ou ne le sérialise pas selon les versions. Pour avoir des **champs ECS first-class** typés `keyword` et indexables proprement, on utilise `StructuredLoggingJsonMembersCustomizer<ILoggingEvent>`.

### 6.2 `EcsCustomFieldsCustomizer.java`

```java
package com.crok4it.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.boot.logging.structured.json.JsonWriter.Members;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mappe les MDC custom vers des champs ECS structurés.
 *
 * Champs produits :
 *   job.{name, execution_id, outcome}
 *   business.{correlation_id, tenant_id, item_id, source_system}
 *   kafka.{topic, partition, offset, attempt}
 *
 * Le champ n'est ajouté au JSON que si la clé MDC racine existe → pas de bruit.
 */
public class EcsCustomFieldsCustomizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(Members<ILoggingEvent> members) {
        members.add("job", event -> mapJob(event.getMDCPropertyMap()));
        members.add("business", event -> mapBusiness(event.getMDCPropertyMap()));
        members.add("kafka", event -> mapKafka(event.getMDCPropertyMap()));
    }

    private Map<String, String> mapJob(Map<String, String> mdc) {
        if (!mdc.containsKey("job.name")) return null;
        Map<String, String> map = new LinkedHashMap<>();
        map.put("name", mdc.get("job.name"));
        putIfPresent(map, mdc, "job.execution.id", "execution_id");
        putIfPresent(map, mdc, "job.outcome", "outcome");
        return map;
    }

    private Map<String, String> mapBusiness(Map<String, String> mdc) {
        if (!mdc.containsKey("business.correlation.id")
                && !mdc.containsKey("business.tenant.id")) return null;
        Map<String, String> map = new LinkedHashMap<>();
        putIfPresent(map, mdc, "business.correlation.id", "correlation_id");
        putIfPresent(map, mdc, "business.tenant.id", "tenant_id");
        putIfPresent(map, mdc, "business.item.id", "item_id");
        putIfPresent(map, mdc, "business.source_system", "source_system");
        return map;
    }

    private Map<String, String> mapKafka(Map<String, String> mdc) {
        if (!mdc.containsKey("kafka.topic")) return null;
        Map<String, String> map = new LinkedHashMap<>();
        map.put("topic", mdc.get("kafka.topic"));
        putIfPresent(map, mdc, "kafka.partition", "partition");
        putIfPresent(map, mdc, "kafka.offset", "offset");
        putIfPresent(map, mdc, "kafka.attempt", "attempt");
        return map;
    }

    private void putIfPresent(Map<String, String> dst, Map<String, String> mdc,
                              String mdcKey, String jsonKey) {
        String v = mdc.get(mdcKey);
        if (v != null) dst.put(jsonKey, v);
    }
}
```

### 6.3 Enregistrement

Référencé dans `application.yaml` :

```yaml
logging:
  structured:
    json:
      customizer: com.crok4it.observability.logging.EcsCustomFieldsCustomizer
```

⚠️ Le customizer est **instancié par Logback**, pas par Spring → pas d’injection possible. Si tu as besoin de beans Spring dedans, voir le pattern `ApplicationContextProvider` static.

-----

## 7. Kafka — observation, retry, DLT corrélation

### 7.1 `KafkaObservabilityConfig.java`

```java
package com.crok4it.observability.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.RecordInterceptor;

@Configuration
public class KafkaObservabilityConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object>
            kafkaListenerContainerFactory(
                ConsumerFactory<String, Object> consumerFactory,
                RecordInterceptor<String, Object> traceCorrelationInterceptor) {

        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);

        // CLÉ : sans ça, le consumer ne crée pas d'Observation enfant et
        // le traceparent du header est ignoré
        factory.getContainerProperties().setObservationEnabled(true);

        factory.setRecordInterceptor(traceCorrelationInterceptor);
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> pf,
            OutboundTraceHeaderProducer headerProducer) {

        var template = new KafkaTemplate<>(pf);
        // CLÉ : sans ça, le producer n'injecte pas le header traceparent
        template.setObservationEnabled(true);

        // ProducerInterceptor pour ajouter notre header business custom
        template.setProducerInterceptor(headerProducer);
        return template;
    }
}
```

### 7.2 `TraceCorrelationInterceptor.java` — réception

```java
package com.crok4it.observability.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Avant exécution du listener :
 *  - alimente le MDC avec topic/partition/offset/attempt
 *  - restaure businessCorrelationId depuis le header x-original-trace-id
 *
 * Le traceparent W3C est géré nativement par Spring Kafka quand
 * observationEnabled=true → on n'a rien à faire pour trace.id/span.id.
 */
@Component
public class TraceCorrelationInterceptor implements RecordInterceptor<String, Object> {

    public static final String ORIGINAL_TRACE_HEADER = "x-original-trace-id";
    public static final String BUSINESS_CORRELATION_HEADER = "x-business-correlation-id";
    public static final String ATTEMPT_HEADER = "kafka_dlt-original-attempt"; // standard Spring

    @Override
    public ConsumerRecord<String, Object> intercept(
            ConsumerRecord<String, Object> record,
            Consumer<String, Object> consumer) {

        MDC.put("kafka.topic", record.topic());
        MDC.put("kafka.partition", String.valueOf(record.partition()));
        MDC.put("kafka.offset", String.valueOf(record.offset()));

        String correlationId = headerAsString(record, BUSINESS_CORRELATION_HEADER);
        if (correlationId != null) {
            MDC.put("business.correlation.id", correlationId);
        }

        String attempt = headerAsString(record, ATTEMPT_HEADER);
        MDC.put("kafka.attempt", attempt != null ? attempt : "1");

        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, Object> record,
                            Consumer<String, Object> consumer) {
        clearMdc();
    }

    @Override
    public void failure(ConsumerRecord<String, Object> record,
                        Exception exception,
                        Consumer<String, Object> consumer) {
        // failure() est appelé AVANT que l'error handler retry/DLT prenne la main
        // → ne PAS clear le MDC ici sinon l'error handler n'a plus le contexte
    }

    private static String headerAsString(ConsumerRecord<?, ?> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static void clearMdc() {
        MDC.remove("kafka.topic");
        MDC.remove("kafka.partition");
        MDC.remove("kafka.offset");
        MDC.remove("kafka.attempt");
        MDC.remove("business.correlation.id");
    }
}
```

### 7.3 `OutboundTraceHeaderProducer.java` — émission

```java
package com.crok4it.observability.kafka;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Pose le header business-correlation-id à partir du MDC actuel,
 * de sorte qu'il survive aux retries et au DLT.
 */
@Component
public class OutboundTraceHeaderProducer implements ProducerInterceptor<String, Object> {

    @Override
    public ProducerRecord<String, Object> onSend(ProducerRecord<String, Object> record) {
        String correlationId = MDC.get("business.correlation.id");
        if (correlationId != null
                && record.headers().lastHeader(
                    TraceCorrelationInterceptor.BUSINESS_CORRELATION_HEADER) == null) {
            record.headers().add(
                TraceCorrelationInterceptor.BUSINESS_CORRELATION_HEADER,
                correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Override public void onAcknowledgement(RecordMetadata md, Exception e) {}
    @Override public void close() {}
    @Override public void configure(Map<String, ?> configs) {}
}
```

### 7.4 `KafkaErrorHandlingConfig.java` — DLT avec préservation

```java
package com.crok4it.observability.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {

        // Le DLT recoverer COPIE automatiquement les headers x-* du record
        // d'origine → notre business correlation est préservée
        var recoverer = new DeadLetterPublishingRecoverer(template,
            (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        // Backoff exponentiel : 1s, 2s, 4s, 8s, 16s
        var backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(16000L);
        backOff.setMaxElapsedTime(60000L); // arrêt après 60s → DLT

        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setCommitRecovered(true);
        return handler;
    }
}
```

### 7.5 Schéma de séquence — retry + DLT

```
Producer            Topic              Consumer                ErrorHandler          DLT
   │                  │                    │                        │                  │
   │─── send() ──────►│                    │                        │                  │
   │  + traceparent   │                    │                        │                  │
   │  + x-business-   │                    │                        │                  │
   │    correlation-id│                    │                        │                  │
   │                  │                    │                        │                  │
   │                  │── poll ───────────►│                        │                  │
   │                  │                    │── intercept() ─────────│                  │
   │                  │                    │   MDC.put(correlation) │                  │
   │                  │                    │── @KafkaListener ─────►│                  │
   │                  │                    │     throws ex          │                  │
   │                  │                    │── failure() ──────────►│                  │
   │                  │                    │                        │                  │
   │                  │                    │◄── retry attempt 2 ────│                  │
   │                  │                    │   MDC encore actif     │                  │
   │                  │                    │     throws ex          │                  │
   │                  │                    │   ... attempt 3, 4, 5  │                  │
   │                  │                    │                        │                  │
   │                  │                    │                        │── DLT publish ──►│
   │                  │                    │                        │  copie headers   │
   │                  │                    │                        │  x-business-...  │
   │                  │                    │                        │  préservé !      │
```

-----

## 8. Async & event listeners

### 8.1 `AsyncConfig.java`

```java
package com.crok4it.observability.async;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("async-");
        executor.setRejectedExecutionHandler((r, e) ->
            log.warn("Async task rejected: queue full"));

        // CLÉ : propage MDC + Observation context vers le thread
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());

        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
            log.error("Uncaught async exception in {}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }

    /**
     * Executor dédié aux gros batchs — pool séparé pour ne pas saturer
     * les autres @Async legers.
     */
    @Bean(name = "batchExecutor")
    public Executor batchExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("batch-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
```

-----

## 9. Modulith — events et replay

### 9.1 `AuditableEvent.java` — interface marker

```java
package com.crok4it.observability.modulith;

/**
 * Tout event Modulith qui veut survivre au replay avec son contexte
 * doit implémenter cette interface.
 *
 * Choix de design : marker interface plutôt qu'inheritance pour
 * préserver l'immutabilité des records et l'indépendance des modules.
 */
public interface AuditableEvent {
    AuditMetadata auditMetadata();
}
```

### 9.2 `AuditMetadata.java`

```java
package com.crok4it.observability.modulith;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Métadonnées embarquées dans chaque event.
 * @JsonCreator est nécessaire pour la désérialisation au replay.
 */
public record AuditMetadata(
        String correlationId,
        String sourceSystem,
        String userId,
        String tenantId,
        Instant publishedAt,
        boolean isReplay) {

    @JsonCreator
    public AuditMetadata(
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("sourceSystem") String sourceSystem,
            @JsonProperty("userId") String userId,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("publishedAt") Instant publishedAt,
            @JsonProperty("isReplay") boolean isReplay) {
        this.correlationId = correlationId;
        this.sourceSystem = sourceSystem;
        this.userId = userId;
        this.tenantId = tenantId;
        this.publishedAt = publishedAt;
        this.isReplay = isReplay;
    }

    public static AuditMetadata fromCurrentContext(String sourceSystem) {
        String correlationId = org.slf4j.MDC.get("business.correlation.id");
        String tenantId = org.slf4j.MDC.get("business.tenant.id");
        return new AuditMetadata(
            correlationId != null ? correlationId : java.util.UUID.randomUUID().toString(),
            sourceSystem,
            org.slf4j.MDC.get("user.id"),
            tenantId,
            Instant.now(),
            false);
    }
}
```

### 9.3 `AuditContextRestorerAspect.java`

```java
package com.crok4it.observability.modulith;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Intercepte les @ApplicationModuleListener et restaure le contexte
 * d'audit (correlationId, sourceSystem) depuis l'event lui-même.
 *
 * Vital pour les replays IncompleteEventPublications : sans ça,
 * le replay tourne avec un MDC vide et on perd la trace métier.
 */
@Aspect
@Component
@Order(0) // s'exécute avant les autres aspects
@RequiredArgsConstructor
public class AuditContextRestorerAspect {

    private final ObservationRegistry registry;

    @Around("@annotation(org.springframework.modulith.events.ApplicationModuleListener) && args(event,..)")
    public Object restoreContext(ProceedingJoinPoint pjp, Object event) throws Throwable {

        if (!(event instanceof AuditableEvent auditable) || auditable.auditMetadata() == null) {
            return pjp.proceed();
        }

        AuditMetadata meta = auditable.auditMetadata();
        boolean isReplay = isReplayContext(meta);

        MDC.put("business.correlation.id", meta.correlationId());
        MDC.put("business.source_system", isReplay ? "REPLAY" : meta.sourceSystem());
        if (meta.tenantId() != null) MDC.put("business.tenant.id", meta.tenantId());

        Observation obs = Observation.createNotStarted("modulith.event.handle", registry)
            .lowCardinalityKeyValue("event.type", event.getClass().getSimpleName())
            .lowCardinalityKeyValue("source.system", isReplay ? "REPLAY" : meta.sourceSystem());

        try {
            return obs.observe(() -> {
                try {
                    return pjp.proceed();
                } catch (Throwable t) {
                    sneakyThrow(t);
                    return null;
                }
            });
        } finally {
            MDC.remove("business.correlation.id");
            MDC.remove("business.source_system");
            MDC.remove("business.tenant.id");
        }
    }

    /**
     * Heuristique : si l'event a été publié il y a plus de 5 secondes,
     * c'est très probablement un replay au démarrage.
     */
    private boolean isReplayContext(AuditMetadata meta) {
        return meta.publishedAt() != null
            && java.time.Duration.between(meta.publishedAt(), java.time.Instant.now())
                .toSeconds() > 5;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
```

-----

## 10. ShedLock manuel — wrapper observé complet

### 10.1 `JobDefinition.java`

```java
package com.crok4it.observability.scheduling;

import java.time.Duration;

public record JobDefinition(
        String name,
        String cron,
        Duration lockAtMostFor,
        Duration lockAtLeastFor,
        boolean logSkips,
        String beanName,
        String methodName,
        Duration expectedFrequency) {

    /**
     * Validation à la construction.
     */
    public JobDefinition {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("Job name required");
        if (lockAtMostFor == null || lockAtMostFor.isNegative() || lockAtMostFor.isZero())
            throw new IllegalArgumentException("lockAtMostFor must be positive");
        if (lockAtLeastFor != null && lockAtLeastFor.compareTo(lockAtMostFor) > 0)
            throw new IllegalArgumentException("lockAtLeastFor cannot exceed lockAtMostFor");
    }
}
```

### 10.2 `ObservedJobRunner.java`

```java
package com.crok4it.observability.scheduling;

import com.crok4it.observability.error.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class ObservedJobRunner {

    private final ObservationRegistry observationRegistry;
    private final LockProvider lockProvider;
    private final MeterRegistry meterRegistry;
    private final JobsHealthIndicator healthIndicator;

    /**
     * Wrappe un Runnable métier dans :
     *  1. MDC enrichissement (job.name, job.execution.id)
     *  2. Observation Micrometer (génère traceId/spanId)
     *  3. ShedLock executeWithLock
     *  4. Métriques (Timer + Counter)
     *  5. Healthcheck update
     *  6. Détection lock dépassé (risque double exécution)
     */
    public Runnable wrap(JobDefinition def, Runnable task) {
        return () -> {
            String executionId = UUID.randomUUID().toString();
            MDC.put("job.name", def.name());
            MDC.put("job.execution.id", executionId);

            try {
                Observation.createNotStarted("scheduled.job", observationRegistry)
                    .lowCardinalityKeyValue("job.name", def.name())
                    .lowCardinalityKeyValue("job.type", "shedlock")
                    .observe(() -> executeWithLock(def, task));
            } finally {
                MDC.remove("job.name");
                MDC.remove("job.execution.id");
                MDC.remove("job.outcome");
            }
        };
    }

    private void executeWithLock(JobDefinition def, Runnable task) {
        var lockingExecutor = new DefaultLockingTaskExecutor(lockProvider);
        long start = System.nanoTime();
        String outcome;

        try {
            var lockConfig = new LockConfiguration(
                Instant.now(), def.name(),
                def.lockAtMostFor(),
                def.lockAtLeastFor() != null ? def.lockAtLeastFor() : java.time.Duration.ZERO);

            LockingTaskExecutor.TaskResult<Void> result =
                lockingExecutor.executeWithLock(
                    (LockingTaskExecutor.TaskWithResult<Void>) () -> {
                        task.run();
                        return null;
                    },
                    lockConfig);

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            if (!result.wasExecuted()) {
                outcome = "skipped_locked";
                if (def.logSkips()) {
                    log.debug("Job skipped: lock held by another instance");
                }
                meterRegistry.counter("scheduled.job.skipped",
                    "job.name", def.name()).increment();
            } else {
                outcome = "executed";
                healthIndicator.recordSuccess(def.name());

                if (elapsedMs > def.lockAtMostFor().toMillis()) {
                    log.warn("Job duration {}ms exceeded lockAtMostFor {}ms — " +
                        "RISK of concurrent execution on other nodes",
                        elapsedMs, def.lockAtMostFor().toMillis());
                    meterRegistry.counter("scheduled.job.lock.exceeded",
                        "job.name", def.name()).increment();
                }
                log.info("Job {} completed in {}ms", def.name(), elapsedMs);
            }
        } catch (BusinessException be) {
            outcome = "business_error";
            log.warn("Job {} failed (business): {}", def.name(), be.getMessage());
        } catch (Throwable t) {
            outcome = "technical_error";
            log.error("Job {} failed (technical)", def.name(), t);
            // Tag l'observation comme erreur pour Tempo/Jaeger
            Observation current = observationRegistry.getCurrentObservation();
            if (current != null) current.error(t);
        } finally {
            MDC.put("job.outcome", outcome);
            meterRegistry.timer("scheduled.job.duration",
                "job.name", def.name(),
                "outcome", outcome)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
```

-----

## 11. SchedulingConfigurer dynamique

### 11.1 `JobsProperties.java`

```java
package com.crok4it.observability.scheduling;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "jobs")
public class JobsProperties {
    private List<JobDefinition> definitions = List.of();
}
```

### 11.2 `JobsSchedulingConfig.java`

C’est ici qu’on charge les jobs depuis YAML, qu’on résout dynamiquement les beans/méthodes, et qu’on les enregistre dans le `TaskScheduler`.

```java
package com.crok4it.observability.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.lang.reflect.Method;
import java.util.TimeZone;

@Slf4j
@Configuration
@EnableConfigurationProperties(JobsProperties.class)
@RequiredArgsConstructor
public class JobsSchedulingConfig implements SchedulingConfigurer {

    private final JobsProperties jobsProperties;
    private final ObservedJobRunner observedJobRunner;
    private final ApplicationContext applicationContext;
    private final JobsHealthIndicator healthIndicator;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());

        for (JobDefinition def : jobsProperties.getDefinitions()) {
            try {
                Runnable bizTask = resolveTask(def);
                Runnable observed = observedJobRunner.wrap(def, bizTask);

                registrar.addTriggerTask(observed,
                    new CronTrigger(def.cron(), TimeZone.getTimeZone("UTC")));

                healthIndicator.register(def.name(), def.expectedFrequency());

                log.info("Registered job: name={} cron={} lockAtMostFor={}",
                    def.name(), def.cron(), def.lockAtMostFor());
            } catch (Exception e) {
                log.error("Failed to register job {}", def.name(), e);
                throw new IllegalStateException(
                    "Job registration failed: " + def.name(), e);
            }
        }
    }

    /**
     * Résout dynamiquement bean.method() depuis le contexte Spring.
     */
    private Runnable resolveTask(JobDefinition def) throws NoSuchMethodException {
        Object bean = applicationContext.getBean(def.beanName());
        Method method = bean.getClass().getMethod(def.methodName());

        return () -> {
            try {
                method.invoke(bean);
            } catch (Exception e) {
                if (e.getCause() instanceof RuntimeException re) throw re;
                throw new RuntimeException(e);
            }
        };
    }

    @Bean(destroyMethod = "shutdown")
    public TaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);

        // ContextPropagatingTaskDecorator : utile si du code en aval
        // délègue à un CompletableFuture qui change encore de thread
        scheduler.setTaskDecorator(new ContextPropagatingTaskDecorator());

        scheduler.initialize();
        return scheduler;
    }
}
```

### 11.3 Exemple de bean métier ciblé par YAML

```java
@Component("salesforceSyncJob")
@RequiredArgsConstructor
@Slf4j
public class SalesforceSyncJob {

    private final SalesforceClient client;
    private final ReviewRepository reviewRepository;

    public void run() {
        // pas besoin de @Scheduled — c'est le scheduler programmatique qui appelle
        log.info("Starting Salesforce sync");
        var reviews = reviewRepository.findPendingSync();
        for (var review : reviews) {
            client.upsert(review);
        }
        log.info("Synced {} reviews", reviews.size());
    }
}
```

### 11.4 Pourquoi `addTriggerTask` plutôt que `addCronTask`

`addCronTask(Runnable, String)` accepte directement la chaîne cron mais n’autorise pas la timezone. `addTriggerTask(Runnable, Trigger)` avec `CronTrigger(cron, TimeZone)` permet de fixer UTC explicitement, ce qui évite les surprises sur les serveurs avec timezone système différente.

-----

## 12. Outbox — propagation businessCorrelationId

### 12.1 Modèle

```java
package com.crok4it.outbox;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_entry")
@Getter
@NoArgsConstructor
public class OutboxEntry {

    @Id
    private UUID id;

    private String topic;
    private String key;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private String correlationId;
    private String sourceSystem;
    private Instant createdAt;
    private Instant dispatchedAt;
    private int version;

    public static OutboxEntry create(String topic, String key, String payload) {
        var e = new OutboxEntry();
        e.id = UUID.randomUUID();
        e.topic = topic;
        e.key = key;
        e.payload = payload;
        e.correlationId = org.slf4j.MDC.get("business.correlation.id");
        if (e.correlationId == null) e.correlationId = e.id.toString();
        e.sourceSystem = org.slf4j.MDC.get("business.source_system");
        e.createdAt = Instant.now();
        e.version = 1;
        return e;
    }

    public void markDispatched() {
        this.dispatchedAt = Instant.now();
    }
}
```

### 12.2 `OutboxDispatcher.java`

```java
package com.crok4it.outbox;

import com.crok4it.observability.kafka.TraceCorrelationInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component("outboxDispatcherJob")
@RequiredArgsConstructor
@Slf4j
public class OutboxDispatcher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final OutboxRepository repository;

    /**
     * Méthode appelée par le scheduler (référencée dans application.yaml).
     */
    @Transactional
    public void dispatchAll() {
        List<OutboxEntry> pending = repository.findUndispatchedOrderedByCreatedAt(100);
        if (pending.isEmpty()) return;

        log.info("Dispatching {} outbox entries", pending.size());
        for (OutboxEntry entry : pending) {
            dispatch(entry);
        }
    }

    private void dispatch(OutboxEntry entry) {
        // Restaure le contexte capturé à la publication
        MDC.put("business.correlation.id", entry.getCorrelationId());
        MDC.put("business.source_system", "OUTBOX");
        try {
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                entry.getTopic(), entry.getKey(), entry.getPayload());

            // Header pour préserver le correlationId à travers retry/DLT
            record.headers().add(
                TraceCorrelationInterceptor.BUSINESS_CORRELATION_HEADER,
                entry.getCorrelationId().getBytes(StandardCharsets.UTF_8));

            kafkaTemplate.send(record).whenComplete((md, ex) -> {
                if (ex == null) {
                    entry.markDispatched();
                    repository.save(entry);
                    log.debug("Dispatched outbox entry {}", entry.getId());
                } else {
                    log.error("Failed to dispatch outbox entry {}", entry.getId(), ex);
                }
            });
        } finally {
            MDC.remove("business.correlation.id");
            MDC.remove("business.source_system");
        }
    }
}
```

### 12.3 Schéma de séquence — Outbox + replay

```
Tx HTTP                Outbox                Scheduler            Kafka
   │                     │                       │                  │
   │ writeBusinessData   │                       │                  │
   │ + saveOutboxEntry──►│                       │                  │
   │ COMMIT              │ correlationId capturé │                  │
   │                     │ depuis MDC            │                  │
   │                     │                       │                  │
   │                     │                       │ tick (10s)       │
   │                     │◄──── findUndispatched─┤                  │
   │                     │                       │                  │
   │                     │ MDC.put(correlationId)│                  │
   │                     │                       │── send ─────────►│
   │                     │                       │   + header       │
   │                     │                       │     business-    │
   │                     │                       │     correlation  │
   │                     │ markDispatched        │                  │
   │                     │                       │                  │
   │                     │                       │                  ▼
   │                     │                       │            Consumer
   │                     │                       │            MDC restauré
   │                     │                       │            via interceptor
   │                     │                       │
   │                     │                       │
   │                     │                       │ Trace logique:
   │                     │                       │ • span "http.request" (t0)
   │                     │                       │ • span "outbox.dispatch" (t0+10s)
   │                     │                       │ • span "kafka.consume" (t0+10.1s)
   │                     │                       │ → trace.id différents
   │                     │                       │ → business.correlation.id IDENTIQUE
   │                     │                       │ → corrélation Kibana via correlation_id
```

-----

## 13. Batch — span par item

```java
package com.crok4it.observability.batch;

import com.crok4it.observability.error.BusinessException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchItemProcessor {

    private final ObservationRegistry registry;

    /**
     * Pour chaque item :
     *  - crée une Observation enfant → nouveau spanId, même traceId que le batch
     *  - met l'item.id en MDC (visible dans tous les logs émis pendant le traitement)
     *  - capture les erreurs item-level sans casser le batch
     *
     * Résultat : dans Kibana, on peut filtrer business.item_id="42" et
     * remonter exactement les logs liés à cet item, même au milieu de 10k autres.
     */
    public <T> BatchResult processBatch(String batchName, List<T> items,
                                        Function<T, String> idExtractor,
                                        Consumer<T> processor) {
        int processed = 0, failed = 0;

        for (T item : items) {
            String itemId = idExtractor.apply(item);
            MDC.put("business.item.id", itemId);

            try {
                Observation.createNotStarted("batch.item.process", registry)
                    .lowCardinalityKeyValue("batch.name", batchName)
                    .observe(() -> processor.accept(item));
                processed++;
            } catch (BusinessException be) {
                log.warn("Item {} skipped (business): {}", itemId, be.getMessage());
                failed++;
            } catch (Exception e) {
                log.error("Item {} failed (technical)", itemId, e);
                failed++;
            } finally {
                MDC.remove("business.item.id");
            }
        }

        log.info("Batch {} completed: {} processed, {} failed",
            batchName, processed, failed);
        return new BatchResult(processed, failed);
    }

    public record BatchResult(int processed, int failed) {}
}
```

-----

## 14. Classification d’erreurs & masquage PII

### 14.1 `ApplicationException.java`

```java
package com.crok4it.observability.error;

/**
 * Sealed hierarchy : oblige à classifier toute exception métier.
 * - BusinessException → log WARN, pas d'alerting Pager
 * - TechnicalException → log ERROR, déclenche alerting
 */
public sealed interface ApplicationException
        permits BusinessException, TechnicalException {

    String getMessage();
}

public final class BusinessException extends RuntimeException
        implements ApplicationException {

    public BusinessException(String message) {
        super(message);
    }
}

public final class TechnicalException extends RuntimeException
        implements ApplicationException {

    public TechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### 14.2 `PiiMaskingCustomizer.java`

```java
package com.crok4it.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;
import org.springframework.boot.logging.structured.json.JsonWriter.Members;

import java.util.regex.Pattern;

/**
 * ATTENTION : remplace le champ "message" par défaut.
 * Si tu utilises déjà EcsCustomFieldsCustomizer, fusionne les deux
 * en un seul customizer pour éviter les conflits d'ordre d'enregistrement.
 */
public class PiiMaskingCustomizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    private static final Pattern EMAIL = Pattern.compile(
        "([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");

    private static final Pattern CARD = Pattern.compile("\\b\\d{13,19}\\b");

    // Pattern IBAN simplifié : 2 lettres + 2 chiffres + 11-30 alphanumériques
    private static final Pattern IBAN = Pattern.compile(
        "\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b");

    @Override
    public void customize(Members<ILoggingEvent> members) {
        members.add("message", event -> mask(event.getFormattedMessage()));
    }

    private String mask(String input) {
        if (input == null || input.isEmpty()) return input;
        String masked = EMAIL.matcher(input).replaceAll("***@$2");
        masked = CARD.matcher(masked).replaceAll("****-MASKED-****");
        masked = IBAN.matcher(masked).replaceAll("IBAN-MASKED");
        return masked;
    }
}
```

### 14.3 Customizer combiné (recommandé)

Pour éviter les conflits, regroupe en un seul bean :

```java
public class CombinedEcsCustomizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    private final EcsCustomFieldsCustomizer fields = new EcsCustomFieldsCustomizer();
    private final PiiMaskingCustomizer pii = new PiiMaskingCustomizer();

    @Override
    public void customize(Members<ILoggingEvent> members) {
        fields.customize(members);
        pii.customize(members);
    }
}
```

Et dans `application.yaml` :

```yaml
logging.structured.json.customizer: com.crok4it.observability.logging.CombinedEcsCustomizer
```

-----

## 15. Healthcheck jobs

```java
package com.crok4it.observability.scheduling;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobsHealthIndicator implements HealthIndicator {

    private final Map<String, Instant> lastSuccess = new ConcurrentHashMap<>();
    private final Map<String, Duration> expectedFrequency = new ConcurrentHashMap<>();

    public void register(String jobName, Duration expectedFreq) {
        expectedFrequency.put(jobName, expectedFreq);
    }

    public void recordSuccess(String jobName) {
        lastSuccess.put(jobName, Instant.now());
    }

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        boolean degraded = false;
        Instant now = Instant.now();

        for (var entry : expectedFrequency.entrySet()) {
            String name = entry.getKey();
            Duration expected = entry.getValue();
            Instant last = lastSuccess.get(name);

            if (last == null) {
                // Tolérance startup : pas de DOWN tant que 2× la fréquence
                // n'est pas écoulée depuis le démarrage
                details.put(name, Map.of(
                    "status", "NEVER_RUN",
                    "expected_frequency_seconds", expected.toSeconds()));
                continue;
            }

            Duration since = Duration.between(last, now);
            if (since.compareTo(expected.multipliedBy(2)) > 0) {
                details.put(name, Map.of(
                    "status", "STALE",
                    "last_success_seconds_ago", since.toSeconds(),
                    "expected_frequency_seconds", expected.toSeconds()));
                degraded = true;
            } else {
                details.put(name, Map.of(
                    "status", "OK",
                    "last_success_seconds_ago", since.toSeconds()));
            }
        }

        return (degraded ? Health.down() : Health.up()).withDetails(details).build();
    }
}
```

-----

## 16. Tests d’intégration

### 16.1 `KafkaTracePropagationIT.java`

```java
package com.crok4it.observability.kafka;

import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = "test-topic")
class KafkaTracePropagationIT {

    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired Tracer tracer;
    @Autowired TestConsumer consumer;

    @Test
    void traceId_should_propagate_to_kafka_consumer() throws Exception {
        var span = tracer.nextSpan().name("test-producer").start();
        String expectedTraceId;

        try (var ws = tracer.withSpan(span)) {
            expectedTraceId = span.context().traceId();
            kafkaTemplate.send("test-topic", "key", "payload").get(5, TimeUnit.SECONDS);
        } finally {
            span.end();
        }

        boolean received = consumer.latch.await(5, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(consumer.lastTraceId.get())
            .as("Le trace.id du consumer doit être égal à celui du producer")
            .isEqualTo(expectedTraceId);
    }

    @Component
    static class TestConsumer {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> lastTraceId = new AtomicReference<>();

        @Autowired Tracer tracer;

        @KafkaListener(topics = "test-topic")
        void onMessage(ConsumerRecord<String, String> record) {
            var span = tracer.currentSpan();
            if (span != null) {
                lastTraceId.set(span.context().traceId());
            }
            latch.countDown();
        }
    }
}
```

### 16.2 `ObservedJobRunnerIT.java`

```java
package com.crok4it.observability.scheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ObservedJobRunnerIT {

    @Autowired ObservedJobRunner runner;
    @Autowired Tracer tracer;
    @Autowired MeterRegistry meterRegistry;

    @Test
    void wrapped_job_should_have_traceId_in_mdc() {
        var def = new JobDefinition(
            "test-job", "0 0 * * * *",
            Duration.ofSeconds(10), Duration.ZERO,
            false, "n/a", "n/a", Duration.ofMinutes(1));

        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedJobName = new AtomicReference<>();

        Runnable wrapped = runner.wrap(def, () -> {
            capturedTraceId.set(MDC.get("traceId"));
            capturedJobName.set(MDC.get("job.name"));
        });

        wrapped.run();

        assertThat(capturedTraceId.get())
            .as("Le wrapper doit créer un span → traceId présent en MDC")
            .isNotBlank();
        assertThat(capturedJobName.get()).isEqualTo("test-job");

        assertThat(meterRegistry.timer("scheduled.job.duration",
                "job.name", "test-job", "outcome", "executed").count())
            .isEqualTo(1);
    }

    @Test
    void failed_job_should_be_tagged_as_error() {
        var def = new JobDefinition(
            "failing-job", "0 0 * * * *",
            Duration.ofSeconds(10), Duration.ZERO,
            false, "n/a", "n/a", Duration.ofMinutes(1));

        Runnable wrapped = runner.wrap(def, () -> {
            throw new RuntimeException("boom");
        });

        wrapped.run(); // ne re-throw pas

        assertThat(meterRegistry.timer("scheduled.job.duration",
                "job.name", "failing-job", "outcome", "technical_error").count())
            .isEqualTo(1);
    }
}
```

-----

## 17. Infrastructure

### 17.1 Index template Elasticsearch

`infra/es/ecs-template.json` :

```json
{
  "index_patterns": ["microservices-*"],
  "priority": 500,
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 1,
      "index.refresh_interval": "5s",
      "index.lifecycle.name": "microservices-policy",
      "index.lifecycle.rollover_alias": "microservices"
    },
    "mappings": {
      "dynamic": "strict",
      "properties": {
        "@timestamp": { "type": "date" },
        "log": {
          "properties": {
            "level": { "type": "keyword" },
            "logger": { "type": "keyword" },
            "thread_name": { "type": "keyword" }
          }
        },
        "message": { "type": "text" },
        "trace": {
          "properties": {
            "id": { "type": "keyword" },
            "span_id": { "type": "keyword" }
          }
        },
        "service": {
          "properties": {
            "name": { "type": "keyword" },
            "version": { "type": "keyword" },
            "environment": { "type": "keyword" },
            "node": { "properties": { "name": { "type": "keyword" } } }
          }
        },
        "job": {
          "properties": {
            "name": { "type": "keyword" },
            "execution_id": { "type": "keyword" },
            "outcome": { "type": "keyword" }
          }
        },
        "business": {
          "properties": {
            "correlation_id": { "type": "keyword" },
            "tenant_id": { "type": "keyword" },
            "item_id": { "type": "keyword" },
            "source_system": { "type": "keyword" }
          }
        },
        "kafka": {
          "properties": {
            "topic": { "type": "keyword" },
            "partition": { "type": "integer" },
            "offset": { "type": "long" },
            "attempt": { "type": "integer" }
          }
        },
        "error": {
          "properties": {
            "type": { "type": "keyword" },
            "message": { "type": "text" },
            "stack_trace": { "type": "text" }
          }
        },
        "kubernetes": {
          "properties": {
            "namespace": { "type": "keyword" },
            "pod": { "properties": { "name": { "type": "keyword" } } },
            "container": { "properties": { "name": { "type": "keyword" } } },
            "node": { "properties": { "name": { "type": "keyword" } } }
          }
        }
      }
    }
  }
}
```

Bootstrap :

```bash
curl -X PUT "http://elastic:9200/_index_template/ecs-microservices" \
  -H 'Content-Type: application/json' \
  -d @infra/es/ecs-template.json
```

### 17.2 Filebeat DaemonSet

`infra/k8s/filebeat-daemonset.yaml` :

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: filebeat-config
  namespace: observability
data:
  filebeat.yml: |
    filebeat.inputs:
      - type: container
        paths:
          - /var/log/containers/*.log
        processors:
          - add_kubernetes_metadata:
              host: ${NODE_NAME}
              matchers:
                - logs_path:
                    logs_path: "/var/log/containers/"
          # CRITIQUE : décode le JSON émis par les apps
          - decode_json_fields:
              fields: ["message"]
              target: ""
              overwrite_keys: true
              add_error_key: true
              expand_keys: true
          # Drop des logs Kubernetes système qui ne sont pas du JSON
          - drop_event:
              when:
                and:
                  - has_fields: ["json.error"]
                  - regexp:
                      kubernetes.container.name: "^(kube-|coredns)"

    output.elasticsearch:
      hosts: ["${ELASTIC_HOSTS}"]
      index: "microservices-%{[kubernetes.namespace]}-%{+yyyy.MM.dd}"

    setup.template.enabled: false   # template géré séparément
    setup.ilm.enabled: false
---
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: filebeat
  namespace: observability
spec:
  selector:
    matchLabels:
      app: filebeat
  template:
    metadata:
      labels:
        app: filebeat
    spec:
      serviceAccountName: filebeat
      containers:
        - name: filebeat
          image: docker.elastic.co/beats/filebeat:8.13.0
          args: ["-c", "/etc/filebeat.yml", "-e"]
          env:
            - name: NODE_NAME
              valueFrom: { fieldRef: { fieldPath: spec.nodeName } }
            - name: ELASTIC_HOSTS
              value: "http://elastic.observability.svc.cluster.local:9200"
          volumeMounts:
            - name: config
              mountPath: /etc/filebeat.yml
              subPath: filebeat.yml
            - name: varlog
              mountPath: /var/log
              readOnly: true
            - name: varlibdockercontainers
              mountPath: /var/lib/docker/containers
              readOnly: true
      volumes:
        - name: config
          configMap: { name: filebeat-config }
        - name: varlog
          hostPath: { path: /var/log }
        - name: varlibdockercontainers
          hostPath: { path: /var/lib/docker/containers }
```

### 17.3 OTel Collector — tail-sampling

`infra/otel/collector-config.yaml` :

```yaml
receivers:
  otlp:
    protocols:
      http: { endpoint: 0.0.0.0:4318 }
      grpc: { endpoint: 0.0.0.0:4317 }

processors:
  batch:
    timeout: 5s
    send_batch_size: 512

  # Tail-sampling : on garde 100% des erreurs et 1% des succès
  tail_sampling:
    decision_wait: 10s
    num_traces: 100000
    expected_new_traces_per_sec: 100
    policies:
      - name: errors
        type: status_code
        status_code: { status_codes: [ERROR] }
      - name: slow
        type: latency
        latency: { threshold_ms: 1000 }
      - name: sampled
        type: probabilistic
        probabilistic: { sampling_percentage: 1 }

exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls: { insecure: true }

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [tail_sampling, batch]
      exporters: [otlp/tempo]
```

### 17.4 Convention naming

|Type           |Nom Observation              |Tags low-cardinality                  |À mettre en MDC   |
|---------------|-----------------------------|--------------------------------------|------------------|
|Job ShedLock   |`scheduled.job`              |`job.name`, `outcome`, `lock.acquired`|`job.execution.id`|
|Kafka consume  |`kafka.consume` (auto)       |`messaging.destination`, `outcome`    |`kafka.offset`    |
|Kafka produce  |`kafka.produce` (auto)       |`messaging.destination`               |—                 |
|Modulith event |`modulith.event.handle`      |`event.type`, `source.system`         |`event.id`        |
|Outbox dispatch|`outbox.dispatch`            |`topic`, `outcome`                    |`entry.id`        |
|Batch item     |`batch.item.process`         |`batch.name`, `outcome`               |`business.item.id`|
|HTTP entrant   |`http.server.requests` (auto)|`uri`, `method`, `status`             |path params       |

**Règle absolue** : tout tag à cardinalité élevée (UUID, offset, ID) va en MDC ou span attribute, **jamais** en `lowCardinalityKeyValue` — sinon explosion mémoire Prometheus.

-----

## 18. Exemples de logs JSON attendus

### 18.1 Job ShedLock exécuté avec succès

```json
{
  "@timestamp": "2026-04-24T08:15:32.421Z",
  "log.level": "INFO",
  "log.logger": "com.crok4it.observability.scheduling.ObservedJobRunner",
  "log.thread_name": "sched-3",
  "message": "Job salesforce-sync completed in 4231ms",
  "service": {
    "name": "my-service",
    "version": "1.4.0",
    "environment": "production",
    "node": { "name": "my-service-7d4f9c-x2k9p" }
  },
  "trace": {
    "id": "4bf92f3577b34da6a3ce929d0e0e4736",
    "span_id": "00f067aa0ba902b7"
  },
  "job": {
    "name": "salesforce-sync",
    "execution_id": "e1f4a7c2-9b8d-4f6e-a3c5-8d1e0b9a7f6c",
    "outcome": "executed"
  },
  "kubernetes": {
    "namespace": "production",
    "pod": { "name": "my-service-7d4f9c-x2k9p" },
    "node": { "name": "k3s-node-02" }
  }
}
```

### 18.2 Kafka consumer avec retry (3e tentative)

```json
{
  "@timestamp": "2026-04-24T08:15:35.102Z",
  "log.level": "WARN",
  "log.logger": "com.crok4it.review.ReviewEventListener",
  "message": "Review processing failed (business): Review #1234 not found in Salesforce",
  "service": { "name": "my-service", "version": "1.4.0" },
  "trace": {
    "id": "8a2c1d4e9f6b3a7c0d5e8f2a1b4c7d9e",
    "span_id": "1a2b3c4d5e6f7a8b"
  },
  "kafka": {
    "topic": "reviews.events",
    "partition": 2,
    "offset": 18432,
    "attempt": 3
  },
  "business": {
    "correlation_id": "ord-2026-04-24-001234",
    "tenant_id": "luxair"
  }
}
```

(Note : `business.correlation_id` est **identique** sur les 3 tentatives + le DLT, alors que `trace.id` change à chaque tentative.)

### 18.3 Replay Modulith

```json
{
  "@timestamp": "2026-04-24T08:15:42.811Z",
  "log.level": "INFO",
  "log.logger": "com.crok4it.review.ReviewSyncListener",
  "message": "Syncing review to downstream system",
  "service": { "name": "my-service", "version": "1.4.0" },
  "trace": {
    "id": "f1e2d3c4b5a6978869584736251403f2",
    "span_id": "abcdef0123456789"
  },
  "business": {
    "correlation_id": "ord-2026-04-23-009876",
    "source_system": "REPLAY",
    "tenant_id": "luxair"
  }
}
```

### 18.4 Job skippé (lock pris par autre node)

```json
{
  "@timestamp": "2026-04-24T08:16:00.005Z",
  "log.level": "DEBUG",
  "log.logger": "com.crok4it.observability.scheduling.ObservedJobRunner",
  "message": "Job skipped: lock held by another instance",
  "service": { "name": "my-service" },
  "trace": { "id": "...", "span_id": "..." },
  "job": {
    "name": "cleanup-stale-data",
    "execution_id": "...",
    "outcome": "skipped_locked"
  }
}
```

### 18.5 Erreur technique avec stack trace

```json
{
  "@timestamp": "2026-04-24T08:16:15.331Z",
  "log.level": "ERROR",
  "log.logger": "com.crok4it.observability.scheduling.ObservedJobRunner",
  "message": "Job outbox-dispatcher failed (technical)",
  "service": { "name": "my-service", "version": "1.4.0" },
  "trace": { "id": "...", "span_id": "..." },
  "job": {
    "name": "outbox-dispatcher",
    "execution_id": "...",
    "outcome": "technical_error"
  },
  "error": {
    "type": "org.springframework.dao.DataAccessResourceFailureException",
    "message": "Could not open JDBC connection",
    "stack_trace": "org.springframework.dao.DataAccessResourceFailureException: ...\n\tat org.springframework..."
  }
}
```

-----

## 19. Checklist de validation

|# |Critère                                                      |Comment vérifier                              |
|--|-------------------------------------------------------------|----------------------------------------------|
|1 |`trace.id` non vide sur log d’un `@KafkaListener`            |Envoyer un message → grep dans Kibana         |
|2 |`trace.id` identique entre producer et consumer              |Comparer 2 logs corrélés via le même message  |
|3 |`trace.id` non vide sur job ShedLock manuel                  |Attendre cron tick → grep                     |
|4 |`trace.id` non vide sur `@ApplicationModuleListener`         |Publier event → grep                          |
|5 |Replay Modulith → `business.source_system = "REPLAY"`        |Restart pod avec events en attente            |
|6 |Retry Kafka → même `business.correlation_id` sur N tentatives|Forcer une exception → grep `correlation_id`  |
|7 |DLT → header `business-correlation-id` préservé              |Forcer 5 retries → consulter DLT              |
|8 |Job dépassant `lockAtMostFor` → log WARN + counter           |Provoquer un job lent volontairement          |
|9 |Job skippé sans flood si `logSkips=false`                    |Démarrer 2 nodes + observer logs              |
|10|Email dans message → masqué dans Kibana                      |Logger un email → vérifier `***@domain`       |
|11|`service.name` présent sur 100% des logs                     |`count where service.name is null` doit être 0|
|12|`/actuator/health` DOWN si job stale                         |Stopper un job → attendre 2× expectedFreq     |
|13|Test `KafkaTracePropagationIT` vert                          |`mvn verify`                                  |
|14|Métrique `scheduled.job.duration` exposée Prometheus         |`curl /actuator/prometheus                    |
|15|Filebeat décode le JSON                                      |Pas de champ `json.error` dans Kibana         |
|16|Index template appliqué                                      |`GET /_index_template/ecs-microservices`      |
|17|Tail-sampling collector fonctionne                           |Vérifier ratio traces erreur/succès dans Tempo|

-----

## 20. Pièges & dépannage

|Symptôme                                                                  |Cause probable                                                |Fix                                                                   |
|--------------------------------------------------------------------------|--------------------------------------------------------------|----------------------------------------------------------------------|
|`trace.id` vide partout                                                   |Pas de `micrometer-tracing-bridge-otel` sur classpath         |§3                                                                    |
|`trace.id` vide seulement sur Kafka                                       |`setObservationEnabled(true)` non appelé sur factory          |§7.1                                                                  |
|`trace.id` vide seulement sur jobs                                        |Pas d’`Observation` créée dans le wrapper                     |§10                                                                   |
|`trace.id` vide sur `@Async`                                              |Pas de `ContextPropagatingTaskDecorator`                      |§8                                                                    |
|`trace.id` change entre producer et consumer                              |Header `traceparent` non propagé (clients custom non-Spring)  |Vérifier `setObservationEnabled(true)` côté producer                  |
|`trace.id` change au retry Kafka (attendu) mais perd la corrélation métier|`business-correlation-id` header non posé                     |§7.3 — `OutboundTraceHeaderProducer`                                  |
|Logs JSON arrivent en texte brut dans Kibana                              |Filebeat ne décode pas le JSON                                |§17.2 — `decode_json_fields`                                          |
|Champs ECS custom mappés en `text`                                        |Pas d’index template poussé                                   |§17.1                                                                 |
|Métriques Prometheus explosent                                            |Tag haute cardinalité (offset, ID) sur Observation            |Mettre en MDC, pas en `lowCardinalityKeyValue`                        |
|Job exécuté 2× par 2 nodes différents                                     |`lockAtMostFor` < durée réelle                                |Augmenter `lockAtMostFor` ou alerter sur `scheduled.job.lock.exceeded`|
|Replay Modulith perd contexte                                             |`AuditMetadata` non sérialisable JSON                         |`@JsonCreator` sur le record (§9.2)                                   |
|Customizer ECS pas appliqué                                               |Mauvaise classe dans `logging.structured.json.customizer`     |Vérifier FQN exact + classpath                                        |
|Coût Elasticsearch trop élevé                                             |Niveau DEBUG en prod + 100% sampling traces                   |Niveau INFO + tail-sampling §17.3                                     |
|`service.name` manquant sur premiers logs startup                         |Définition dans `application.yaml` au lieu de `bootstrap.yaml`|§5                                                                    |
|`outcome` MDC reste sur log suivant                                       |`MDC.remove()` oublié dans `finally`                          |Toujours `try/finally` autour MDC                                     |
|ShedLock skippe tout en local                                             |Contention sur `shedlock` table en JDBC                       |Vérifier index unique sur `name`                                      |

-----

## Annexes

### A. Ordre d’implémentation recommandé

1. **Dépendances pom.xml** + `application.yaml` minimal (§3, §4)
1. **Bootstrap & service identity** (§5)
1. **Customizers ECS** (§6) — vérifier en local que les logs sortent en JSON
1. **Kafka observation** (§7.1) — vérifier `trace.id` propagé sur un consumer simple
1. **AsyncConfig** (§8)
1. **ShedLock wrapper + SchedulingConfigurer** (§10, §11) — cœur du sujet
1. **Modulith replay** (§9)
1. **Outbox + retry/DLT corrélation** (§7.2-7.4, §12)
1. **Erreurs + masquage PII** (§14)
1. **Healthcheck + tests** (§15, §16)
1. **Infra ELK + OTel** (§17)

### B. Liens utiles

- [Spring Boot 3.4 Structured Logging Reference](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)
- [Micrometer Observation](https://docs.micrometer.io/micrometer/reference/observation.html)
- [Spring Modulith Observability](https://docs.spring.io/spring-modulith/reference/observability.html)
- [ShedLock](https://github.com/lukas-krecan/ShedLock)
- [Elastic Common Schema (ECS)](https://www.elastic.co/guide/en/ecs/current/index.html)
