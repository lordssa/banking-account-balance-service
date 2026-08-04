package com.itau.account.application.port.in;

public interface RequestJournalReplayCommand {
    void requestReplay(String subjectId);
}
