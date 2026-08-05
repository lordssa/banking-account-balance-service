package com.itau.account.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "account.sqs")
public record SqsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String queueUrl,
        @DefaultValue("") String endpointOverride,
        @DefaultValue("sa-east-1") String region,
        @DefaultValue("16") int maxConcurrent,
        @DefaultValue("10") int waitTimeSeconds,
        @DefaultValue("60") int visibilityTimeoutSeconds,
        @DefaultValue("5") int maxReceiveCount,
        @DefaultValue("") String expectedDlqArn,
        @DefaultValue("observe") String topologyValidationMode,
        /** HMAC key for privacy-safe envelope fingerprints when producers omit correlation attrs. */
        @DefaultValue("local-dev-only-change-me") String envelopeHmacSecret,
        /** Concurrent long-poll receive loops per pod. */
        @DefaultValue("2") int receiverCount,
        /** Background topology refresh interval while the cached result is valid. */
        @DefaultValue("45") int topologyRefreshValidSeconds,
        /** Background topology refresh interval while the cached result is invalid. */
        @DefaultValue("3") int topologyRefreshInvalidSeconds,
        /** Apache HTTP client max connections for the sync SQS client. */
        @DefaultValue("64") int httpMaxConnections
) {
    public boolean topologyEnforce() {
        return "enforce".equalsIgnoreCase(topologyValidationMode);
    }

    public boolean topologyObserveOrEnforce() {
        return topologyEnforce()
                || "observe".equalsIgnoreCase(topologyValidationMode);
    }
}
