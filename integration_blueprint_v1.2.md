# 🧱 Blueprint — Inbox to Salesforce Integration Flow (v1.2)

> **Audience:** Developers & Technical Leads  
> **Purpose:** Provide a technical reference and modular breakdown for implementing the Inbox → Handler → Outbox → Salesforce flow using **Spring Modulith** patterns, with a **separate Audit Trail feature**.  
> **Context:** KYC Remediation project — transactional and asynchronous event-driven orchestration.

---

## 🗓 Versioning
- **Version:** v1.2 (clarifies separation of *Handler*, *Outbox*, and *Audit Trail* stories)  
- **Date:** 2025-10-06  
- **Changes vs v1.1:** Explicitly separates Handler and Outbox responsibilities; Audit Trail confirmed as its own feature; adds per‑story anchors & details for better Jira linking.

---

## 🎯 Objective  
Define structure, boundaries, and design of the **Inbox → Handler → Outbox → Salesforce** chain.  
Guarantee reliable event processing, full auditability, and parallel development with clear ownership.

---

## 📊 Story Summary

| **Story Name** | **Purpose / Description** | **Key Responsibilities** | **Dependencies** |
|----------------|---------------------------|---------------------------|------------------|
| **Inbox Story** | Receive upstream events and persist them transactionally before business logic starts. | - Deserialize & validate incoming event<br>- Persist to `inbox` table<br>- Idempotency & retryable statuses<br>- Commit ack after persistence | None |
| **Handler Story** | Execute domain-specific business logic in response to persisted events; emit internal domain events. | - Load event from Inbox<br>- Business rules & enrichment<br>- Domain state changes (e.g., review creation)<br>- Emit **internal domain event** (`@DomainEvents`) | Inbox |
| **Outbox Story** | Implement **transactional outbox** to guarantee reliable delivery to external systems. | - Persist outbound messages to `outbox`<br>- Status machine & dedup<br>- Dispatcher with backoff & retry<br>- Idempotency key management | Handler |
| **Salesforce Integration Story** | Map and send outbox messages to Salesforce (upsert), with enrichment if needed. | - Build payloads<br>- Fetch enrichment from NODS/BDWH<br>- Call Salesforce API (Upsert)<br>- Handle errors/retries & map responses | Outbox |
| **Audit Trail Story** | Centralized audit logging across the chain (DB + ELK), as a **separate cross‑cutting feature**. | - Post‑commit structured audit logs<br>- Persist to `audit_log` table<br>- Async publish to ELK<br>- Correlate via `trace_id` | Inbox, Handler, Outbox, Salesforce |
| **E2E Test Story** | Validate full flow and audit consistency. | - Simulate inbound event<br>- Assert Inbox → Handler → Outbox → Salesforce<br>- Verify audit entries across steps<br>- Provide reproducible reference test | All above |

> Tip: In Jira, create one Epic “Inbox→Salesforce Integration” and child stories matching the table above. Link each story to the relevant section anchors below.

---

## 🧠 Design Overview

### <a id="sequence"></a>Sequence — Inbox → Handler → Outbox → Salesforce (with Audit Trail)

```plantuml
@startuml
title E2E Flow: Inbox → Handler → Outbox → Salesforce (Upsert + Audit)

actor Upstream as U
participant "Inbox Adapter\n(Kafka/REST)" as InAdapter
participant "InboxService" as Inbox
database "DB\n(inbox/outbox/audit)" as DB
participant "Handler\n(Domain App Service)" as Handler
participant "OutboxService\n(Dispatcher)" as Outbox
participant "Salesforce Integration\n(Client + Mapper)" as SFInt
entity "Salesforce API" as SF
participant "AuditLogger\n(DB + ELK)" as Audit

U -> InAdapter: 1) Send Hit event
InAdapter -> Inbox: validate + deserialize
box "TX-1 (atomic)"
  Inbox -> DB: insert inbox(event_id, payload, status=PERSISTED)
  Inbox -> Audit: log(event='inbox.persisted')
end box
InAdapter <-- Inbox: ack commit

Inbox -> Handler: onNewInboxEvent(event_id)
Handler -> DB: read inbox
Handler -> DB: domain updates (e.g., create review)
Handler -> Audit: log(event='handler.processed')
box "TX-2 (transactional outbox)"
  Handler -> DB: insert into outbox(key, type, payload, status=PENDING, retry=0)
end box

Outbox -> DB: fetch PENDING for update skip locked
Outbox -> SFInt: map + prepare payload
SFInt -> SF: upsert(payload, Idempotency-Key=outbox.key)
alt success
  Outbox -> DB: status=SENT, sent_at=now()
  Outbox -> Audit: log(event='outbox.sent')
else retryable
  Outbox -> DB: status=RETRYABLE, retry=retry+1, next_run_at=backoff()
  Outbox -> Audit: log(event='outbox.retryable')
else non-retryable
  Outbox -> DB: status=FAILED_NON_RETRYABLE, error=...
  Outbox -> Audit: log(event='outbox.failed')
end

Audit -> ELK: async publish (structured JSON)
@enduml
```

---

### <a id="component-view"></a>Component View — Module Boundaries

```plantuml
@startuml
title Component View (Spring Modulith Boundaries)
skinparam componentStyle rectangle

package "Inbound Layer" {
  [Inbox Adapter\n(Kafka/REST)] as C1
}

package "Application Layer" {
  [InboxService] as C2
  [Handler (Domain Service)] as C3
  [OutboxService (Dispatcher)] as C4
  [AuditLogger] as C5
}

package "Infrastructure" {
  database "DB\n(inbox, outbox, audit)" as DB
  [Salesforce Client] as SFClient
  [NODS Client] as NODSClient
  [ELK Connector] as ELK
}

package "External Systems" {
  [Salesforce API] as SF
  [NODS/BDWH] as NODS
  [ELK Stack] as ELKSys
}

C1 --> C2
C2 --> DB
C2 --> C3
C3 --> DB
C3 --> C4
C4 --> SFClient
SFClient --> SF
C5 --> DB
C5 --> ELK
ELK --> ELKSys
@enduml
```

---

### <a id="outbox-state"></a>Outbox Message Status Lifecycle

```plantuml
@startuml
title Outbox Message Status Lifecycle
[*] --> PENDING
PENDING --> SENDING : dispatcher picks
SENDING --> SENT : success
SENDING --> RETRYABLE : recoverable error
SENDING --> FAILED_NON_RETRYABLE : non-recoverable
RETRYABLE --> SENDING : next_run_at reached
RETRYABLE --> DEAD_LETTERED : retries > maxRetries
FAILED_NON_RETRYABLE --> DEAD_LETTERED
@enduml
```

---

## 🧾 Table Definitions

### <a id="inbox-table"></a>Inbox Table
| **Column** | **Type** | **Description** |
|-------------|-----------|----------------|
| `id` | UUID | Unique record identifier |
| `event_type` | VARCHAR(100) | Event class/type |
| `payload` | JSONB | Serialized event data |
| `status` | VARCHAR(30) | PENDING / PROCESSED / FAILED |
| `retry_count` | INT | Number of processing retries |
| `trace_id` | VARCHAR(255) | Correlation ID |
| `error_message` | TEXT | Last known error |
| `created_at` | TIMESTAMP | Creation time |
| `updated_at` | TIMESTAMP | Last update time |

### <a id="outbox-table"></a>Outbox Table
| **Column** | **Type** | **Description** |
|-------------|-----------|----------------|
| `id` | UUID | Unique identifier |
| `type` | VARCHAR(100) | Event type |
| `payload` | JSONB | Serialized outbound message |
| `target_system` | VARCHAR(50) | e.g., “SALESFORCE” |
| `status` | VARCHAR(30) | PENDING / SENT / FAILED / RETRYABLE |
| `key` | VARCHAR(255) | Idempotency key (hash of source + type + businessId) |
| `retry_count` | INT | Retry attempts |
| `next_run_at` | TIMESTAMP | Next retry time |
| `sent_at` | TIMESTAMP | Sent timestamp |
| `trace_id` | VARCHAR(255) | Correlation ID |
| `error_message` | TEXT | Error details |

### <a id="audit-table"></a>Audit Table
| **Column** | **Type** | **Description** |
|-------------|-----------|----------------|
| `id` | UUID | Unique identifier |
| `trace_id` | VARCHAR(255) | Correlation ID |
| `entity` | VARCHAR(100) | e.g., inbox, handler, outbox, integration |
| `action` | VARCHAR(100) | e.g., persisted, processed, sent, failed |
| `status` | VARCHAR(50) | SUCCESS / FAILURE |
| `payload` | JSONB | Context snapshot |
| `timestamp` | TIMESTAMP | Log timestamp |
| `error` | TEXT | Optional error message |

---

## 🔗 Story Details & Anchors (for Jira)

### <a id="inbox-story"></a>Inbox Story
**Goal:** Transactionally receive and persist incoming events.  
**Blueprint refs:** [Sequence](#sequence), [Inbox Table](#inbox-table).  
**Acceptance (excerpt):** Persist to `inbox`, idempotency, `inbox.persisted` audit.

### <a id="handler-story"></a>Handler Story
**Goal:** Apply domain rules and emit internal domain events.  
**Blueprint refs:** [Sequence](#sequence), [Component View](#component-view).  
**Acceptance (excerpt):** Domain updates, emit `@DomainEvents`, `handler.processed` audit.

### <a id="outbox-story"></a>Outbox Story
**Goal:** Transactional outbox with dispatcher, retries, and idempotency.  
**Blueprint refs:** [Sequence](#sequence), [Outbox State](#outbox-state), [Outbox Table](#outbox-table).  
**Acceptance (excerpt):** Create outbox record, dispatcher/backoff, status machine, audit on send/fail.

### <a id="sf-integration-story"></a>Salesforce Integration Story
**Goal:** Map & upsert to Salesforce, handling errors and enrichment.  
**Blueprint refs:** [Sequence](#sequence), [Component View](#component-view).  
**Acceptance (excerpt):** Proper payload, enrichment, SENT/RETRYABLE/FAILED handling, idempotency key.

### <a id="audit-story"></a>Audit Trail Story
**Goal:** Post‑commit structured audit logging to DB + ELK.  
**Blueprint refs:** [Sequence](#sequence), [Audit Table](#audit-table), [Component View](#component-view).  
**Acceptance (excerpt):** Audit record per step with `trace_id`; async ELK publish; minimal perf impact.

### <a id="e2e-story"></a>E2E Test Story
**Goal:** Validate full chain and audit consistency.  
**Blueprint refs:** [Sequence](#sequence).  
**Acceptance (excerpt):** Golden path + retryable and non‑retryable scenarios; ELK trace by `trace_id`.

---

## ✅ Deliverables

- Inbox, Handler, Outbox, Salesforce, **Audit** modules implemented  
- Transactional guarantees (Inbox/Outbox) and idempotency keys in place  
- Audit events persisted in DB and exported to ELK  
- E2E test verifying full functional chain and audit visibility

---

**Author:** Architecture Team  
**Version:** v1.2  
**Date:** 2025-10-06  
