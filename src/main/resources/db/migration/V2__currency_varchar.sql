-- Align currency with JPA String mapping (varchar) to avoid CHAR/bpchar validation mismatch
ALTER TABLE account_balance_snapshot
    ALTER COLUMN currency TYPE VARCHAR(3);
