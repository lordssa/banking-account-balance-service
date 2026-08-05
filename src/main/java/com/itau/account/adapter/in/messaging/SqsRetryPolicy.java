package com.itau.account.adapter.in.messaging;

/**
 * Observes SQS ApproximateReceiveCount relative to the expected broker RedrivePolicy
 * {@code maxReceiveCount}. Does not authorize message deletion — the broker is the sole
 * authority for moving messages to the DLQ.
 */
public final class SqsRetryPolicy {

    public enum FailureClass {
        TRANSIENT,
        PERMANENT
    }

    private final int expectedMaxReceiveCount;

    public SqsRetryPolicy(int expectedMaxReceiveCount) {
        this.expectedMaxReceiveCount = Math.max(1, expectedMaxReceiveCount);
    }

    public int maxReceiveCount() {
        return expectedMaxReceiveCount;
    }

    public FailureClass classify(Throwable error) {
        if (error instanceof InvalidFinancialEventException) {
            return FailureClass.PERMANENT;
        }
        return FailureClass.TRANSIENT;
    }

    public boolean isAtOrAboveBrokerThreshold(int approximateReceiveCount) {
        return approximateReceiveCount >= expectedMaxReceiveCount;
    }

    public boolean isRetryExhausted(int approximateReceiveCount) {
        return isAtOrAboveBrokerThreshold(approximateReceiveCount);
    }

    public int parseReceiveCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}
