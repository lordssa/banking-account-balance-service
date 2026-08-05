package com.itau.account.adapter.out.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalAccessPolicyToggleTest {

    @Test
    void anonymousReadAllowsJournalButNotReplay() {
        AllowAnonymousJournalReadPolicy policy = new AllowAnonymousJournalReadPolicy();
        assertThat(policy.canReadJournal("anonymous")).isTrue();
        assertThat(policy.canReplay("anonymous")).isFalse();
    }

    @Test
    void denyAllBlocksJournalAndReplay() {
        DenyAllJournalAccessPolicy policy = new DenyAllJournalAccessPolicy();
        assertThat(policy.canReadJournal("anonymous")).isFalse();
        assertThat(policy.canReplay("anonymous")).isFalse();
    }
}
