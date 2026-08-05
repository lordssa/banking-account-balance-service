package com.itau.account.adapter.in.http;

import com.itau.account.application.model.JournalIngestSpan;
import com.itau.account.application.model.JournalRecord;
import com.itau.account.application.port.in.RequestJournalReplayCommand;
import com.itau.account.application.port.in.TraceJournalQuery;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.domain.TransactionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JournalControllerTest {

    @Mock TraceJournalQuery traceJournalQuery;
    @Mock RequestJournalReplayCommand requestJournalReplayCommand;
    @InjectMocks JournalController controller;

    @Test
    void byTransactionAndAccountAndReplay() {
        UUID tx = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        JournalRecord row = JournalRecord.create(
                TransactionId.parse(tx.toString()),
                AccountId.parse(account.toString()),
                LocalDateTime.MIN,
                Instant.EPOCH,
                ProcessingOutcome.INVALID,
                SnapshotEffect.UNCHANGED,
                "c",
                "a",
                Map.of());
        when(traceJournalQuery.byTransaction(eq(JournalController.ANONYMOUS_SUBJECT), any()))
                .thenReturn(List.of(row));
        when(traceJournalQuery.byAccount(eq(JournalController.ANONYMOUS_SUBJECT), any()))
                .thenReturn(List.of(row));

        assertThat(controller.byTransaction(tx.toString()).getBody()).hasSize(1);
        assertThat(controller.byAccount(account.toString()).getBody()).hasSize(1);
        assertThat(controller.replay().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(requestJournalReplayCommand).requestReplay(JournalController.ANONYMOUS_SUBJECT);
    }

    @Test
    void ingestSpanDelegatesToQuery() {
        UUID account = UUID.randomUUID();
        Instant since = Instant.parse("2026-08-05T14:00:00Z");
        JournalIngestSpan span = JournalIngestSpan.of(
                2,
                Instant.parse("2026-08-05T14:00:01Z"),
                Instant.parse("2026-08-05T14:00:03Z"));
        when(traceJournalQuery.ingestSpan(eq(JournalController.ANONYMOUS_SUBJECT), eq(since), any()))
                .thenReturn(span);

        assertThat(controller.ingestSpan(since, List.of(account.toString())).getBody()).isEqualTo(span);
    }
}
