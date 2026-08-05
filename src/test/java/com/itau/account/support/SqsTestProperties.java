package com.itau.account.support;

import com.itau.account.bootstrap.SqsProperties;

/** Shared SqsProperties construction for unit tests after throughput-related fields were added. */
public final class SqsTestProperties {

    private SqsTestProperties() {
    }

    public static SqsProperties of(
            boolean enabled,
            String queueUrl,
            String endpointOverride,
            String region,
            int maxConcurrent,
            int waitTimeSeconds,
            int visibilityTimeoutSeconds,
            int maxReceiveCount,
            String expectedDlqArn,
            String topologyValidationMode
    ) {
        return new SqsProperties(
                enabled,
                queueUrl,
                endpointOverride,
                region,
                maxConcurrent,
                waitTimeSeconds,
                visibilityTimeoutSeconds,
                maxReceiveCount,
                expectedDlqArn,
                topologyValidationMode,
                "test-hmac-secret",
                2,
                45,
                3,
                64);
    }
}
