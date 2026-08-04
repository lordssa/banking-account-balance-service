package com.itau.account.application.usecase;

import com.itau.account.application.exception.JournalAccessDeniedException;
import com.itau.account.application.model.AdministrativeJournalActionInsert;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestJournalReplayUseCaseTest {

    @Mock JournalAccessPolicy accessPolicy;
    @Mock AdministrativeJournalActionPort administrativeActions;

    RequestJournalReplayUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new RequestJournalReplayUseCase(accessPolicy, administrativeActions);
    }

    @Test
    void deniedReplayIsAudited() {
        when(accessPolicy.canReplay("anonymous")).thenReturn(false);

        assertThatThrownBy(() -> useCase.requestReplay("anonymous"))
                .isInstanceOf(JournalAccessDeniedException.class);

        ArgumentCaptor<AdministrativeJournalActionInsert> captor =
                ArgumentCaptor.forClass(AdministrativeJournalActionInsert.class);
        verify(administrativeActions).record(captor.capture());
        assertThat(captor.getValue().actionType())
                .isEqualTo(AdministrativeJournalActionInsert.TYPE_JOURNAL_REPLAY);
        assertThat(captor.getValue().result()).isEqualTo(AdministrativeJournalActionInsert.RESULT_DENIED);
    }

    @Test
    void allowedReplayIsAuditedThenRejectedAsUnimplemented() {
        when(accessPolicy.canReplay("operator-1")).thenReturn(true);

        assertThatThrownBy(() -> useCase.requestReplay("operator-1"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("não está implementado");

        ArgumentCaptor<AdministrativeJournalActionInsert> captor =
                ArgumentCaptor.forClass(AdministrativeJournalActionInsert.class);
        verify(administrativeActions).record(captor.capture());
        assertThat(captor.getValue().result()).isEqualTo(AdministrativeJournalActionInsert.RESULT_ALLOWED);
    }
}
