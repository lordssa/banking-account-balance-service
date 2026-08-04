package com.itau.account.application.usecase;

import com.itau.account.application.port.out.JournalPort;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.itau.account.application.model.JournalRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RejectInvalidEventUseCaseTest {

    @Mock JournalPort journalPort;

    RejectInvalidEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RejectInvalidEventUseCase(journalPort);
    }

    @Test
    void journalsInvalidOutcome() {
        IngestResult result = useCase.reject("attempt-1", "corr-1", "INVALID_PAYLOAD");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.INVALID);
        assertThat(result.snapshotEffect()).isEqualTo(SnapshotEffect.NOT_APPLICABLE);
        assertThat(result.reasonCode()).isEqualTo("INVALID_PAYLOAD");

        ArgumentCaptor<JournalRecord> captor = ArgumentCaptor.forClass(JournalRecord.class);
        verify(journalPort).append(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(ProcessingOutcome.INVALID);
        assertThat(captor.getValue().attemptKey()).isEqualTo("attempt-1");
        assertThat(captor.getValue().correlationId()).isEqualTo("corr-1");
    }

    @Test
    void journalsPermanentlyFailedOnRetryExhaustion() {
        IngestResult result = useCase.reject("attempt-5", "corr-5", "RETRY_EXHAUSTED");

        assertThat(result.outcome()).isEqualTo(ProcessingOutcome.PERMANENTLY_FAILED);
        ArgumentCaptor<JournalRecord> captor = ArgumentCaptor.forClass(JournalRecord.class);
        verify(journalPort).append(captor.capture());
        assertThat(captor.getValue().outcome()).isEqualTo(ProcessingOutcome.PERMANENTLY_FAILED);
    }
}
