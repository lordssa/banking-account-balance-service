package com.itau.account.adapter.out.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionMetricsTest {

    @Test
    void metricLabelsExcludeHighCardinalityIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionMetrics metrics = new IngestionMetrics(registry);
        metrics.recordRetry();
        metrics.recordAckFailure();
        metrics.recordUnexpectedFailure();
        metrics.recordRetryExhausted();
        metrics.setTopologyValid(true);

        for (Meter meter : registry.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertThat(tag.getKey()).isNotIn("transactionId", "accountId", "messageId", "correlationId");
                assertThat(tag.getValue()).doesNotContain("{");
            });
        }
    }
}
