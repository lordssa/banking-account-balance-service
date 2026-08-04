package com.itau.account.adapter.out.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DenyAllJournalAccessPolicyTest {

    private final DenyAllJournalAccessPolicy policy = new DenyAllJournalAccessPolicy();

    @Test
    void deniesReadAndReplay() {
        assertThat(policy.canReadJournal("any")).isFalse();
        assertThat(policy.canReplay("any")).isFalse();
    }
}
