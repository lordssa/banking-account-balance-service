package com.itau.account.bootstrap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness contribution for SQS topology. Always registered so the readiness group
 * can reference {@code sqsTopology} even when ingestion is disabled.
 * Observe mode never fails readiness; enforce mode fails on mismatch.
 * Reads only the cached topology result — never triggers GetQueueAttributes.
 */
@Component("sqsTopology")
public class SqsTopologyHealthIndicator implements HealthIndicator {

    private final ObjectProvider<SqsTopologyValidator> validator;
    private final SqsProperties properties;

    public SqsTopologyHealthIndicator(ObjectProvider<SqsTopologyValidator> validator, SqsProperties properties) {
        this.validator = validator;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up().withDetail("mode", "ingestion-disabled").build();
        }
        SqsTopologyValidator active = validator.getIfAvailable();
        if (active == null) {
            return Health.up().withDetail("mode", "validator-unavailable").build();
        }
        if (!properties.topologyObserveOrEnforce()) {
            return Health.up().withDetail("mode", "off").build();
        }
        SqsTopologyValidator.ValidationResult result = active.cachedValidation();
        if (result.valid()) {
            return Health.up()
                    .withDetail("mode", properties.topologyValidationMode())
                    .withDetail("reason", result.reason())
                    .build();
        }
        if (properties.topologyEnforce()) {
            return Health.down()
                    .withDetail("mode", "enforce")
                    .withDetail("reason", result.reason())
                    .build();
        }
        return Health.up()
                .withDetail("mode", "observe")
                .withDetail("reason", result.reason())
                .withDetail("observedInvalid", true)
                .build();
    }
}
