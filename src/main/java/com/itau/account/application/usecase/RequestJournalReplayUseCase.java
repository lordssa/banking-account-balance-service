package com.itau.account.application.usecase;

import com.itau.account.application.exception.JournalAccessDeniedException;
import com.itau.account.application.model.AdministrativeJournalActionInsert;
import com.itau.account.application.port.in.RequestJournalReplayCommand;
import com.itau.account.application.port.out.AdministrativeJournalActionPort;
import com.itau.account.application.port.out.JournalAccessPolicy;

import java.util.Map;

public class RequestJournalReplayUseCase implements RequestJournalReplayCommand {

    private final JournalAccessPolicy accessPolicy;
    private final AdministrativeJournalActionPort administrativeActions;

    public RequestJournalReplayUseCase(
            JournalAccessPolicy accessPolicy,
            AdministrativeJournalActionPort administrativeActions
    ) {
        this.accessPolicy = accessPolicy;
        this.administrativeActions = administrativeActions;
    }

    @Override
    public void requestReplay(String subjectId) {
        boolean allowed = accessPolicy.canReplay(subjectId);
        administrativeActions.record(AdministrativeJournalActionInsert.of(
                AdministrativeJournalActionInsert.TYPE_JOURNAL_REPLAY,
                subjectId,
                Map.of(),
                allowed
                        ? AdministrativeJournalActionInsert.RESULT_ALLOWED
                        : AdministrativeJournalActionInsert.RESULT_DENIED
        ));
        if (!allowed) {
            throw new JournalAccessDeniedException();
        }
        // Replay authority/bounds unresolved (P6/P7) — policy must allow before implementation lands.
        throw new UnsupportedOperationException("Replay do journal ainda não está implementado");
    }
}
