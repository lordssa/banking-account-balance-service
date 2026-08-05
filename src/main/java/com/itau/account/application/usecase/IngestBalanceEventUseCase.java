package com.itau.account.application.usecase;

import com.itau.account.application.model.ClaimResult;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.model.OrderingConflictInsert;
import com.itau.account.application.model.ProcessedTransactionInsert;
import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.out.AccountBalanceSnapshotPort;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.application.port.out.OrderingConflictPort;
import com.itau.account.application.port.out.ProcessedTransactionPort;
import com.itau.account.domain.AccountBalanceSnapshot;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.EventOrdering;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.domain.TransactionId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Claim-first ingest: the common ACCEPTED path is tryInsert → upsertIfNewer → journal.
 * Duplicate/conflict lookups run only when the idempotency/order claim fails.
 */
public class IngestBalanceEventUseCase implements IngestBalanceEventCommand {

    private final AccountBalanceSnapshotPort snapshotPort;
    private final ProcessedTransactionPort processedTransactionPort;
    private final JournalPort journalPort;
    private final OrderingConflictPort orderingConflictPort;

    public IngestBalanceEventUseCase(
            AccountBalanceSnapshotPort snapshotPort,
            ProcessedTransactionPort processedTransactionPort,
            JournalPort journalPort,
            OrderingConflictPort orderingConflictPort
    ) {
        this.snapshotPort = snapshotPort;
        this.processedTransactionPort = processedTransactionPort;
        this.journalPort = journalPort;
        this.orderingConflictPort = orderingConflictPort;
    }

    @Override
    public IngestResult ingest(BalanceEvent event, String attemptKey, String correlationId) {
        ClaimResult claim = claim(event, ProcessingOutcome.ACCEPTED);
        return switch (claim) {
            case DUPLICATE_TRANSACTION -> finish(
                    event, attemptKey, correlationId,
                    ProcessingOutcome.DUPLICATE, SnapshotEffect.UNCHANGED, "DUPLICATE_TRANSACTION");
            case ACCOUNT_TIMESTAMP_TAKEN -> conflictWithOccupant(event, attemptKey, correlationId);
            case INSERTED -> {
                boolean updated = snapshotPort.upsertIfNewer(event);
                if (updated) {
                    yield finish(
                            event, attemptKey, correlationId,
                            ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER");
                }
                // Older than current snapshot, or concurrent newer CAS winner.
                yield finish(
                        event, attemptKey, correlationId,
                        ProcessingOutcome.STALE, SnapshotEffect.UNCHANGED, "STALE_LOST_RACE");
            }
        };
    }

    private IngestResult conflictWithOccupant(BalanceEvent event, String attemptKey, String correlationId) {
        Optional<TransactionId> other = processedTransactionPort.findOtherTransactionAt(
                event.accountId(), event.sourceTimestamp(), event.transactionId());
        if (other.isEmpty()) {
            other = snapshotPort.findByAccountId(event.accountId())
                    .filter(s -> EventOrdering.isEqualTimestamp(s.sourceTimestamp(), event.sourceTimestamp()))
                    .map(AccountBalanceSnapshot::winningTransactionId)
                    .filter(id -> !id.equals(event.transactionId()));
        }
        if (other.isPresent()) {
            return conflict(event, attemptKey, correlationId, other.get());
        }
        // Unique claim lost but peer not resolvable — still isolate; never crash the consumer.
        ClaimResult claim = claim(event, ProcessingOutcome.CONFLICTING);
        if (claim == ClaimResult.DUPLICATE_TRANSACTION) {
            return finish(event, attemptKey, correlationId, ProcessingOutcome.DUPLICATE, SnapshotEffect.UNCHANGED, "DUPLICATE_TRANSACTION");
        }
        return finish(event, attemptKey, correlationId, ProcessingOutcome.CONFLICTING, SnapshotEffect.UNCHANGED, "EQUAL_TIMESTAMP_CONFLICT");
    }

    private ClaimResult claim(BalanceEvent event, ProcessingOutcome outcome) {
        return processedTransactionPort.tryInsert(new ProcessedTransactionInsert(
                event.transactionId(),
                event.accountId(),
                event.sourceTimestamp(),
                outcome
        ));
    }

    private IngestResult conflict(BalanceEvent event, String attemptKey, String correlationId, TransactionId otherTx) {
        orderingConflictPort.recordConflict(OrderingConflictInsert.between(
                event.accountId(),
                event.sourceTimestamp(),
                otherTx,
                event.transactionId()
        ));
        ClaimResult claim = claim(event, ProcessingOutcome.CONFLICTING);
        if (claim == ClaimResult.DUPLICATE_TRANSACTION) {
            return finish(event, attemptKey, correlationId, ProcessingOutcome.DUPLICATE, SnapshotEffect.UNCHANGED, "DUPLICATE_TRANSACTION");
        }
        return finish(event, attemptKey, correlationId, ProcessingOutcome.CONFLICTING, SnapshotEffect.UNCHANGED, "EQUAL_TIMESTAMP_CONFLICT");
    }

    private IngestResult finish(
            BalanceEvent event,
            String attemptKey,
            String correlationId,
            ProcessingOutcome outcome,
            SnapshotEffect effect,
            String reasonCode
    ) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("reasonCode", reasonCode);
        ctx.put("transactionId", event.transactionId().toString());
        ctx.put("accountId", event.accountId().toString());
        journalPort.append(JournalRecord.create(
                event.transactionId(),
                event.accountId(),
                event.sourceTimestamp(),
                event.receivedAt(),
                outcome,
                effect,
                correlationId,
                attemptKey,
                ctx
        ));
        return IngestResult.of(outcome, effect, reasonCode);
    }
}
