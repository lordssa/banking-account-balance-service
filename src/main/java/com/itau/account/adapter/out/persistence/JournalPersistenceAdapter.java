package com.itau.account.adapter.out.persistence;

import com.itau.account.adapter.out.persistence.entity.JournalProcessingRecordEntity;
import com.itau.account.adapter.out.persistence.jpa.JournalProcessingRecordJpaRepository;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.domain.TransactionId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

@Repository
public class JournalPersistenceAdapter implements JournalPort {

    private final JournalProcessingRecordJpaRepository repository;
    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    public JournalPersistenceAdapter(
            JournalProcessingRecordJpaRepository repository,
            JdbcTemplate jdbc,
            JsonMapper jsonMapper
    ) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void append(JournalRecord record) {
        // Idempotent on attempt_key: redelivery after durable journal (or ambiguous commit) must not fail.
        jdbc.update(
                """
                INSERT INTO journal_processing_record (
                    journal_id, transaction_id, account_id, source_timestamp, received_at,
                    outcome, snapshot_effect, correlation_id, attempt_key, decision_context
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (attempt_key) DO NOTHING
                """,
                record.journalId(),
                record.transactionId() == null ? null : record.transactionId().value(),
                record.accountId() == null ? null : record.accountId().value(),
                record.sourceTimestamp(),
                java.sql.Timestamp.from(record.receivedAt()),
                record.outcome().name(),
                record.snapshotEffect().name(),
                record.correlationId(),
                record.attemptKey(),
                toJson(record.decisionContext())
        );
    }

    @Override
    public List<JournalRecord> findByTransactionId(TransactionId transactionId) {
        return repository.findByTransactionIdOrderByReceivedAtAsc(transactionId.value())
                .stream()
                .map(this::toRecord)
                .toList();
    }

    @Override
    public List<JournalRecord> findByAccountId(AccountId accountId) {
        return repository.findByAccountIdOrderByReceivedAtAsc(accountId.value())
                .stream()
                .map(this::toRecord)
                .toList();
    }

    private JournalRecord toRecord(JournalProcessingRecordEntity entity) {
        return new JournalRecord(
                entity.getJournalId(),
                entity.getTransactionId() == null ? null : new TransactionId(entity.getTransactionId()),
                entity.getAccountId() == null ? null : new AccountId(entity.getAccountId()),
                entity.getSourceTimestamp(),
                entity.getReceivedAt(),
                ProcessingOutcome.valueOf(entity.getOutcome()),
                SnapshotEffect.valueOf(entity.getSnapshotEffect()),
                entity.getCorrelationId(),
                entity.getAttemptKey(),
                fromJson(entity.getDecisionContext())
        );
    }

    private String toJson(Map<String, Object> map) {
        try {
            return jsonMapper.writeValueAsString(map);
        } catch (JacksonException e) {
            throw new IllegalStateException("Não foi possível serializar o contexto da decisão", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("Não foi possível desserializar o contexto da decisão", e);
        }
    }
}
