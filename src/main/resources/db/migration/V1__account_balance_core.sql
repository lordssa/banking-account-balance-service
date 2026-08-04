-- V1: authoritative balance snapshot, idempotency, journal, conflicts

CREATE TABLE account_balance_snapshot (
    account_id              UUID PRIMARY KEY,
    owner_id                UUID            NOT NULL,
    balance_amount          NUMERIC(20, 4)  NOT NULL,
    currency                CHAR(3)         NOT NULL,
    source_timestamp        TIMESTAMP(6)    NOT NULL,
    account_status          VARCHAR(32)     NOT NULL,
    account_created_at      TIMESTAMP(6),
    winning_transaction_id  UUID            NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_snapshot_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_snapshot_source_ts ON account_balance_snapshot (source_timestamp);

CREATE TABLE processed_transaction (
    transaction_id      UUID PRIMARY KEY,
    account_id          UUID            NOT NULL,
    source_timestamp    TIMESTAMP(6)    NOT NULL,
    first_outcome       VARCHAR(32)     NOT NULL,
    first_processed_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_account_ts ON processed_transaction (account_id, source_timestamp);

CREATE TABLE journal_processing_record (
    journal_id          UUID PRIMARY KEY,
    transaction_id      UUID,
    account_id          UUID,
    source_timestamp    TIMESTAMP(6),
    received_at         TIMESTAMPTZ     NOT NULL,
    outcome             VARCHAR(32)     NOT NULL,
    snapshot_effect     VARCHAR(32)     NOT NULL,
    correlation_id      VARCHAR(128),
    attempt_key         VARCHAR(128)    NOT NULL,
    decision_context    JSONB           NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_journal_attempt_key UNIQUE (attempt_key)
);

CREATE INDEX idx_journal_tx ON journal_processing_record (transaction_id, created_at);
CREATE INDEX idx_journal_account ON journal_processing_record (account_id, created_at);
CREATE INDEX idx_journal_outcome ON journal_processing_record (outcome, created_at);

CREATE TABLE ordering_conflict (
    conflict_id         UUID PRIMARY KEY,
    account_id          UUID            NOT NULL,
    source_timestamp    TIMESTAMP(6)    NOT NULL,
    transaction_id_a    UUID            NOT NULL,
    transaction_id_b    UUID            NOT NULL,
    detected_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    recovery_state      VARCHAR(32)     NOT NULL,
    CONSTRAINT uq_conflict_account_ts UNIQUE (account_id, source_timestamp)
);

CREATE INDEX idx_conflict_recovery ON ordering_conflict (recovery_state, detected_at);

CREATE TABLE administrative_journal_action (
    action_id   UUID PRIMARY KEY,
    action_type VARCHAR(64)     NOT NULL,
    actor_id    VARCHAR(128)    NOT NULL,
    scope       JSONB           NOT NULL,
    result      VARCHAR(32)     NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
