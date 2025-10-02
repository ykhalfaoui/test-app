# Init Load — Simplified Blueprint (v1.2)

**Goal**: One‑shot initial load (no functional scopes) that reads **active natural persons** from a **source DB**, computes the **7 KYC blocks**, writes to **target KYC DB** using **INSERT‑only** (duplicates are **skipped**, not failed), then **exports a CSV** for Salesforce upsert. The job is **retryable** and **restartable**. A secured **Ops API** runs/restarts the job.

---

## 1) Roles & Responsibilities

| Role | Responsibility |
|---|---|
| **Ops Client** | Calls the Ops API to start / restart / monitor the job. |
| **Ops API** | Validates API key, launches jobs, exposes status & restart endpoints. |
| **Job Orchestrator (Spring Batch)** | Coordinates steps, chunk transactions, retry/skip, restart state. |
| **Step A Reader** | JDBC paging from **dsSrc** (ACTIVE + IN). |
| **Step A Processor** | Builds `PartyEntity` + computes **7 Block rows** (`version=1`, `is_current='Y'`, default `state/scope`). |
| **Step A Composite Writer** | In **one TX per chunk**: **INSERT PARTY** then **INSERT BLOCKS** (duplicates → **skip**). |
| **Step B Reader** | JDBC paging from **dsKyc**: select current blocks. |
| **Step B CSV Writer** | Transactional, restartable writer to `.tmp`, then **atomic rename** to a final CSV. |
| **Job Repository** | Persists execution context for **restartability**. |

---

## 2) Global Sequence (API → Job → Steps)

```plantuml
@startuml
skinparam monochrome true
actor OpsClient
participant API as "Ops API (X-API-Key)"
participant JL as "JobLauncher"
participant JOB as "initLoad Job"
participant A as "Step A: Load & Create"
participant B as "Step B: Export CSV"
collections SRC as "dsSrc (source)"
database KYC as "dsKyc (target)"
folder FS as "Filesystem"

OpsClient -> API: POST /ops/initload/run
API -> API: Validate API key & concurrency
API -> JL: launch(job, params)
JL -> JOB: start
JOB -> A: execute (chunk)
A -> SRC: JDBC page ACTIVE & IN
A -> KYC: INSERT PARTY (skip duplicate)
A -> KYC: INSERT 7 BLOCKS (skip duplicate)
A -> JOB: COMPLETED
JOB -> B: execute
B -> KYC: SELECT current blocks
B -> FS: write .tmp (transactional, append)
B -> FS: rename .tmp → final CSV (on COMPLETED)
JOB -> API: executionId, status
API -> OpsClient: 202 Accepted
@enduml
```

---

## 3) Step A — Components & Config

### 3.1 Component table

| Component | Tech | DS | Key Config | Responsibility |
|---|---|---|---|---|
| Reader | `JdbcPagingItemReader<SrcPartyRow>` | **dsSrc** | `saveState=true`, `pageSize=1000`, **ORDER BY `external_id`** | Deterministic read of **ACTIVE** + **IN** persons |
| Processor | `ItemProcessor<SrcPartyRow, PartyWithBlocks>` | — | (pure Java) | Map to `PartyEntity` + **7 blocks** (defaults below) |
| **Composite Writer** | `CompositeItemWriter<PartyWithBlocks>` | **dsKyc** | **chunk=500**, fault‑tolerant | **INSERT PARTY** then **INSERT BLOCKS** in **one TX**; duplicates → **skip** |

**Reader SQL (example)**
```sql
SELECT external_id, type, sub_type, status,
       full_name, birth_date, nationality, residency, email, phone
FROM PARTY_SRC
WHERE status = 'ACTIVE' AND sub_type = 'IN'
ORDER BY external_id
```

### 3.2 Block defaults (computed in Processor)

| Block Type | CLIENT/IN | THIRD_PARTY |
|---|---|---|
| STATIC_DATA | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| ID_DOCUMENTS | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| ADDRESS_PROOF | `INITIALIZED` / IN_SCOPE | `OUT_OF_SCOPE` |
| PEP_SANCTIONS_SCREENING | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| RISK_RATING | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| TAX_COMPLIANCE | `INITIALIZED` / IN_SCOPE | `OUT_OF_SCOPE` |
| KYC_QUESTIONNAIRE | `INITIALIZED` / IN_SCOPE | `OUT_OF_SCOPE` |

> All 7 blocks: `version=1`, `is_current='Y'`. Historization is out of scope for init load.

### 3.3 Composite Writer — how & why

- **Design**: `CompositeItemWriter` delegates to two batch writers **in order** inside the **same chunk transaction**:
  1) `JdbcBatchItemWriter<PartyEntity>` → **INSERT PARTY** (INSERT‑only)
  2) `JdbcBatchItemWriter<BlockRow>` → **INSERT 7 BLOCKS** (flattened list, INSERT‑only)
- **Why**: preserves referential integrity, ensures atomicity per chunk, and lets fault‑tolerance treat duplicate keys as **idempotent skips** (not failures).
- **Duplicates**: unique constraints (`PARTY.external_id`, `BLOCK` unique current) raise `DataIntegrityViolationException`; the step **skips** them and continues.

### 3.4 Fault tolerance (Step A)

| Setting | Value | Rationale |
|---|---|---|
| `chunkSize` | `500` (param `initload.chunk`) | Good throughput; 1 TX/chunk |
| `retry` | DB/I/O transients: timeouts, deadlocks, cannot acquire lock | Resilient |
| `retryLimit` | `3` (exponential backoff 1s→2s→4s) | Avoid hammering |
| `skip` | `DataIntegrityViolationException` | Duplicates → **idempotent skip** |

### 3.5 **Why JDBC Reader/Writer (rationale & trade‑offs)**

| Aspect | **JDBC (chosen)** | JPA (alternative) |
|---|---|---|
| Write pattern | **INSERT‑only** in large batches | `persist`/`merge` (risk of UPDATEs) |
| Performance | **High** (true batch, no ORM overhead) | Good with tuning (batch size, ordering) |
| Memory footprint | **Low** (no persistence context) | Higher (EntityManager 1st‑level cache) |
| Idempotency | **Natural**: rely on DB **UNIQUE** constraints → **skip on duplicate** | Possible, but `merge()` can update rows unexpectedly |
| Determinism | **Full SQL control**; ideal for one‑shot loads | Simpler mapping but less control |
| Restartability | Reader `saveState`, stable **ORDER BY**, chunk TX | Same, but need flush/clear discipline |

**Conclusion**: For a **one‑shot, INSERT‑only, idempotent** init load, **JDBC** gives **speed**, **constant memory**, and **clear idempotency** via constraints. We still use `@Entity` classes as POJOs for parameter binding.

---

## 4) Step B — CSV Export (restartable)

### 4.1 Components

| Component | Tech | DS | Key Config | Responsibility |
|---|---|---|---|---|
| Reader | `JdbcPagingItemReader<BlockCsvRow>` | **dsKyc** | `saveState=true`, `pageSize=1000`, ORDER BY `party_external_id, block_type` | Select current blocks |
| Writer | `FlatFileItemWriter<BlockCsvRow>` | FS | `transactional=true`, `append=true`, `saveState=true` → write to **`.tmp`**; rename on COMPLETE | Output CSV for SF upsert |

**Reader SQL (example)**
```sql
SELECT p.external_id AS party_external_id,
       b.block_type, b.version, b.state, b.scope
FROM BLOCK b
JOIN PARTY p ON p.party_id = b.party_id
WHERE b.is_current = 'Y'
ORDER BY p.external_id, b.block_type
```

**CSV Columns**: `ExternalId=PartyExternalId#BlockType`, `PartyExternalId`, `BlockType`, `BlockVersion(=1)`, `State`, `Scope`, `PayloadHash?`

**Restart pattern**: write to `blocks_sf_export_<executionId>.tmp` (transactional + append + saveState); on `COMPLETED` **atomically rename** to `blocks_sf_export_<timestamp>_<executionId>.csv`.

---

## 5) DB Constraints (idempotency guards)

| Table | Constraint | Purpose |
|---|---|---|
| `PARTY` | `external_id UNIQUE` | INSERT‑only; duplicates → **skip** |
| `BLOCK` | Unique **current** per `(party_id, block_type)` with filtered unique index (`is_current='Y'`) | INSERT‑only; duplicates → **skip** |

DDL reminders:
```sql
ALTER TABLE PARTY ADD CONSTRAINT UX_PARTY_EXT UNIQUE (external_id);

CREATE UNIQUE INDEX UX_BLOCK_CURRENT ON BLOCK (
  party_id, block_type,
  CASE WHEN is_current = 'Y' THEN 1 ELSE NULL END
);
```

---

## 6) Ops API — Run / Restart (secured by API Key)

### 6.1 Security & Concurrency
- Header: `X-API-Key: <secret>` (stored in a secret manager; allow multiple keys for rotation).
- Optional: `X-Idempotency-Key: <uuid>` to deduplicate trigger requests.
- **Single run at a time**: concurrent `/run` returns **409**.

### 6.2 Endpoints

| Method & Path | Purpose | Request | Response |
|---|---|---|---|
| `POST /ops/initload/run` | Start a new job instance | Headers: `X-API-Key` (+ optional `X-Idempotency-Key`). Body (JSON, all optional): `{ "dry": false, "chunk": 500, "retryLimit": 3, "exportDir": "/data/exports", "createBlocks": true }` | `202 { executionId, status }`; `409 JOB_ALREADY_RUNNING`; `403` invalid key |
| `GET /ops/initload/{executionId}` | Get status & counters | `X-API-Key` | `200 { id, status, startTime, endTime, exitCode, exitDescription }` |
| `POST /ops/initload/{executionId}/restart` | Restart a `FAILED` execution | `X-API-Key` | `202 { executionId }` (new one) or `409 NOT_RESTARTABLE` |
| `POST /ops/initload/{executionId}/stop` | Graceful stop request | `X-API-Key` | `202` or `409 NOT_STOPPABLE` |

---

## 7) Error, Retry & Restart (single view)

| Case | Policy |
|---|---|
| **DB/I/O transient** (timeouts, deadlocks, cannot acquire lock) | `retryLimit=3` with exponential backoff; if exhausted → step **FAILED** |
| **Duplicate key** (`DataIntegrityViolationException`) | **skip** and continue (idempotent success) |
| **CSV I/O persistent** | **FAIL** the step (avoid partial file), restart later |
| **Unknown runtime** | **FAIL** immediately |
| **Restart** | Same JobParameters → resumes from last committed chunk (Reader `saveState=true`); CSV writer continues appending to `.tmp` |

---

## 8) Runbook (minimal)

```bash
# Run
java -jar app.jar \
  --spring.profiles.active=initload \
  --spring.batch.job.name=initLoad

# Restart after FAILED (same JobParameters)
java -jar app.jar \
  --spring.profiles.active=initload \
  --spring.batch.job.name=initLoad
```

**Metrics**: `read.count`, `write.count`, `skip.count (duplicates)`, `retry.count`, CSV lines, execution time.

---

**End — Init Load Simplified Blueprint (v1.2)**

