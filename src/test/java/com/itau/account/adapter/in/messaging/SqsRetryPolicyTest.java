package com.itau.account.adapter.in.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqsRetryPolicyTest {

    private final SqsRetryPolicy policy = new SqsRetryPolicy(5);

    @Test
    void classifiesPayloadParseAsPermanentAndOthersTransient() {
        assertThat(policy.classify(new InvalidFinancialEventException("bad")))
                .isEqualTo(SqsRetryPolicy.FailureClass.PERMANENT);
        assertThat(policy.classify(new IllegalArgumentException("internal")))
                .isEqualTo(SqsRetryPolicy.FailureClass.TRANSIENT);
        assertThat(policy.classify(new RuntimeException("db"))).isEqualTo(SqsRetryPolicy.FailureClass.TRANSIENT);
    }

    @Test
    void observesBrokerThresholdWithoutAuthorizingDeletion() {
        assertThat(policy.isAtOrAboveBrokerThreshold(4)).isFalse();
        assertThat(policy.isAtOrAboveBrokerThreshold(5)).isTrue();
        assertThat(policy.isRetryExhausted(5)).isTrue();
        assertThat(policy.maxReceiveCount()).isEqualTo(5);
        assertThat(policy.parseReceiveCount("3")).isEqualTo(3);
        assertThat(policy.parseReceiveCount("x")).isEqualTo(1);
    }
}
