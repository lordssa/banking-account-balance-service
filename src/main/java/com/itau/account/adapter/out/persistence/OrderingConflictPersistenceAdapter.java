package com.itau.account.adapter.out.persistence;

import com.itau.account.adapter.out.persistence.jpa.OrderingConflictJpaRepository;
import com.itau.account.application.model.OrderingConflictInsert;
import com.itau.account.application.model.OrderingConflictRecord;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class OrderingConflictPersistenceAdapter implements OrderingConflictPort {

    private final OrderingConflictJpaRepository repository;
    private final JdbcTemplate jdbc;

    public OrderingConflictPersistenceAdapter(
            OrderingConflictJpaRepository repository,
            JdbcTemplate jdbc
    ) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrderingConflictRecord> findOpen(AccountId accountId, LocalDateTime sourceTimestamp) {
        return repository.findByAccountIdAndSourceTimestamp(accountId.value(), sourceTimestamp)
                .map(entity -> new OrderingConflictRecord(
                        entity.getConflictId(),
                        new AccountId(entity.getAccountId()),
                        entity.getSourceTimestamp(),
                        new TransactionId(entity.getTransactionIdA()),
                        new TransactionId(entity.getTransactionIdB()),
                        entity.getRecoveryState()
                ));
    }

    @Override
    public void recordConflict(OrderingConflictInsert conflict) {
        jdbc.update(
                """
                INSERT INTO ordering_conflict (
                    conflict_id, account_id, source_timestamp,
                    transaction_id_a, transaction_id_b, recovery_state
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                conflict.conflictId(),
                conflict.accountId().value(),
                conflict.sourceTimestamp(),
                conflict.transactionIdA().value(),
                conflict.transactionIdB().value(),
                "OPEN"
        );
    }
}
