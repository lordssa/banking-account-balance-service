package com.itau.account.adapter.out.persistence;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.adapter.out.persistence.entity.AccountBalanceSnapshotEntity;
import com.itau.account.adapter.out.persistence.jpa.AccountBalanceSnapshotJpaRepository;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.domain.AccountBalanceSnapshot;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.Money;
import com.itau.account.domain.OwnerId;
import com.itau.account.domain.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;


@Repository
public class AccountBalanceSnapshotPersistenceAdapter implements AccountBalanceSnapshotPort {

    private final AccountBalanceSnapshotJpaRepository readRepository;
    private final JdbcTemplate jdbc;
    private final IngestionMetrics metrics;

    public AccountBalanceSnapshotPersistenceAdapter(
            AccountBalanceSnapshotJpaRepository readRepository,
            JdbcTemplate jdbc,
            IngestionMetrics metrics
    ) {
        this.readRepository = readRepository;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Override
    public Optional<AccountBalanceSnapshot> findByAccountId(AccountId accountId) {
        return metrics.timeDb("snapshot.find",
                () -> readRepository.findById(accountId.value()).map(this::toDomain));
    }

    @Override
    public boolean upsertIfNewer(BalanceEvent event) {
        return metrics.timeDb("snapshot.upsert_if_newer", () -> {
            int updated = jdbc.update(
                    """
                    INSERT INTO account_balance_snapshot (
                        account_id, owner_id, balance_amount, currency, source_timestamp,
                        account_status, account_created_at, winning_transaction_id, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                    ON CONFLICT (account_id) DO UPDATE SET
                        owner_id = EXCLUDED.owner_id,
                        balance_amount = EXCLUDED.balance_amount,
                        currency = EXCLUDED.currency,
                        source_timestamp = EXCLUDED.source_timestamp,
                        account_status = EXCLUDED.account_status,
                        account_created_at = EXCLUDED.account_created_at,
                        winning_transaction_id = EXCLUDED.winning_transaction_id,
                        updated_at = NOW()
                    WHERE account_balance_snapshot.source_timestamp < EXCLUDED.source_timestamp
                    """,
                    event.accountId().value(),
                    event.ownerId().value(),
                    event.authoritativeBalance().amount(),
                    event.authoritativeBalance().currency().value(),
                    event.sourceTimestamp(),
                    event.accountStatus(),
                    event.accountCreatedAt() == null ? null : java.time.LocalDateTime.ofInstant(
                            event.accountCreatedAt(), java.time.ZoneOffset.UTC),
                    event.transactionId().value()
            );
            return updated > 0;
        });
    }

    private AccountBalanceSnapshot toDomain(AccountBalanceSnapshotEntity entity) {
        return new AccountBalanceSnapshot(
                new AccountId(entity.getAccountId()),
                new OwnerId(entity.getOwnerId()),
                Money.of(entity.getBalanceAmount(), entity.getCurrency()),
                entity.getSourceTimestamp(),
                entity.getAccountStatus(),
                new TransactionId(entity.getWinningTransactionId())
        );
    }
}
