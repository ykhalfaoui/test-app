-- Unified audit table — all outcomes, all actions, one table.
-- Query by correlation_id to reconstruct the full business flow.

CREATE TABLE audit_entry (
    id              UUID         PRIMARY KEY,
    entity_type     VARCHAR(64)  NOT NULL,
    entity_id       VARCHAR(128) NOT NULL,
    party_id        VARCHAR(64),
    action_type     VARCHAR(64)  NOT NULL,
    outcome         VARCHAR(32)  NOT NULL,
    source_system   VARCHAR(32),
    correlation_id  VARCHAR(64)  NOT NULL,
    causation_id    VARCHAR(64),
    chain_depth     INT          NOT NULL DEFAULT 0,
    occurred_at     TIMESTAMP    NOT NULL,
    details         TEXT
);

-- Primary access pattern: "what happened for correlation X?"
CREATE INDEX idx_audit_correlation ON audit_entry (correlation_id);

-- Secondary: "full history of aggregate ORDER / rev-001"
CREATE INDEX idx_audit_entity ON audit_entry (entity_type, entity_id);

-- Ops: "all failures in the last hour"
CREATE INDEX idx_audit_outcome_time ON audit_entry (outcome, occurred_at);
