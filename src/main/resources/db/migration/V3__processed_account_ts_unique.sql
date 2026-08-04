-- Enforce one non-conflicting binding per account + source timestamp (FR-008 / FR-020).
-- Existing race duplicates (pre-constraint) are demoted to CONFLICTING before the unique index is created.

WITH ranked AS (
    SELECT
        pt.transaction_id,
        pt.account_id,
        pt.source_timestamp,
        ROW_NUMBER() OVER (
            PARTITION BY pt.account_id, pt.source_timestamp
            ORDER BY
                CASE
                    WHEN s.winning_transaction_id IS NOT NULL
                         AND s.winning_transaction_id = pt.transaction_id
                        THEN 0
                    ELSE 1
                END,
                pt.first_processed_at ASC,
                pt.transaction_id ASC
        ) AS rn
    FROM processed_transaction pt
    LEFT JOIN account_balance_snapshot s
        ON s.account_id = pt.account_id
       AND s.source_timestamp = pt.source_timestamp
    WHERE pt.first_outcome NOT IN ('CONFLICTING', 'INVALID')
),
losers AS (
    SELECT
        r.transaction_id AS loser_tx,
        w.transaction_id AS winner_tx,
        r.account_id,
        r.source_timestamp
    FROM ranked r
    JOIN ranked w
      ON w.account_id = r.account_id
     AND w.source_timestamp = r.source_timestamp
     AND w.rn = 1
    WHERE r.rn > 1
),
demoted AS (
    UPDATE processed_transaction pt
    SET first_outcome = 'CONFLICTING'
    FROM losers l
    WHERE pt.transaction_id = l.loser_tx
    RETURNING pt.transaction_id
)
INSERT INTO ordering_conflict (
    conflict_id,
    account_id,
    source_timestamp,
    transaction_id_a,
    transaction_id_b,
    detected_at,
    recovery_state
)
SELECT DISTINCT ON (l.account_id, l.source_timestamp)
    gen_random_uuid(),
    l.account_id,
    l.source_timestamp,
    l.winner_tx,
    l.loser_tx,
    NOW(),
    'OPEN'
FROM losers l
ORDER BY l.account_id, l.source_timestamp, l.loser_tx
ON CONFLICT (account_id, source_timestamp) DO NOTHING;

DROP INDEX IF EXISTS idx_processed_account_ts;
DROP INDEX IF EXISTS uq_processed_account_source_ts;

CREATE UNIQUE INDEX uq_processed_account_source_ts
    ON processed_transaction (account_id, source_timestamp)
    WHERE first_outcome NOT IN ('CONFLICTING', 'INVALID');
