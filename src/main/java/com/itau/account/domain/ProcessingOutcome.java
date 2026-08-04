package com.itau.account.domain;

public enum ProcessingOutcome {
    ACCEPTED,
    DUPLICATE,
    STALE,
    CONFLICTING,
    INVALID,
    PERMANENTLY_FAILED
}
