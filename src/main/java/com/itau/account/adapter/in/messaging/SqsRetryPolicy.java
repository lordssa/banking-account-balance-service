package com.itau.account.adapter.in.messaging;

/**
 * Classifies SQS delivery failures and detects retry exhaustion (app-level maxReceiveCount -> permanent journal).
 */
public final class SqsRetryPolicy {

    public enum FailureClass {
        TRANSIENT,
        PERMANENT
    }

    private final int maxReceiveCount;

    public SqsRetryPolicy(int maxReceiveCount) {
        this.maxReceiveCount = Math.max(1, maxReceiveCount);
    }

    public int maxReceiveCount() {
        return maxReceiveCount;
    }

    public FailureClass classify(Throwable error) {
        if (error instanceof InvalidFinancialEventException) {
            return FailureClass.PERMANENT;
        }
        return FailureClass.TRANSIENT;
    }

    public boolean isRetryExhausted(int approximateReceiveCount) {
        return approximateReceiveCount >= maxReceiveCount;
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
