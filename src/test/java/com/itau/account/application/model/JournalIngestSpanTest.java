package com.itau.account.application.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JournalIngestSpanTest {

    @Test
    void computesSpanAndRoundedEps() {
        JournalIngestSpan span = JournalIngestSpan.of(
                1950,
                Instant.parse("2026-08-05T14:48:42.237820Z"),
                Instant.parse("2026-08-05T14:49:07.057042Z"));

        assertThat(span.eventCount()).isEqualTo(1950);
        assertThat(span.spanSeconds()).isEqualTo(24.819222);
        assertThat(span.eps()).isEqualTo(78.6);
    }

    @Test
    void emptyWhenCountOrBoundsMissing() {
        assertThat(JournalIngestSpan.of(0, Instant.EPOCH, Instant.EPOCH.plusSeconds(1)).eventCount()).isZero();
        assertThat(JournalIngestSpan.of(10, null, Instant.EPOCH).eps()).isNull();
    }
}
