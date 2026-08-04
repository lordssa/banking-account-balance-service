package com.itau.account.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EventOrderingTest {

    @Test
    void comparesTimestamps() {
        LocalDateTime older = LocalDateTime.of(2024, 1, 1, 0, 0, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2024, 1, 1, 0, 0, 0, 1000);
        assertThat(EventOrdering.isStrictlyNewer(newer, older)).isTrue();
        assertThat(EventOrdering.isStrictlyNewer(older, newer)).isFalse();
        assertThat(EventOrdering.isEqualTimestamp(older, older)).isTrue();
    }
}
