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
        @DefaultValue("5") int maxReceiveCount
) {
}
