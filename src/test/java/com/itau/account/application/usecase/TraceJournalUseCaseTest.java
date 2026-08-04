package com.itau.account.application.usecase;

import com.itau.account.application.exception.JournalAccessDeniedException;
import com.itau.account.application.model.AdministrativeJournalActionInsert;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalAccessPolicy;
import com.itau.account.application.port.out.JournalPort;
import com.itau.account.domain.AccountId;
import com.itau.account.domain.TransactionId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceJournalUseCaseTest {

    @Mock JournalPort journalPort;
    @Mock JournalAccessPolicy accessPolicy;
    @Mock AdministrativeJournalActionPort administrativeActions;

    TraceJournalUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new TraceJournalUseCase(journalPort, accessPolicy, administrativeActions);
    }

    @Test
    void deniedReadIsAuditedAndDoesNotQueryJournal() {
        TransactionId tx = new TransactionId(UUID.randomUUID());
        when(accessPolicy.canReadJournal("anonymous")).thenReturn(false);

        assertThatThrownBy(() -> useCase.byTransaction("anonymous", tx))
                .isInstanceOf(JournalAccessDeniedException.class);

        ArgumentCaptor<AdministrativeJournalActionInsert> captor =
                ArgumentCaptor.forClass(AdministrativeJournalActionInsert.class);
        verify(administrativeActions).record(captor.capture());
        assertThat(captor.getValue().actionType())
                .isEqualTo(AdministrativeJournalActionInsert.TYPE_JOURNAL_READ_BY_TRANSACTION);
        assertThat(captor.getValue().result()).isEqualTo(AdministrativeJournalActionInsert.RESULT_DENIED);
        verify(journalPort, never()).findByTransactionId(any());
    }

    @Test
    void allowedReadQueriesJournalAfterAudit() {
        TransactionId tx = new TransactionId(UUID.randomUUID());
        when(accessPolicy.canReadJournal("operator-1")).thenReturn(true);
        when(journalPort.findByTransactionId(tx)).thenReturn(java.util.List.of());

        assertThat(useCase.byTransaction("operator-1", tx)).isEmpty();

        ArgumentCaptor<AdministrativeJournalActionInsert> captor =
                ArgumentCaptor.forClass(AdministrativeJournalActionInsert.class);
        verify(administrativeActions).record(captor.capture());
        assertThat(captor.getValue().result()).isEqualTo(AdministrativeJournalActionInsert.RESULT_ALLOWED);
        verify(journalPort).findByTransactionId(tx);
    }

    @Test
    void deniedAccountReadIsAudited() {
        AccountId accountId = new AccountId(UUID.randomUUID());
        when(accessPolicy.canReadJournal("anonymous")).thenReturn(false);

        assertThatThrownBy(() -> useCase.byAccount("anonymous", accountId))
                .isInstanceOf(JournalAccessDeniedException.class);

        ArgumentCaptor<AdministrativeJournalActionInsert> captor =
                ArgumentCaptor.forClass(AdministrativeJournalActionInsert.class);
        verify(administrativeActions).record(captor.capture());
        assertThat(captor.getValue().actionType())
                .isEqualTo(AdministrativeJournalActionInsert.TYPE_JOURNAL_READ_BY_ACCOUNT);
        assertThat(captor.getValue().result()).isEqualTo(AdministrativeJournalActionInsert.RESULT_DENIED);
        verify(journalPort, never()).findByAccountId(any());
    }

    @Test
    void allowedAccountReadQueriesJournal() {
        AccountId accountId = new AccountId(UUID.randomUUID());
        when(accessPolicy.canReadJournal("operator-1")).thenReturn(true);
        when(journalPort.findByAccountId(accountId)).thenReturn(java.util.List.of());

        assertThat(useCase.byAccount("operator-1", accountId)).isEmpty();
        verify(journalPort).findByAccountId(accountId);
    }
}
