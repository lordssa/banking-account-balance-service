package com.itau.account.adapter.out.security;

import com.itau.account.application.port.out.JournalAccessPolicy;
import org.springframework.stereotype.Component;


@Component
public class DenyAllJournalAccessPolicy implements JournalAccessPolicy {

    @Override
    public boolean canReadJournal(String subjectId) {
        return false;
    }

    @Override
    public boolean canReplay(String subjectId) {
        return false;
    }
}
