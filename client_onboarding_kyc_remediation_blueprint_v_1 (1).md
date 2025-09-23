# Client Onboarding – KYC Remediation Blueprint (v1)

**Project:** KYC Remediation (Java + Oracle, Kafka, Spring Modulith, OSS-only)  
**Feature:** Client Onboarding  
**Version:** v1 (22-Sep-2025)  
**Owner:** Crok4IT

---

## 1) Scope & Non‑Goals

### In Scope

* Consume upstream **PartyDTO** events from **Kafka** sent by `party-ms`.
* **Validate & filter** events: if the **Party** already exists **with the same status & type**, **skip** (no-op, idempotent).
* Compute and apply operations:
  1. **New customer**
  2. **Role change: CLIENT → THIRD_PARTY**
  3. **Role change: THIRD_PARTY → CLIENT**
  4. **Customer exits bank** (status update only; **no block impact**)
* **Upsert Party** (a.k.a. Customer) in Oracle.
* Create **KYC Block(s)** **for the customer only** (no family in this feature).
* Publish **integration intents** captured via transactional **Outbox HTTP** (same DB tx).
* **Project to Salesforce** (create/update a single SF record per `(party, blockType)`), with **retries**, **coalescing**, and **reconciliation**.
* Observability: metrics, logs, traces, **audit**.

### Out of Scope (v1)
* Family orchestration (may come later).
* Full sanctions/case review workflows (covered by HIT/Review features).
* Historical backfill/migration.

---

## 2) Architecture Overview

**Pattern mix:** **Kafka Inbox** (persist inbound before processing) + **Orchestrator** service + **Transactional Outbox (HTTP)** for **Salesforce only**. Internal domain events remain *in‑process*; **Kafka is only used inbound** (no outbound Kafka).

```
[party-ms]
  └─► Kafka: topic party.dto.v1 (key = partyId)
               │
        [Onboarding Consumer]
               │  (persist to INBOX + commit)
               ▼
          ORACLE.TABLE: INBOX
               │ (poll)
               ▼
    [Onboarding Orchestrator]
      ├─ Validation/Idempotency (skip if same status+type)
      ├─ Qualification Rules
      ├─ Upsert Party + Create/Version 7 Blocks (single DB tx)
      └─ **Publish IntegrationIntents (in‑process)**
               │  (captured **BEFORE_COMMIT**)
               ▼
          ORACLE.TABLE: OUTBOX_HTTP (target = Salesforce)
               │
               ▼
        [HTTP Outbox Relayer]
               │  (retry/backoff, idempotent upsert, confirm-on-unknown)
               ▼
          Salesforce API (REST/Bulk/Composite)
```

**Why this works:**
* **Outbox (HTTP)** captures the *intention* to integrate with SF **in the same DB transaction** as domain changes.
* A **relayer** asynchronously pushes to SF (decoupled), with **retries** and **idempotence** via ExternalId.

### 2.1) Transactional Event Wiring
* The orchestrator publishes **IntegrationIntents** via `ApplicationEventPublisher`.
* Module `integration.outbox` listens with `@TransactionalEventListener(phase = BEFORE_COMMIT)` and **writes** `OUTBOX_HTTP` rows **in the same transaction** (no network calls here).
* **After commit**, the **relayer** reads `OUTBOX_HTTP` (`status=PENDING`) and performs SF HTTP calls.
* **Guards:** coalescing by `(ExternalId, version)`, `projection_lock`, `expectedVersion` checks, and `sf_version` updated on SF success.

---

## 3) Bounded Contexts (DDD)

* **Onboarding BC**: orchestrates the workflow from PartyDTO to Party+Blocks changes.
* **Party BC**: owns the **Party** aggregate (identity, type/subType/status).
* **KYC Block BC**: owns **Block** versions (state machine, deadlines, required docs) and historization.
* **Integration BC**: **Kafka inbound** consumer for PartyDTO, **Outbox HTTP**, Salesforce client/projection.
* **Reference Data BC**: country codes, risk thresholds, document types (read‑only for others).

Module-level dependencies are **incoming → Onboarding → Party → Block → Integration**; Reference Data is read-only for all.

---

## 4) Domain Model (simplified)

**Aggregates & Entities**

* **Party**: `party_id`, `type` (`CLIENT|THIRD_PARTY`), `subType` (`IN|LE`), `status` (`ACTIVE|INACTIVE|CLOSED`), timestamps.
* **Block**: `block_id`, `party_id`, `block_type` (`STATIC_DATA`, `ID_DOCUMENTS`, …), `version`, `is_current`, `scope` (`IN_SCOPE|OUT_OF_SCOPE`), `state` (`INITIALIZED|…|CLOSED`), `projection_lock`, `sf_version`, `sf_id?`, `sf_hash?`, SLA/timestamps.

**Value Objects**: Address, RiskScore, DocumentRequirement, IdempotencyKey.

**Invariants**

* At most **one current** row per `(party_id, block_type)`.
* Idempotency: `INBOX.message_id` unique; **skip** when Party already has **same** `(status,type)`; `PROCESSED_MESSAGE` prevents replays.

---

## 5) Integration Intents & Contracts (no outbound Kafka)

### 5.1 Internal intents (in‑process)
Expose under `onboarding.api.events.*`:

* `NewCustomerIntent` — first creation of a Party as customer.
* `CustomerRoleChangedIntent { fromRole, toRole }`.
* `CustomerExitedIntent`.
* `BlocksBatchCreatedIntent { blocks[] }`.
* `BlocksBatchReplacedIntent { closed[], created[] }`.

### 5.2 Outbox Transaction Strategy (intent **in the same transaction**)

* **Capture**: a `@TransactionalEventListener(BEFORE_COMMIT)` maps each intent to one or more **`OUTBOX_HTTP`** rows (full **snapshot** payload, idempotent).
* **Coalescing**: while a row is **unpublished**, keep **updating** its payload to the latest snapshot (no SF spam).
* **Ordering**: `event_group_id` + `publish_order` define logical order (e.g., `role_changed` before `blocks.batch_*`).
* **Idempotence**: unique `event_id` + stable **SF ExternalId** (e.g., `partyId#blockType`).

### 5.3 Salesforce

* **One SF record per `(party, blockType)`**; `BlockVersion__c` stores the **latest projected** local version.
* **Upsert idempotent** via ExternalId (`Customer_ExternalId__c`, `Block_ExternalId__c = partyId#blockType`).
* `PayloadHash__c` (SHA‑256) allows **exact sync validation**.

---

## 5.1) Block Catalog & Default Status Matrix

Below are the **7 blocks** created for every onboarding, with **default statuses** per role. The *third‑party pertinence* is policy‑driven and configurable; defaults are provided for v1.

| Block Type                | Purpose                       | Default (CLIENT) | Default (THIRD_PARTY) |
|--------------------------|-------------------------------|------------------|------------------------|
| `STATIC_DATA`             | Identity & master data        | `INITIALIZED`    | `INITIALIZED`          |
| `ID_DOCUMENTS`            | ID docs capture/verification  | `INITIALIZED`    | `INITIALIZED`          |
| `ADDRESS_PROOF`           | Proof of address              | `INITIALIZED`    | `OUT_OF_SCOPE`         |
| `PEP_SANCTIONS_SCREENING` | Screening hook/result storage | `INITIALIZED`    | `INITIALIZED`          |
| `RISK_RATING`             | Initial risk scoring          | `INITIALIZED`    | `INITIALIZED`          |
| `TAX_COMPLIANCE`          | FATCA/CRS collection          | `INITIALIZED`    | `OUT_OF_SCOPE`         |
| `KYC_QUESTIONNAIRE`       | Purpose & activity profile    | `INITIALIZED`    | `OUT_OF_SCOPE`         |

> Status domain: `INITIALIZED`, `IN_PROGRESS`, `COMPLETED`, `OUT_OF_SCOPE`, `FAILED`, `CLOSED`.

---

## 6) Oracle Data Model (DDL Sketch)

```sql
CREATE TABLE INBOX (
  id              NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  topic           VARCHAR2(200) NOT NULL,
  partition_id    NUMBER NOT NULL,
  offset          NUMBER NOT NULL,
  message_id      VARCHAR2(64) NOT NULL,
  key_hash        VARCHAR2(64) NOT NULL,
  payload         CLOB NOT NULL,
  status          VARCHAR2(30) CHECK (status IN ('RECEIVED','PROCESSING','DONE','NON_RETRYABLE','RETRY','PARKED')),
  attempts        NUMBER DEFAULT 0,
  error_code      VARCHAR2(64),
  error_details   CLOB,
  received_at     TIMESTAMP DEFAULT SYSTIMESTAMP,
  UNIQUE (topic, partition_id, offset),
  UNIQUE (message_id)
);

CREATE TABLE PARTY (
  party_id    VARCHAR2(64) PRIMARY KEY,
  type        VARCHAR2(20) CHECK (type IN ('CLIENT','THIRD_PARTY')) NOT NULL,
  sub_type    VARCHAR2(20) CHECK (sub_type IN ('IN','LE')) NOT NULL,
  status      VARCHAR2(20) CHECK (status IN ('ACTIVE','INACTIVE','CLOSED')) DEFAULT 'ACTIVE' NOT NULL,
  created_at  TIMESTAMP DEFAULT SYSTIMESTAMP,
  updated_at  TIMESTAMP
);

CREATE TABLE BLOCK (
  block_id        VARCHAR2(64) PRIMARY KEY,
  party_id        VARCHAR2(64) NOT NULL,
  block_type      VARCHAR2(40) NOT NULL,
  version         NUMBER DEFAULT 1 NOT NULL,
  is_current      CHAR(1) CHECK (is_current IN ('Y','N')) DEFAULT 'Y' NOT NULL,
  scope           VARCHAR2(20) CHECK (scope IN ('IN_SCOPE','OUT_OF_SCOPE')) NOT NULL,
  state           VARCHAR2(20) CHECK (state IN ('INITIALIZED','IN_PROGRESS','COMPLETED','OUT_OF_SCOPE','FAILED','CLOSED')) NOT NULL,
  state_reason    VARCHAR2(200),
  required_tasks  CLOB,
  sla_due_at      TIMESTAMP,
  valid_from      TIMESTAMP DEFAULT SYSTIMESTAMP,
  valid_to        TIMESTAMP,
  projection_lock CHAR(1) CHECK (projection_lock IN ('Y','N')) DEFAULT 'N' NOT NULL,
  sf_version      NUMBER DEFAULT 0 NOT NULL,
  sf_id           VARCHAR2(18),
  sf_hash         VARCHAR2(64),
  created_at      TIMESTAMP DEFAULT SYSTIMESTAMP,
  CONSTRAINT FK_BLOCK_PARTY FOREIGN KEY (party_id) REFERENCES PARTY(party_id)
);

/* At most one current per (party, type) */
CREATE UNIQUE INDEX UX_BLOCK_CURRENT ON BLOCK (
  party_id,
  block_type,
  CASE WHEN is_current = 'Y' THEN 1 ELSE NULL END
);

/* OUTBOX for Salesforce HTTP */
CREATE TABLE OUTBOX_HTTP (
  id               NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  event_id         VARCHAR2(64) UNIQUE NOT NULL,
  event_type       VARCHAR2(100) NOT NULL,
  resource_type    VARCHAR2(50) NOT NULL,
  resource_key     VARCHAR2(100) NOT NULL, -- e.g., ExternalId
  http_method      VARCHAR2(10) CHECK (http_method IN ('POST','PUT','PATCH','DELETE')) NOT NULL,
  http_path        VARCHAR2(200) NOT NULL,
  payload          CLOB NOT NULL,
  block_version    NUMBER,
  payload_hash     VARCHAR2(64),
  event_group_id   VARCHAR2(64),
  publish_order    NUMBER,
  retries          NUMBER DEFAULT 0,
  last_error       CLOB,
  status           VARCHAR2(20) CHECK (status IN ('PENDING','SENT','SUCCEEDED','FAILED','STALE')) DEFAULT 'PENDING' NOT NULL,
  created_at       TIMESTAMP DEFAULT SYSTIMESTAMP,
  published_at     TIMESTAMP
);

CREATE TABLE PROCESSED_MESSAGE (
  message_id      VARCHAR2(64) PRIMARY KEY,
  processed_at    TIMESTAMP DEFAULT SYSTIMESTAMP
);

/* Durable audit (written in the same TX via BEFORE_COMMIT listener) */
CREATE TABLE AUDIT_EVENT (
  id              NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  event_id        VARCHAR2(64) UNIQUE NOT NULL,
  occurred_at     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  actor_id        VARCHAR2(64),
  actor_type      VARCHAR2(40),
  source_system   VARCHAR2(64),
  correlation_id  VARCHAR2(64),
  entity_type     VARCHAR2(40) NOT NULL,
  entity_id       VARCHAR2(64) NOT NULL,
  entity_version  NUMBER,
  action          VARCHAR2(40) NOT NULL,  -- CREATE/UPDATE/CLOSE/ROLE_CHANGE/EXIT/...
  payload         CLOB,
  payload_hash    VARCHAR2(64),
  status          VARCHAR2(20) CHECK (status IN ('PENDING','LOGGED','FAILED')) DEFAULT 'PENDING' NOT NULL,
  attempts        NUMBER DEFAULT 0,
  last_error      CLOB
);

CREATE INDEX IDX_INBOX_STATUS ON INBOX(status);
CREATE INDEX IDX_OUTBOX_HTTP_CREATED_AT ON OUTBOX_HTTP(created_at);
CREATE INDEX IDX_BLOCK_PARTY ON BLOCK(party_id, block_type, is_current);
CREATE INDEX IDX_AUDIT_ENTITY ON AUDIT_EVENT(entity_type, entity_id);
CREATE INDEX IDX_AUDIT_STATUS ON AUDIT_EVENT(status, occurred_at);
```

---

## 7) Process Flows by Case (Inbound Kafka, Outbound HTTP)
**Common steps** (Inbox, validation, idempotency). The orchestrator branches on `case_type` and publishes **IntegrationIntents**.

- **New customer** → upsert PARTY, create 7 blocks → `NewCustomerIntent` + `BlocksBatchCreatedIntent` → **OUTBOX_HTTP** rows → **Relayer** upserts SF (idempotent).
- **CLIENT → THIRD_PARTY** → close current versions, create 7 new ones → `CustomerRoleChangedIntent` + `BlocksBatchReplacedIntent` → OUTBOX_HTTP → SF.
- **THIRD_PARTY → CLIENT** → same pattern.
- **Exit** → set `status='CLOSED'` (or specific lifecycle), no block changes → `CustomerExitedIntent` → OUTBOX_HTTP → SF.

## 7.5) Historization, Projection Gate & Concurrency
**Write‑fence / Projection gate**
- On create/update, set `projection_lock='Y'` for the current version.
- The orchestrator **rejects** mutations if:
  1) `sf_version < version` (projection behind), **or**
  2) there exist **unpublished** `OUTBOX_HTTP` rows for the key,
  **except** for authorized **review** edits which coalesce into the **same snapshot**.
- On SF success: set `sf_version = version`, `sf_hash = payload_hash`, `projection_lock='N'`.

**Edit contract & versions**
- Commands carry `expectedVersion`; reject with `STALE_VERSION` if `< version`.
- During **review**, edits allowed only on the **current version**; changes coalesce into **one** `OUTBOX_HTTP` snapshot.

**Coalescing**
- Multiple quick edits ⇒ **one** OUTBOX_HTTP row updated until publish.

**SF Idempotence**
- Stable ExternalId (e.g., `partyId#blockType`). SF upsert is idempotent; controlled with `BlockVersion__c` & `PayloadHash__c`.

---

## 8) Failure Modes & Recovery
**Error classes**
- *Transient*: network, DB locks, **Salesforce** 5xx/429 → **RETRY** with backoff (e.g., 5, 30, 120s, capped).
- *Business*: invalid data (missing required), disallowed residency/type → **PARKED** with reasons (ops triage).
- *Non‑retryable technical*: schema invalid/unknown version → mark **NON_RETRYABLE** and raise ops alert.

**Policies**
- Max attempts N (e.g., 10); then park for manual triage with a **Reprocess** admin endpoint.
- **Reconciliation job** cross-checks SF (`BlockVersion__c`, `PayloadHash__c`) vs DB and requeues if drift.

---

## 9) Idempotency & Ordering
- **Inbound**: Kafka key = `partyId` preserves per-party order; `INBOX.message_id` unique; skip if same `(status,type)`.
- **Blocks**: uniqueness via `UX_BLOCK_CURRENT` (one current per `(party, block_type)`), version bump on role change.
- **Outbox ordering**: `event_group_id` + `publish_order` ensures `role_changed` before `blocks.batch_*`; publisher sorts by `(created_at, event_group_id, publish_order, id)`.
- **Publisher idempotence**: `event_id` is the dedupe key; **SF** idempotence relies on ExternalId.

---

## 10) Spring Modulith Structure
```
com.company.kyc
├─ onboarding
│  ├─ api        // intents, DTOs
│  ├─ app        // orchestrators, services (transactions)
│  ├─ domain     // aggregates, policies
│  └─ infra      // inbox repo, mappers
├─ party
├─ block
└─ integration
   ├─ kafka-inbox     // inbound consumer only
   ├─ outbox-http     // OUTBOX_HTTP repo + relayer
   └─ salesforce      // client, mappers, reconciliation
```
**Modulith** events are in‑JVM only; only **SF HTTP** is used for integration.

---

## 11) Interfaces & Contracts (API + Events + SF HTTP)
### 11.1 Ops API (Runbook)
- `POST /ops/outbox/{id}/reprocess` — requeue a failed OUTBOX_HTTP row.
- `GET /ops/outbox?status=PENDING|FAILED|STALE` — support view.
- `POST /ops/block/{id}/project` — force SF projection (high priority) if `sf_version < version`.
- `GET /ops/sf/drift?partyId=...` — run targeted SOQL validation and return diff (version/hash).

### 11.2 Internal intents (contracts)
- `NewCustomerIntent(partyId, snapshot)`
- `CustomerRoleChangedIntent(partyId, fromRole, toRole)`
- `BlocksBatchCreatedIntent(partyId, blocks[])`
- `BlocksBatchReplacedIntent(partyId, closed[], created[])`

### 11.3 Outbox Listener (BEFORE_COMMIT)
- Signature: `void on(Intent e)`; deterministic mapping → `OUTBOX_HTTP` rows.
- **Forbidden**: any network I/O; **Allowed**: local DB, serialization.

### 11.4 Salesforce HTTP
- **Upsert**: `PATCH /sobjects/Block__c/ExternalId/{externalId}` (or Composite/Bulk for volume).
- Payload: business fields + `BlockVersion__c` + `PayloadHash__c`.
- **Retry/backoff** in the relayer; **confirm-on-unknown** (GET by ExternalId) after timeouts.

---

## 12) Qualification Rules (v1)
- **Completeness**: minimal attributes present.
- **Residency/type whitelist/blacklist** via RefData.
- **Risk pre-score**: LOW/MED/HIGH placeholder (pluggable policy).
- Start with Java policies; consider **Drools**/**Easy Rules** later.

---

## 13) Security & Compliance
- **PII**: Oracle TDE + app-level encryption if needed.
- **Access control**: Spring Security; dedicated technical accounts for SF.
- **Audit Trail (dedicated module)**
  - **Emit** enriched **AuditIntent** (actor, action, entity, version, diff/hash, corId…).
  - **Durability**: `@TransactionalEventListener(BEFORE_COMMIT)` persists **`AUDIT_EVENT`** in the **same transaction**.
  - **Async shipping**: post-commit job (or `AFTER_COMMIT`) writes to logs/SIEM, marks `LOGGED`; retries on failure.
  - **Validation**: `payload_hash` must match GO snapshot; SF stores `PayloadHash__c` for SF↔GO coherence.
- **Retention**: TTL on INBOX/OUTBOX_HTTP (e.g., 30/7 days); **AUDIT_EVENT** kept per KYC policy (≥ 5 years).

---

## 14) Observability
- **Metrics** (Micrometer/OpenTelemetry):
  - `onboarding.received.count`, `processed.count`, `failed.count`, `parked.count`.
  - Latency (end-to-end, per stage), retry counters, attempts histogram.
  - SF projection success/error, reconciliation drift count.
- **Tracing**: traceId from inbound Kafka → DB tx → outbox → SF call.
- **Logging**: structured with keys (`messageId`, `partyId`, `blockId`).

---

## 15) Performance & Sizing (Guidelines)
- Inbound throughput = partitions × consumer throughput; start with **6–12** partitions; tune.
- INBOX/OUTBOX_HTTP indexed; poll batches (e.g., 100 rows) with fair scheduling per partition.
- Pool sizing (Hikari) aligned with consumer concurrency.

---

## 16) Testing Strategy
- **Contract tests** for PartyDTO schema (Registry/Pact optional).
- **Component tests**: consumer → inbox → orchestrator → outbox (Testcontainers: **Kafka + Oracle XE**), SF via **WireMock**.
- **E2E** in staging; **replay** tests from stored fixtures.
- **Chaos** drills: SF failures, DB locks, poison messages.

---

## 17) Rollout Plan
- Dark launch consumer (read + inbox, processing disabled) to validate volume/shape.
- Enable processing for a subset of partitions.
- Enable outbox relayer.
- Enable Salesforce projection last; keep reconciliation job to compare SF vs DB.

---

## 18) Backlog (v1)
1. Kafka consumer + INBOX repo (Oracle).
2. Party upsert & validation (skip same status+type).
3. Qualification policies (config‑driven).
4. Block creation/versioning service + unique/index constraints.
5. OUTBOX_HTTP repository + idempotent relayer (confirm‑on‑unknown).
6. Intents & listener (BEFORE_COMMIT) + coalescing.
7. Salesforce connector + retries + idempotent ExternalId.
8. Ops endpoints + dashboards (Grafana/Prometheus).
9. Purge jobs (INBOX/OUTBOX_HTTP) + optional cold storage export.
10. Documentation & runbook.

---

## 19) Open Questions
- Exact policy for **family** (future scope).
- Which Salesforce objects/fields to update and mapping details.
- Target SLA and acceptable retry windows.
- PartyDTO schema evolution strategy.

---

## 20) Appendix – Code Sketches
### 20.1 Kafka Consumer (Inbox Persist + Ack)
```java
@KafkaListener(topics = "party.dto.v1", groupId = "kyc-onboarding-v1")
public void onMessage(ConsumerRecord<String, String> rec, Acknowledgment ack) {
  inboxRepo.persist(rec.topic(), rec.partition(), rec.offset(), rec.key(), rec.value());
  ack.acknowledge();
}
```

### 20.2 Inbox Worker → Orchestrator
```java
@Transactional
public void process(InboxMessage m) {
  if (!schemaValidator.valid(m.payload())) return inboxRepo.markNonRetryable(m, "SCHEMA");
  if (processedRepo.exists(m.messageId())) return inboxRepo.markDone(m);

  PartyDto dto = mapper.toPartyDto(m);
  if (partyRepo.existsWithSameStatusAndType(dto.getPartyId(), dto.getStatus(), dto.getType())) {
    processedRepo.mark(m.messageId());
    return inboxRepo.markDone(m); // skip: no change
  }

  QualificationResult qr = qualificationService.qualify(dto);
  if (!qr.accepted()) {
    audit.emit(AuditIntent.reject("PartyQualification", dto.getPartyId(), qr.reasons()));
    return inboxRepo.park(m, qr.reasons());
  }

  Party p = partyService.upsert(dto); // may change role/status
  BlocksBatch batch = blockService.applyRoleAndBlocks(p, dto); // historise + create 7 blocks

  integrationEvents.publish(new NewCustomerIntent(p.id(), snapshot(p)));
  integrationEvents.publish(new BlocksBatchCreatedIntent(p.id(), batch.toSnapshot()));

  processedRepo.mark(m.messageId());
  inboxRepo.markDone(m);
}
```

### 20.3 Outbox Listener (BEFORE_COMMIT)
```java
@Component
@RequiredArgsConstructor
class OutboxHttpListener {
  private final OutboxHttpRepo outbox;

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void on(BlocksBatchCreatedIntent e) {
    for (BlockSnapshot b : e.blocks()) {
      String externalId = e.partyId() + "#" + b.getBlockType();
      String path = "/services/data/vXX.X/sobjects/Block__c/ExternalId/" + externalId;
      OutboxHttpRow row = OutboxHttpRow.builder()
          .eventId(UUID.randomUUID().toString())
          .eventType("BlocksBatchCreated")
          .resourceType("Block")
          .resourceKey(externalId)
          .httpMethod("PATCH")
          .httpPath(path)
          .payload(b.payload())
          .blockVersion(b.getVersion())
          .payloadHash(sha256(b.payload()))
          .status(Status.PENDING)
          .build();
      outbox.upsertCoalescing(row); // update payload if PENDING exists for (externalId, version)
    }
  }
}
```

### 20.4 Relayer HTTP (confirm-on-unknown)
```java
@Scheduled(fixedDelayString = "PT2S")
public void relay() {
  List<OutboxHttpRow> batch = outbox.fetchPendingOrdered(200);
  for (OutboxHttpRow r : batch) {
    try {
      HttpResponse resp = sf.patch(r.getHttpPath(), r.getPayload());
      if (resp.is2xxSuccessful()) {
        outbox.markSucceeded(r.getId());
        blockRepo.markProjected(r.getResourceKey(), r.getBlockVersion(), r.getPayloadHash());
      } else if (resp.is429Or5xx()) {
        outbox.retryLater(r.getId(), resp.summary());
      } else { // 4xx validation
        outbox.markFailed(r.getId(), resp.summary());
      }
    } catch (TimeoutException te) {
      Optional<SfRecord> sfRec = sf.getByExternalId(r.getResourceKey());
      if (sfRec.isPresent() && matches(sfRec.get(), r.getBlockVersion(), r.getPayloadHash())) {
        outbox.markSucceeded(r.getId());
        blockRepo.markProjected(r.getResourceKey(), r.getBlockVersion(), r.getPayloadHash());
      } else {
        outbox.retryLater(r.getId(), te.getMessage());
      }
    }
  }
}
```

### 20.5 Guard d’écriture (expectedVersion + projection_lock)
```java
public void updateBlock(BlockId id, int expectedVersion, BlockChanges changes, Actor actor) {
  Block b = blockRepo.get(id);
  if (expectedVersion < b.getVersion()) throw new StaleVersionException();
  if (b.isProjectionLocked() && !actor.isReviewer()) throw new ProjectionPendingException();
  b.apply(changes); // version policy during review
  blockRepo.save(b);
}
```

---

## 21) Appendix – "Sans table de lock" (option)

**Add on `BLOCK` (or separate `BLOCK_VERSION`)**
- `state ∈ {DRAFT, READY_FOR_4EYES, LOCKED, APPROVED}`
- `locked_by_review_id` (nullable) + `locked_at`
- `valid_from`, `valid_until` (null until approved)
- `row_version` (optimistic locking)

**"En cours de review" — derived, not stored**
- Use table `REVIEW_BLOCK(review_id, block_id/version_id, status)`; a version is *in review* if a row exists with `status IN ('IN_PROGRESS','READY_FOR_4EYES')`.

**Exclusive lock (first-wins)**
```sql
UPDATE BLOCK
SET state = 'LOCKED',
    locked_by_review_id = :reviewId,
    locked_at = SYSTIMESTAMP
WHERE block_id = :id
  AND state IN ('DRAFT','READY_FOR_4EYES')
  AND locked_by_review_id IS NULL;
```

**Approve (freeze + validity)**
```sql
UPDATE BLOCK
SET state = 'APPROVED',
    valid_from = SYSTIMESTAMP,
    valid_until = CASE WHEN :validity_days IS NOT NULL THEN SYSTIMESTAMP + NUMTODSINTERVAL(:validity_days,'DAY') END
WHERE block_id = :id
  AND state = 'LOCKED'
  AND locked_by_review_id = :reviewId;
```

**Concurrency**: enforce with `row_version`; non-winning writers receive 409 and must reload.

---

**End of Blueprint v1 (updated)**