package com.itau.account.adapter.out.security;

import com.itau.account.application.port.out.JournalAccessPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "account.journal.allow-anonymous-read", havingValue = "false", matchIfMissing = true)
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
