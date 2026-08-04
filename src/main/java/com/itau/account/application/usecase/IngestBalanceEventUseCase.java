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
        if (processedTransactionPort.findOutcome(event.transactionId()).isPresent()) {
            return finish(event, attemptKey, correlationId, ProcessingOutcome.DUPLICATE, SnapshotEffect.UNCHANGED, "DUPLICATE_TRANSACTION");
        }

        var otherAtSameTs = processedTransactionPort.findOtherTransactionAt(
                event.accountId(), event.sourceTimestamp(), event.transactionId());
        if (otherAtSameTs.isPresent()) {
            return conflict(event, attemptKey, correlationId, otherAtSameTs.get());
        }

        var existingSnapshot = snapshotPort.findByAccountId(event.accountId());
        if (existingSnapshot.isPresent()
                && EventOrdering.isEqualTimestamp(existingSnapshot.get().sourceTimestamp(), event.sourceTimestamp())
                && !existingSnapshot.get().winningTransactionId().equals(event.transactionId())) {
            return conflict(event, attemptKey, correlationId, existingSnapshot.get().winningTransactionId());
        }

        boolean clearlyStale = existingSnapshot.isPresent()
                && !EventOrdering.isStrictlyNewer(event.sourceTimestamp(), existingSnapshot.get().sourceTimestamp());

        if (clearlyStale) {
            return claimThen(
                    event,
                    attemptKey,
                    correlationId,
                    ProcessingOutcome.STALE,
                    SnapshotEffect.UNCHANGED,
                    "STALE_OLDER_OR_EQUAL"
            );
        }

        ClaimResult claim = claim(event, ProcessingOutcome.ACCEPTED);
        if (claim == ClaimResult.DUPLICATE_TRANSACTION) {
            return finish(event, attemptKey, correlationId, ProcessingOutcome.DUPLICATE, SnapshotEffect.UNCHANGED, "DUPLICATE_TRANSACTION");
        }
        if (claim == ClaimResult.ACCOUNT_TIMESTAMP_TAKEN) {
            return conflictWithOccupant(event, attemptKey, correlationId);
        }

        boolean updated = snapshotPort.upsertIfNewer(event);
        if (updated) {
            return finish(event, attemptKey, correlationId, ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER");
        }

        // Concurrent newer event won the snapshot CAS — not an equal-timestamp race (guarded by unique claim).
        return finish(event, attemptKey, correlationId, ProcessingOutcome.STALE, SnapshotEffect.UNCHANGED, "STALE_LOST_RACE");
    }

    private IngestResult claimThen(
            BalanceEvent event,
            String attemptKey,
            String correlationId,
            ProcessingOutcome outcome,
            SnapshotEffect effect,
            String reasonCode
    ) {
        ClaimResult claim = claim(event, outcome);
        return switch (claim) {
            case INSERTED -> finish(event, attemptKey, correlationId, outcome, effect, reasonCode);
            case DUPLICATE_TRANSACTION -> finish(
                    event, attemptKey, correlationId, ProcessingOutcome.DUPLICATE, SnapshotEffect.UNCHANGED, "DUPLICATE_TRANSACTION");
            case ACCOUNT_TIMESTAMP_TAKEN -> conflictWithOccupant(event, attemptKey, correlationId);
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
