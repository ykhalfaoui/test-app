# Exemple complet : Traçage (traceId) + Audit + Retry avec Spring Modulith et ThreadLocal

Ce fichier regroupe **toutes les implémentations** évoquées :
- Contexte technique (`TraceContext`, `TraceContextHolder`, `TraceContextScope`)
- Enveloppe d’événement (`CorrelatedEvent`)
- Bridge entre `@DomainEvents` et événements corrélés (`DomainEventCorrelationBridge`)
- Agrégat `Party` avec `@DomainEvents`
- Déclencheur externe asynchrone (`IncomingPartyEvent` + `PartyStartHandler`)
- Handler asynchrone `PartyCreated` qui crée les blocks (`PartyAsyncHandler`)
- Listener pour la création des blocks (optionnel) (`BlockCreationHandler`)
- Outbox (`OutboxItem`, `OutboxListener`, `OutboxJob`)
- Audit trail (`AuditEvent`, `AuditTrailListener`)
- Filtre HTTP optionnel pour initialiser le contexte à partir d’un appel REST

Les packages et imports sont indicatifs, à adapter selon ton projet.

---

## 1. Contexte technique : `TraceContext`, `TraceContextHolder`, `TraceContextScope`

```java
package com.example.infrastructure.trace;

import org.slf4j.MDC;

public record TraceContext(String traceId, String userId) {}
```

```java
package com.example.infrastructure.trace;

import org.slf4j.MDC;

public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CTX = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static TraceContext get() {
        return CTX.get();
    }

    public static void set(TraceContext context) {
        CTX.set(context);
        if (context != null) {
            MDC.put("traceId", context.traceId());
            MDC.put("userId", context.userId());
        } else {
            MDC.remove("traceId");
            MDC.remove("userId");
        }
    }

    public static void clear() {
        set(null);
    }
}
```

```java
package com.example.infrastructure.trace;

public final class TraceContextScope implements AutoCloseable {

    private final TraceContext previous;

    public TraceContextScope(String traceId, String userId) {
        this.previous = TraceContextHolder.get();
        TraceContextHolder.set(new TraceContext(traceId, userId));
    }

    public static TraceContextScope from(CorrelatedEvent<?> event) {
        return new TraceContextScope(event.traceId(), event.userId());
    }

    @Override
    public void close() {
        TraceContextHolder.set(previous);
    }
}
```

---

## 2. Enveloppe d’événements : `CorrelatedEvent<T>`

```java
package com.example.infrastructure.events;

public record CorrelatedEvent<T>(
        String traceId,
        String userId,
        T payload
) {}
```

---

## 3. Bridge `@DomainEvents` → `CorrelatedEvent`

Ce composant écoute **tous** les événements de domaine (émis par `@DomainEvents`)
et les republie automatiquement sous forme de `CorrelatedEvent` en utilisant le `TraceContextHolder`.

```java
package com.example.infrastructure.events;

import com.example.infrastructure.trace.TraceContext;
import com.example.infrastructure.trace.TraceContextHolder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class DomainEventCorrelationBridge {

    private final ApplicationEventPublisher publisher;

    public DomainEventCorrelationBridge(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    public void onDomainEvent(Object event) {
        // Évite de boucler si on reçoit déjà un CorrelatedEvent
        if (event instanceof CorrelatedEvent<?>) {
            return;
        }

        TraceContext ctx = Optional.ofNullable(TraceContextHolder.get())
                .orElseGet(() -> new TraceContext(UUID.randomUUID().toString(), "system"));

        CorrelatedEvent<Object> correlated = new CorrelatedEvent<>(
                ctx.traceId(),
                ctx.userId(),
                event
        );

        publisher.publishEvent(correlated);
    }
}
```

---

## 4. Agrégat `Party` avec `@DomainEvents`

### 4.1. Domain events

```java
package com.example.party.domain.events;

public record PartyCreated(String partyId) {}

public record BlockCreated(String partyId, String blockId) {}

public record AllBlocksCreated(String partyId) {}

public record SynchronizedBlock(String partyId) {}
```

### 4.2. Entité `Party`

```java
package com.example.party.domain;

import com.example.party.domain.events.AllBlocksCreated;
import com.example.party.domain.events.BlockCreated;
import com.example.party.domain.events.PartyCreated;
import com.example.party.domain.events.SynchronizedBlock;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import org.springframework.data.domain.AfterDomainEventPublication;
import org.springframework.data.domain.DomainEvents;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Entity
public class Party {

    @Id
    private String id;

    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    protected Party() {
    }

    private Party(String id) {
        this.id = id;
        domainEvents.add(new PartyCreated(id));
    }

    public static Party newParty(String id) {
        return new Party(id);
    }

    public String getId() {
        return id;
    }

    public void addBlock(String blockId) {
        // Logique métier de création de block
        domainEvents.add(new BlockCreated(this.id, blockId));
    }

    public void allBlocksCreated() {
        domainEvents.add(new AllBlocksCreated(this.id));
    }

    public void markSynchronized() {
        domainEvents.add(new SynchronizedBlock(this.id));
    }

    @DomainEvents
    protected Collection<Object> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    @AfterDomainEventPublication
    protected void clearDomainEvents() {
        domainEvents.clear();
    }
}
```

```java
package com.example.party.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyRepository extends JpaRepository<Party, String> {
}
```

---

## 5. Déclencheur externe asynchrone

Un événement externe démarre le flux (par exemple, un message venant d’un autre système).

```java
package com.example.party.application;

public record IncomingPartyEvent(String externalPartyId, String traceId) {}
```

```java
package com.example.party.application;

import com.example.party.domain.Party;
import com.example.party.domain.PartyRepository;
import com.example.infrastructure.trace.TraceContext;
import com.example.infrastructure.trace.TraceContextHolder;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class PartyStartHandler {

    private final PartyRepository partyRepository;

    public PartyStartHandler(PartyRepository partyRepository) {
        this.partyRepository = partyRepository;
    }

    @Async
    @EventListener
    public void onIncomingParty(IncomingPartyEvent event) {
        // 1. Initialiser le contexte parent
        String traceId = event.traceId() != null ? event.traceId() : java.util.UUID.randomUUID().toString();
        TraceContextHolder.set(new TraceContext(traceId, "system"));

        try {
            // 2. Créer le Party -> déclenche PartyCreated via @DomainEvents
            Party party = Party.newParty(event.externalPartyId());
            partyRepository.save(party);

        } finally {
            TraceContextHolder.clear();
        }
    }
}
```

---

## 6. Handler asynchrone `PartyCreated` qui crée les blocks

```java
package com.example.party.application;

import com.example.infrastructure.events.CorrelatedEvent;
import com.example.infrastructure.trace.TraceContextScope;
import com.example.party.domain.Party;
import com.example.party.domain.PartyRepository;
import com.example.party.domain.events.PartyCreated;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class PartyAsyncHandler {

    private final PartyRepository partyRepository;

    public PartyAsyncHandler(PartyRepository partyRepository) {
        this.partyRepository = partyRepository;
    }

    @Async
    @ApplicationModuleListener
    public void on(CorrelatedEvent<PartyCreated> event) {
        try (var ignored = TraceContextScope.from(event)) {

            String partyId = event.payload().partyId();
            Party party = partyRepository.findById(partyId).orElseThrow();

            // Création de plusieurs blocks (exemple)
            party.addBlock("block-1");
            party.addBlock("block-2");
            party.addBlock("block-3");
            party.allBlocksCreated();

            partyRepository.save(party);
            // => émet BlockCreated x3 + AllBlocksCreated
            // => DomainEventCorrelationBridge les enveloppe en CorrelatedEvent
        }
    }
}
```

*(Tu peux séparer la logique de création de blocks dans un autre handler si tu préfères, mais cet exemple reste cohérent.)*

---

## 7. Outbox : sauvegarde et traitement

### 7.1. Entité Outbox

```java
package com.example.party.outbox;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

@Entity
public class OutboxItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String traceId;
    private String userId;
    private String partyId;
    private String status; // PENDING, SENT, ERROR...
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
```

```java
package com.example.party.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxItem, Long> {

    List<OutboxItem> findTop100ByStatusOrderByCreatedAtAsc(String status);
}
```

### 7.2. Listener `AllBlocksCreated` → Outbox

```java
package com.example.party.outbox;

import com.example.infrastructure.events.CorrelatedEvent;
import com.example.infrastructure.trace.TraceContextScope;
import com.example.party.domain.events.AllBlocksCreated;
import org.springframework.modulith.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OutboxListener {

    private final OutboxRepository outboxRepository;

    public OutboxListener(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @ApplicationModuleListener
    public void on(CorrelatedEvent<AllBlocksCreated> event) {
        try (var ignored = TraceContextScope.from(event)) {

            OutboxItem item = new OutboxItem();
            item.setTraceId(event.traceId());
            item.setUserId(event.userId());
            item.setPartyId(event.payload().partyId());
            item.setStatus("PENDING");
            item.setCreatedAt(Instant.now());

            outboxRepository.save(item);
        }
    }
}
```

### 7.3. Job async Outbox → appel microservice → `SynchronizedBlock`

```java
package com.example.party.outbox;

import com.example.infrastructure.trace.TraceContext;
import com.example.infrastructure.trace.TraceContextHolder;
import com.example.party.domain.Party;
import com.example.party.domain.PartyRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxJob {

    private final OutboxRepository outboxRepository;
    private final PartyRepository partyRepository;
    private final RemotePartySyncClient remoteClient;

    public OutboxJob(OutboxRepository outboxRepository,
                     PartyRepository partyRepository,
                     RemotePartySyncClient remoteClient) {
        this.outboxRepository = outboxRepository;
        this.partyRepository = partyRepository;
        this.remoteClient = remoteClient;
    }

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        List<OutboxItem> pending = outboxRepository
                .findTop100ByStatusOrderByCreatedAtAsc("PENDING");

        for (OutboxItem item : pending) {
            TraceContextHolder.set(new TraceContext(item.getTraceId(), item.getUserId()));
            try {
                // Appel microservice externe (Salesforce, etc.)
                remoteClient.syncParty(item.getPartyId());

                item.setStatus("SENT");
                outboxRepository.save(item);

                // Émettre l'event de fin : SynchronizedBlock via l'agrégat
                Party party = partyRepository.findById(item.getPartyId()).orElseThrow();
                party.markSynchronized();
                partyRepository.save(party);
                // => @DomainEvents => SynchronizedBlock
                // => Bridge => CorrelatedEvent<SynchronizedBlock> avec le même traceId

            } catch (Exception ex) {
                item.setStatus("ERROR");
                outboxRepository.save(item);
                // Logging, gestion de retry, etc.
            } finally {
                TraceContextHolder.clear();
            }
        }
    }
}
```

```java
package com.example.party.outbox;

public interface RemotePartySyncClient {

    void syncParty(String partyId);
}
```

---

## 8. Audit trail

### 8.1. Entité d’audit

```java
package com.example.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

@Entity
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String traceId;
    private String eventType;
    private Instant timestamp;
    private String status; // SUCCESS / ERROR / ...

    public Long getId() {
        return id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

```java
package com.example.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
}
```

### 8.2. Listener d’audit global

```java
package com.example.audit;

import com.example.infrastructure.events.CorrelatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AuditTrailListener {

    private final AuditEventRepository auditRepo;

    public AuditTrailListener(AuditEventRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @EventListener
    public void on(CorrelatedEvent<?> event) {
        String type = event.payload().getClass().getSimpleName();

        AuditEvent ae = new AuditEvent();
        ae.setTraceId(event.traceId());
        ae.setEventType(type);
        ae.setTimestamp(Instant.now());
        ae.setStatus("SUCCESS");

        auditRepo.save(ae);
    }
}
```

---

## 9. Filtre HTTP optionnel pour initialiser le traceId

Si ton flux peut aussi venir d’un appel REST synchrone, tu peux initialiser le contexte à l’entrée du contrôleur via un `OncePerRequestFilter` :

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

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
public class TraceContextFilter extends HttpFilter {

    @Override
    protected void doFilter(HttpServletRequest request,
                            HttpServletResponse response,
                            FilterChain chain)
            throws IOException, ServletException {

        String traceId = Optional.ofNullable(request.getHeader("X-Trace-Id"))
                .orElse(UUID.randomUUID().toString());

        String userId = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .map(Authentication::getName)
                .orElse("system");

        TraceContextHolder.set(new TraceContext(traceId, userId));

        try {
            chain.doFilter(request, response);
        } finally {
            TraceContextHolder.clear();
        }
    }
}
```

---

## 10. Résultat attendu dans l’audit

Avec ce setup, pour un flux complet tu peux obtenir :

```text
trace_id │ event_type         │ timestamp               │ status
─────────┼────────────────────┼─────────────────────────┼────────
trace-123│ PartyCreated       │ 2025-11-13 10:00:00     │ SUCCESS
trace-123│ BlockCreated       │ 2025-11-13 10:00:01     │ SUCCESS
trace-123│ BlockCreated       │ 2025-11-13 10:00:02     │ SUCCESS
trace-123│ BlockCreated       │ 2025-11-13 10:00:03     │ SUCCESS
trace-123│ AllBlocksCreated   │ 2025-11-13 10:00:04     │ SUCCESS
trace-123│ OutboxListener     │ 2025-11-13 10:00:05     │ SUCCESS (si tu ajoutes un event technique)
trace-123│ RemotePartySync    │ 2025-11-13 10:00:10     │ SUCCESS (idem)
trace-123│ SynchronizedBlock  │ 2025-11-13 10:00:11     │ SUCCESS
```

Tu peux enrichir encore les events techniques (`OutboxSaved`, `MicroserviceCallDone`, etc.) en ajoutant des domain events spécifiques ou en loggant ces étapes d’une autre manière.

Tu peux maintenant :
- Copier-coller les classes dans ton projet,
- Adapter les packages, les noms de beans et les annotations,
- Et brancher ça sur Spring Modulith + Event Publication Registry pour gérer les retries sans perdre le `traceId`.
