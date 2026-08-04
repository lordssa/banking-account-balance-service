package com.itau.account.adapter.out.persistence;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.adapter.out.persistence.jpa.ProcessedTransactionJpaRepository;
import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.ProcessedTransactionInsert;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProcessedTransactionPersistenceAdapter implements ProcessedTransactionPort {

    static final String PK_CONSTRAINT = "processed_transaction_pkey";
    static final String ACCOUNT_TS_CONSTRAINT = "uq_processed_account_source_ts";

    private final ProcessedTransactionJpaRepository repository;
    private final JdbcTemplate jdbc;
    private final IngestionMetrics metrics;

    public ProcessedTransactionPersistenceAdapter(
            ProcessedTransactionJpaRepository repository,
            JdbcTemplate jdbc,
            IngestionMetrics metrics
    ) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public Optional<ProcessingOutcome> findOutcome(TransactionId transactionId) {
        return metrics.timeDb("processed.find_outcome",
                () -> repository.findById(transactionId.value())
                        .map(entity -> ProcessingOutcome.valueOf(entity.getFirstOutcome())));
    }

    @Override
    public Optional<TransactionId> findOtherTransactionAt(AccountId accountId, LocalDateTime sourceTimestamp,
                                                          TransactionId exclude) {
        return metrics.timeDb("processed.find_other_at_ts", () -> {
            List<UUID> ids = jdbc.query(
                    """
                    SELECT transaction_id
                    FROM processed_transaction
                    WHERE account_id = ?
                      AND source_timestamp = ?
                      AND transaction_id <> ?
                    ORDER BY
                      CASE WHEN first_outcome NOT IN ('CONFLICTING', 'INVALID') THEN 0 ELSE 1 END,
                      first_processed_at ASC
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getObject("transaction_id", UUID.class),
                    accountId.value(),
                    sourceTimestamp,
                    exclude.value()
            );
            return ids.stream().findFirst().map(TransactionId::new);
        });
    }

    @Override
    public ClaimResult tryInsert(ProcessedTransactionInsert insert) {
        return metrics.timeDb("processed.try_insert", () -> {
            int inserted = jdbc.update(
                    """
                    INSERT INTO processed_transaction (
                        transaction_id, account_id, source_timestamp, first_outcome
                    ) VALUES (?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    insert.transactionId().value(),
                    insert.accountId().value(),
                    insert.sourceTimestamp(),
                    insert.outcome().name()
            );
            if (inserted > 0) {
                return ClaimResult.INSERTED;
            }
            Boolean duplicateTx = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM processed_transaction WHERE transaction_id = ?)",
                    Boolean.class,
                    insert.transactionId().value()
            );
            if (Boolean.TRUE.equals(duplicateTx)) {
                return ClaimResult.DUPLICATE_TRANSACTION;
            }
            return ClaimResult.ACCOUNT_TIMESTAMP_TAKEN;
        });
    }

    static ClaimResult classify(Throwable ex) {
        if (mentionsConstraint(ex, ACCOUNT_TS_CONSTRAINT)) {
            return ClaimResult.ACCOUNT_TIMESTAMP_TAKEN;
        }
        if (mentionsConstraint(ex, PK_CONSTRAINT)) {
            return ClaimResult.DUPLICATE_TRANSACTION;
        }
        return ClaimResult.DUPLICATE_TRANSACTION;
    }

    private static boolean mentionsConstraint(Throwable ex, String constraint) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(constraint)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
