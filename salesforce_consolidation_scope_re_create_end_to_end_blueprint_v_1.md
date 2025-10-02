# Salesforce Consolidation & Scope Re‑Create — End‑to‑End Blueprint (v1)

**Project:** KYC Remediation (Java + Oracle, Spring Batch, JDBC)

**Date:** 02‑Oct‑2025  
**Owner:** Crok4IT

---

## 0) Purpose & Assumptions

**Goal:** After the initial load (PARTY + 7 blocks created in RGO/KYC DB), consolidate the **entire init‑load cohort** with Salesforce using an **SF CSV extract** (no DB link, no SF API in this flow). From that reconciliation, derive a **scope** of parties to **re‑create** from **NODS/BDWH**, purge their current data, rebuild PARTY + 7 blocks, and export a new CSV for Salesforce ingestion.

**Assumptions**
- Salesforce delivers a **full cohort CSV** (all blocks from init load). Ops ingests it into a staging table.
- **ExternalId (SF)** = `PARTY.external_id` (T24 id).
- **INSERT‑only** semantics in RGO: duplicates are **skipped** (idempotency via DB UNIQUE constraints).
- **No Kafka**, **no SF API** in this consolidation flow; only **CSV import/export**.
- NODS/BDWH is the **authoritative source** for customer/party data on re‑create.

---

## 1) Glossary

- **RGO**: local KYC (orchestrator) database.  
- **Init‑load cohort**: the set of PARTY + 7 blocks created by the initial load.  
- **Scope**: the subset of party IDs selected (from reconciliation) to purge & re‑create.  
- **NODS/BDWH**: upstream warehouse used to refetch party data.

---

## 2) Data Contracts (staging & recon)

### 2.1 Staging — Salesforce extract (full cohort)
```sql
CREATE TABLE STG_SF_BLOCK (
  external_id       VARCHAR2(128) UNIQUE NOT NULL, -- PartyExternalId#BlockType
  party_external_id VARCHAR2(64)  NOT NULL,
  block_type        VARCHAR2(40)  NOT NULL,
  block_version     NUMBER        NOT NULL,
  state             VARCHAR2(20)  NOT NULL,
  scope             VARCHAR2(20)  NOT NULL,
  payload_hash      VARCHAR2(64),
  loaded_at         TIMESTAMP DEFAULT SYSTIMESTAMP
);
```
**CSV columns expected:** `external_id,party_external_id,block_type,block_version,state,scope,payload_hash`

### 2.2 Reconciliation output & scope
```sql
CREATE TABLE RECON_BLOCK_DIFF (
  external_id       VARCHAR2(128) PRIMARY KEY,
  party_external_id VARCHAR2(64)  NOT NULL,
  block_type        VARCHAR2(40)  NOT NULL,
  rgo_version       NUMBER,
  sf_version        NUMBER,
  rgo_state         VARCHAR2(20),
  sf_state          VARCHAR2(20),
  rgo_hash          VARCHAR2(64),
  sf_hash           VARCHAR2(64),
  diff_code         VARCHAR2(30)  NOT NULL, -- MATCH | MISSING_IN_SF | MISSING_IN_RGO | VERSION_MISMATCH | PAYLOAD_MISMATCH
  computed_at       TIMESTAMP DEFAULT SYSTIMESTAMP
);

CREATE TABLE STG_SCOPE (
  external_id VARCHAR2(64) PRIMARY KEY,  -- party IDs chosen for re-create
  source      VARCHAR2(20) DEFAULT 'RECON' -- RECON | OPS | MIXED
);
```

### 2.3 KYC (target) reminders
- `PARTY.external_id` **UNIQUE** (INSERT‑only writer skips duplicates).
- `BLOCK` has unique **current** per `(customer_id, block_type)` using a filtered unique index on `is_current='Y'`.

```sql
ALTER TABLE PARTY ADD CONSTRAINT UX_PARTY_EXT UNIQUE (external_id);

CREATE UNIQUE INDEX UX_BLOCK_CURRENT ON BLOCK (
  customer_id,
  block_type,
  CASE WHEN is_current = 'Y' THEN 1 ELSE NULL END
);
```

---

## 3) Architecture (high level)

- **No cross‑DB links**.  
- **CSV in → STG_SF_BLOCK** (full cohort).  
- Set‑based **diff** (RGO vs SF) → `RECON_BLOCK_DIFF`.  
- Automatic **scope build** → `STG_SCOPE` (then optional Ops curation).  
- **Re‑create job** (scope): purge → rebuild from **NODS/BDWH** → create 7 blocks → **export CSV** (scope only).

```mermaid
flowchart LR
  subgraph Consolidation (Full Cohort)
    SFCSV[Salesforce CSV Extract] -->|Ops ingest| STG_SF_BLOCK
    STG_SF_BLOCK -->|Set-based SQL| RECON_BLOCK_DIFF
    RECON_BLOCK_DIFF -->|Build party list| STG_SCOPE
  end

  subgraph Re-Create Job (Scope)
    STG_SCOPE --> Purge[Purge PARTY & BLOCK (scope)]
    Purge --> ReParties[Fetch from NODS (IN-batches) → INSERT PARTY]
    ReParties --> Blocks[Compute defaults → INSERT 7 BLOCKS]
    Blocks --> CSV[Export scope to CSV (restartable)]
  end
```

---

## 4) Job A — Consolidation (Full Init‑Load Cohort)

| # | Step | What it does | Components | Notes |
|---|---|---|---|---|
| C1 | **Ingest Salesforce Extract** | Load SF CSV → `STG_SF_BLOCK` | `FlatFileItemReader` → `JdbcBatchItemWriter` | `TRUNCATE STG_SF_BLOCK` then INSERT; enforce UNIQUE on `external_id`; skip duplicates if present. |
| C2 | **Compute DIFF (SF ↔ RGO)** | Fill `RECON_BLOCK_DIFF` for full cohort | `JdbcTemplate` (set‑based SQL) | Joins **current** RGO blocks; classifies differences. |
| C3 | **Build SCOPE from DIFF** | Derive `STG_SCOPE` (parties to re‑create) | SQL (`INSERT .. SELECT DISTINCT`) | Policy: include any party having a non‑`MATCH` diff; Ops may edit scope. |

### 4.1 Diff SQL (full cohort)
```sql
TRUNCATE TABLE RECON_BLOCK_DIFF;

WITH RGO AS (
  SELECT p.external_id AS party_external_id,
         b.block_type,
         b.version      AS rgo_version,
         b.state        AS rgo_state,
         b.payload_hash AS rgo_hash,
         p.external_id || '#' || b.block_type AS external_id
  FROM BLOCK b
  JOIN PARTY p ON p.party_id = b.customer_id
  WHERE b.is_current = 'Y'
),
SF AS (
  SELECT s.party_external_id,
         s.block_type,
         s.block_version AS sf_version,
         s.state         AS sf_state,
         s.payload_hash  AS sf_hash,
         s.external_id
  FROM STG_SF_BLOCK s
)
INSERT INTO RECON_BLOCK_DIFF (
  external_id, party_external_id, block_type,
  rgo_version, sf_version, rgo_state, sf_state,
  rgo_hash, sf_hash, diff_code, computed_at
)
SELECT
  COALESCE(r.external_id, f.external_id),
  COALESCE(r.party_external_id, f.party_external_id),
  COALESCE(r.block_type, f.block_type),
  r.rgo_version, f.sf_version, r.rgo_state, f.sf_state,
  r.rgo_hash, f.sf_hash,
  CASE
    WHEN r.external_id IS NULL THEN 'MISSING_IN_RGO'
    WHEN f.external_id IS NULL THEN 'MISSING_IN_SF'
    WHEN NVL(r.rgo_version, -1) <> NVL(f.sf_version, -1) THEN 'VERSION_MISMATCH'
    WHEN NVL(r.rgo_hash, 'X') <> NVL(f.sf_hash, 'X')     THEN 'PAYLOAD_MISMATCH'
    ELSE 'MATCH'
  END,
  SYSTIMESTAMP
FROM RGO r
FULL OUTER JOIN SF f ON r.external_id = f.external_id;
```

### 4.2 Scope construction (default policy)
```sql
TRUNCATE TABLE STG_SCOPE;

INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(STG_SCOPE( external_id )) */
INTO STG_SCOPE(external_id, source)
SELECT DISTINCT party_external_id, 'RECON'
FROM RECON_BLOCK_DIFF
WHERE diff_code IN ('MISSING_IN_RGO','MISSING_IN_SF','VERSION_MISMATCH','PAYLOAD_MISMATCH');
```
> Ops can **add/remove** rows in `STG_SCOPE` after this step.

---

## 5) Job B — Re‑Create by Scope (like init‑load)

| # | Step | What it does | Components & Rules | Restart/Idempotency |
|---|---|---|---|---|
| R1 | **Purge (scope)** | Delete existing data for scope parties | `DELETE BLOCK` by `customer_id` in scope → `DELETE PARTY` by `external_id` in scope (order matters) | Safe to replay (0 rows if already purged) |
| R2 | **Create PARTY from NODS** | Batch‑fetch NODS by **IN‑batches ≤ 1000** inside the **writer** and INSERT into `PARTY` | Reader: `JdbcPagingItemReader` over `STG_SCOPE` (stable order). Writer: **custom BatchFetchAndInsertPartyWriter** (NODS query `WHERE id IN (:ids)`) → `JdbcBatchItemWriter<Party>` (INSERT‑only) | Duplicates → **skip** (`DataIntegrityViolationException`). Retry on transient DB errors. |
| R3 | **Create 7 BLOCKS** | Compute defaults and INSERT (current v=1) | Reader: KYC JOIN `PARTY`×`STG_SCOPE`. Processor: 7 blocks / party. Writer: `JdbcBatchItemWriter<Block>` (INSERT‑only). | Unique “current” `(customer_id, block_type)`; collisions → **skip**. |
| R4 | **Export CSV (scope)** | Export current blocks for scope | Reader: KYC JOIN `PARTY`×`BLOCK`×`STG_SCOPE`. Writer: `FlatFileItemWriter` (`transactional+append+saveState`) → `.tmp` → **atomic rename** | **Restartable** writer; safe re‑run. |

### 5.1 Why fetch NODS in the writer?
- Avoids **N+1** queries: one chunk ⇒ a few **IN‑batches** (≤ 1000) to NODS.
- Keeps a single‑source reader (scope from KYC) and a dual‑DS writer (read NODS, write KYC) — simpler restart semantics.

**Writer sketch**
```java
@RequiredArgsConstructor
class BatchFetchAndInsertPartyWriter implements ItemWriter<ScopeId> {
  private final NamedParameterJdbcTemplate nods; // dsNods (READ)
  private final JdbcBatchItemWriter<PartyEntity> partyInsert; // dsKyc (WRITE)
  private final int maxIn = 900; // keep < 1000 for Oracle IN lists

  @Override
  @Transactional
  public void write(List<? extends ScopeId> items) throws Exception {
    List<String> ids = items.stream().map(ScopeId::externalId).toList();
    for (List<String> sub : chunksOf(ids, maxIn)) {
      List<PartyEntity> parties = nods.query(
        "SELECT id AS external_id, status, type, sub_type, full_name, birth_date, nationality, residency, email, phone " +
        "FROM NODS.PARTY_SRC WHERE id IN (:ids) ORDER BY id",
        Map.of("ids", sub),
        (rs, i) -> mapToParty(rs) // generate UUID party_id here
      );
      partyInsert.write(parties); // INSERT-only; duplicates skipped by fault-tolerant step configuration
    }
  }
}
```

### 5.2 Defaults for 7 blocks (processor)

| Block Type | CLIENT/IND | THIRD_PARTY |
|---|---|---|
| STATIC_DATA | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| ID_DOCUMENTS | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| ADDRESS_PROOF | `INITIALIZED` / IN_SCOPE | `OUT_OF_SCOPE` |
| PEP_SANCTIONS_SCREENING | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| RISK_RATING | `INITIALIZED` / IN_SCOPE | `INITIALIZED` / IN_SCOPE |
| TAX_COMPLIANCE | `INITIALIZED` / IN_SCOPE | `OUT_OF_SCOPE` |
| KYC_QUESTIONNAIRE | `INITIALIZED` / IN_SCOPE | `OUT_OF_SCOPE` |

> All blocks created as `version=1`, `is_current='Y'` (no historization in re‑create).

---

## 6) Fault Tolerance & Restart

- **Readers**: `saveState=true`, **stable ORDER BY** (by `external_id`) → **restartable** from last committed chunk.
- **Writers**:
  - JDBC **INSERT‑only**; duplicates raise `DataIntegrityViolationException` → **skip** (counted, not failed).
  - CSV writer: `transactional=true`, `append=true`, `saveState=true` → writes `.tmp`, **renames** to final name on `COMPLETED` → **restartable**.
- **Retries**: transient DB errors (timeouts, deadlocks, cannot acquire lock) with **exponential backoff** (e.g., 1s→2s→4s; limit=3), then step **FAILED**.
- **Concurrency**: enforce single job execution at a time via Ops API (409 on conflicts).

---

## 7) Ops API (optional)

| Method & Path | Purpose | Request | Responses |
|---|---|---|---|
| `POST /ops/consolidation/run` | Run **C1→C3** (ingest SF CSV path, compute diff, build scope) | Headers: `X-API-Key`. Body: `{ "sfCsvPath": "/path/sf_extract.csv" }` | `202 { executionId }`, `403` bad key, `409` already running |
| `GET /ops/recon/diff/summary` | Summary by `diff_code` | `X-API-Key` | `200 { MATCH: n, MISSING_IN_SF: n, ... }` |
| `POST /ops/recreate/run` | Run **R1→R4** on current `STG_SCOPE` | `X-API-Key` | `202 { executionId }`, `409` already running |
| `POST /ops/{executionId}/restart` | Restart a failed execution | `X-API-Key` | `202 { executionId }`, `409` not restartable |

Security: API key in headers; keep keys in a secret manager; allow rotation.

---

## 8) Configuration (sample)

```yaml
spring:
  profiles: initload
initload:
  chunk: 500
  retryLimit: 3
nods:
  maxIn: 900
export:
  dir: /data/exports
```

---

## 9) Runbook

1) **Ops** ingests the **full SF extract** into `STG_SF_BLOCK` by triggering **Job A (C1→C3)** via API.  
2) Review **RECON_BLOCK_DIFF** and adjust **STG_SCOPE** (add/remove party IDs as needed).  
3) **Business GO** to re‑create.  
4) Trigger **Job B (R1→R4)**: purge → rebuild from NODS → create 7 blocks → export **scope CSV**.  
5) Provide the **scope CSV** to Salesforce team for ingestion.  
6) (Optional) Re‑run **Job A** to validate reconciliation after SF ingestion.

---

## 10) Observability & Ops

- **Metrics**: `read.count`, `write.count`, `skip.count (duplicates)`, `retry.count`, CSV lines, execution time; expose via logs/actuator.
- **Tracing**: propagate correlation IDs in logs.
- **Housekeeping**: purge `STG_*` and `RECON_BLOCK_DIFF` after retention period; archive CSVs.

---

## 11) Risk & Recovery

- Keep a **backup** of impacted data (scope) before purge (`BKP_PARTY/BKP_BLOCK`) if required by policy.
- If re‑create fails mid‑way, **restart** with same JobParameters; idempotent writers prevent duplication.
- If SF CSV is malformed, reject early (header validation, column count) and return actionable error to Ops.

---

**End of Blueprint (v1)**