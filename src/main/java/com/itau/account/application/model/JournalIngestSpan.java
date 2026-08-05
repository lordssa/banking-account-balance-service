package com.itau.account.application.model;

import java.time.Duration;
import java.time.Instant;

public record JournalIngestSpan(
        long eventCount,
        Instant minReceivedAt,
        Instant maxReceivedAt,
        Double minReceivedAtEpochSeconds,
        Double maxReceivedAtEpochSeconds,
        Double spanSeconds,
        Double eps
) {
    public static JournalIngestSpan empty() {
        return new JournalIngestSpan(0, null, null, null, null, null, null);
    }

    public static JournalIngestSpan of(long eventCount, Instant minReceivedAt, Instant maxReceivedAt) {
        if (eventCount <= 0 || minReceivedAt == null || maxReceivedAt == null) {
            return empty();
        }
        double minEpoch = minReceivedAt.getEpochSecond() + minReceivedAt.getNano() / 1_000_000_000.0;
        double maxEpoch = maxReceivedAt.getEpochSecond() + maxReceivedAt.getNano() / 1_000_000_000.0;
        double span = Duration.between(minReceivedAt, maxReceivedAt).toNanos() / 1_000_000_000.0;
        Double rate = span >= 0.001 ? round1(eventCount / span) : null;
        return new JournalIngestSpan(eventCount, minReceivedAt, maxReceivedAt, minEpoch, maxEpoch, span, rate);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
