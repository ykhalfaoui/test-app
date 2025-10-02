# Transactional Inbox & Outbox — Column Reference (Oracle)

> **Scope**: Column-by-column reference for the **INBOX** (reliable receive from Kafka) and **OUTBOX** (reliable send to Salesforce) tables — types, nullability, defaults, and semantics.

---

## INBOX — Reliable receive (Kafka → DB)

| Column | Oracle Type | Null? | Default | Description |
|---|---|---:|---|---|
| **ID** | `NUMBER` (IDENTITY, PK) | No | — | Technical primary key. |
| **MESSAGE_ID** | `VARCHAR2(200)` | No | — | Business/Kafka message id used for **deduplication**. |
| **SOURCE_SYSTEM** | `VARCHAR2(100)` | No | — | Source system (e.g., `ihub`). |
| **TOPIC** | `VARCHAR2(200)` | No | — | Kafka topic name (traceability). |
| **PARTITION_NUM** | `NUMBER(10)` | No | — | Kafka partition number. |
| **OFFSET_NUM** | `NUMBER(19)` | No | — | Kafka offset (for replay/debug). |
| **KEY_STR** | `VARCHAR2(4000)` | Yes | — | Kafka key (human-readable; helps per-aggregate ordering). |
| **AGGREGATE_ID** | `VARCHAR2(200)` | Yes | — | Business aggregate id (family, customer, hit, …). |
| **EVENT_TYPE** | `VARCHAR2(200)` | Yes | — | Logical event type (`MembersCreated`, …). |
| **PAYLOAD** | `CLOB` **CHECK (IS JSON)** | Yes* | — | **Valid JSON** when deserialization succeeds. |
| **HEADERS** | `CLOB` **CHECK (IS JSON)** | Yes | — | Kafka headers serialized as JSON (diagnostics/search). |
| **RAW_PAYLOAD_BASE64** | `CLOB` | Yes* | — | **Original bytes encoded as Base64** when SERDE fails (invalid JSON/binary). |
| **EVENT_TS** | `TIMESTAMP WITH TIME ZONE` | Yes | — | Event occurrence time if provided by producer. |
| **RECEIVED_AT** | `TIMESTAMP WITH TIME ZONE` | No | `SYSTIMESTAMP` | DB insertion time. |
| **STATUS** | `VARCHAR2(20)` | No | `'RECEIVED'` | Processing status (see allowed values below). |
| **ATTEMPTS** | `NUMBER(10)` | No | `0` | Number of processing attempts. |
| **NEXT_ATTEMPT_AT** | `TIMESTAMP WITH TIME ZONE` | Yes | — | When to retry next (backoff). |
| **PROCESSED_AT** | `TIMESTAMP WITH TIME ZONE` | Yes | — | Time of successful processing. |
| **ERROR_STAGE** | `VARCHAR2(50)` | Yes | — | `CONSUMER_SERDE` or `BUSINESS`. |
| **ERROR_CODE** | `VARCHAR2(200)` | Yes | — | Error code (classification). |
| **ERROR_MESSAGE** | `VARCHAR2(2000)` | Yes | — | Human-readable error message (truncate if needed). |

\* **Constraint**: `CK_INBOX_PAYLOAD_OR_RAW` → **at least one** of `PAYLOAD` or `RAW_PAYLOAD_BASE64` must be present.

### INBOX status — allowed values & meaning
- **`RECEIVED`** — Persisted in Inbox; not processed yet (or ready to be picked up).
- **`RETRY`** — Previous business attempt failed transiently; worker will retry at/after `NEXT_ATTEMPT_AT`.
- **`FAILED`** — Permanent failure (validation, invariant). Needs manual action.
- **`PROCESSED`** — Successfully processed; downstream effects performed (e.g., Outbox written).
- **`SERDE_ERROR`** — Deserialization failed; **raw bytes** preserved in `RAW_PAYLOAD_BASE64`. A repair/reparse job can promote it back to `RECEIVED`.

### INBOX other controlled values
- **`ERROR_STAGE`**:
  - `CONSUMER_SERDE` — failure while consuming/deserializing from Kafka.
  - `BUSINESS` — failure in business processing after ingestion.

**Useful constraints/indexes**
```sql
CONSTRAINT UX_INBOX_DEDUPE UNIQUE (SOURCE_SYSTEM, MESSAGE_ID);
CONSTRAINT CK_INBOX_STATUS CHECK (STATUS IN ('RECEIVED','RETRY','FAILED','PROCESSED','SERDE_ERROR'));
CONSTRAINT CK_INBOX_PAYLOAD_OR_RAW CHECK (PAYLOAD IS NOT NULL OR RAW_PAYLOAD_BASE64 IS NOT NULL);

-- For workers and troubleshooting:
CREATE INDEX IX_INBOX_STATUS_DUE ON INBOX (STATUS, NEXT_ATTEMPT_AT);
CREATE INDEX IX_INBOX_AGG       ON INBOX (AGGREGATE_ID);
```

---

## OUTBOX — Reliable send (DB → Salesforce)

| Column | Oracle Type | Null? | Default | Description |
|---|---|---:|---|---|
| **ID** | `NUMBER` (IDENTITY, PK) | No | — | Technical primary key. |
| **AGGREGATE_TYPE** | `VARCHAR2(50)` | No | — | Aggregate type (e.g., `Member`, `Block`). |
| **AGGREGATE_ID** | `VARCHAR2(200)` | No | — | Business correlation id (natural key). |
| **EVENT_TYPE** | `VARCHAR2(200)` | No | — | Semantic event (`MembersUpsertRequested`, …). |
| **EVENT_VERSION** | `NUMBER(5)` | No | `1` | Version of the event contract. |
| **PAYLOAD** | `CLOB` **CHECK (IS JSON)** | No | — | JSON to publish (always **valid**). |
| **HEADERS** | `CLOB` **CHECK (IS JSON)** | Yes | — | Optional metadata (correlation, actor…). |
| **DESTINATION** | `VARCHAR2(200)` | No | — | Target, e.g., `HTTP:SFDC:BulkV2:Contact` or REST upsert. |
| **CREATED_AT** | `TIMESTAMP WITH TIME ZONE` | No | `SYSTIMESTAMP` | Creation time. |
| **STATUS** | `VARCHAR2(20)` | No | `'PENDING'` | Delivery status (see allowed values below). |
| **ATTEMPTS** | `NUMBER(10)` | No | `0` | Number of delivery attempts. |
| **NEXT_ATTEMPT_AT** | `TIMESTAMP WITH TIME ZONE` | Yes | — | When to retry next (backoff). |
| **ERROR_CODE** | `VARCHAR2(200)` | Yes | — | HTTP/validation error code. |
| **ERROR_MESSAGE** | `VARCHAR2(2000)` | Yes | — | Error details for ops. |

### OUTBOX status — allowed values & meaning
- **`PENDING`** — Not yet sent (or awaiting next retry). For transient errors, **keep** `STATUS='PENDING'`, increment `ATTEMPTS`, and schedule `NEXT_ATTEMPT_AT`.
- **`DISPATCHED`** — Successfully delivered to Salesforce (HTTP call acknowledged / upsert OK).
- **`FAILED`** — Permanent failure (e.g., validation in Salesforce). Requires manual remediation or compensating action.

**Useful constraints/indexes**
```sql
CONSTRAINT CK_OUTBOX_STATUS CHECK (STATUS IN ('PENDING','DISPATCHED','FAILED'));

CREATE INDEX IX_OUTBOX_PENDING ON OUTBOX (STATUS, NEXT_ATTEMPT_AT);
CREATE INDEX IX_OUTBOX_AGG     ON OUTBOX (AGGREGATE_TYPE, AGGREGATE_ID);
```
