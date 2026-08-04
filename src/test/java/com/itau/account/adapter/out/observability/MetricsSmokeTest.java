package com.itau.account.adapter.out.observability;

import com.itau.account.domain.ProcessingOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsSmokeTest {

    private SimpleMeterRegistry registry;
    private IngestionMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new IngestionMetrics(registry);
    }

    @Test
    void registersLifecycleGauges() {
        metrics.markConsumerStarted(8);
        metrics.beginInFlight();

        assertThat(registry.find(AccountMetricNames.CONSUMER_RUNNING).gauge()).isNotNull();
        assertThat(registry.find(AccountMetricNames.CONSUMER_RUNNING).gauge().value()).isEqualTo(1.0);
        assertThat(registry.find(AccountMetricNames.CONSUMER_PERMITS_AVAILABLE).gauge().value()).isEqualTo(8.0);
        assertThat(registry.find(AccountMetricNames.CONSUMER_IN_FLIGHT).gauge().value()).isEqualTo(1.0);
    }

    @Test
    void recordsOutcomesRetriesLatencyAndBalanceAge() {
        metrics.recordOutcome(ProcessingOutcome.ACCEPTED);
        metrics.recordOutcome(ProcessingOutcome.CONFLICTING);
        metrics.recordRetry();
        metrics.recordRetryExhausted();
        metrics.recordPollError();
        metrics.recordProcessingLatency(Duration.ofMillis(25));
        metrics.recordReturnedBalanceAge(Duration.ofSeconds(90));
        metrics.timeDb("snapshot.find", () -> "ok");

        assertThat(registry.counter(AccountMetricNames.INGESTION_EVENTS, "outcome", "ACCEPTED").count()).isEqualTo(1.0);
        assertThat(registry.counter(AccountMetricNames.INGESTION_CONFLICTS).count()).isEqualTo(1.0);
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRIES).count()).isEqualTo(1.0);
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRY_EXHAUSTED).count()).isEqualTo(1.0);
        assertThat(registry.counter(AccountMetricNames.INGESTION_PERMANENT_FAILURES).count()).isEqualTo(1.0);
        assertThat(registry.find(AccountMetricNames.INGESTION_PROCESSING).timer()).isNotNull();
        assertThat(registry.find(AccountMetricNames.BALANCE_RETURNED_AGE_SECONDS).timer()).isNotNull();
        assertThat(registry.find(AccountMetricNames.DB_OPERATION).tag("operation", "snapshot.find").timer()).isNotNull();
    }

    @Test
    void recordsDbFailures() {
        try {
            metrics.timeDb("snapshot.upsert_if_newer", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException ignored) {
            // expected
        }
        assertThat(registry.counter(AccountMetricNames.DB_OPERATION_FAILURES, "operation", "snapshot.upsert_if_newer").count())
                .isEqualTo(1.0);
    }
}
