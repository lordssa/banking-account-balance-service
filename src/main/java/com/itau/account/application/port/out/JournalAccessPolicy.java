package com.itau.account.application.port.out;


public interface JournalAccessPolicy {
    boolean canReadJournal(String subjectId);

    boolean canReplay(String subjectId);
}
