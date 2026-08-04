package com.itau.account.application.port.out;

import com.itau.account.application.model.AdministrativeJournalActionInsert;

public interface AdministrativeJournalActionPort {
    void record(AdministrativeJournalActionInsert action);
}
