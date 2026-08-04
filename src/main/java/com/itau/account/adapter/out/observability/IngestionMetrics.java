package com.itau.account.adapter.out.observability;

import com.itau.account.domain.ProcessingOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Micrometer facade for ingestion, consumer lifecycle, and balance-age signals.
 */
@Component
public class IngestionMetrics {

    private final MeterRegistry registry;
    private final AtomicBoolean consumerRunning = new AtomicBoolean(false);
    private final AtomicInteger consumerInFlight = new AtomicInteger(0);
    private final AtomicInteger permitsAvailable = new AtomicInteger(0);

    public IngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(AccountMetricNames.CONSUMER_RUNNING, consumerRunning, b -> b.get() ? 1.0 : 0.0)
                .description("1 when SQS consumer poll loop is marked running")
                .register(registry);
        Gauge.builder(AccountMetricNames.CONSUMER_IN_FLIGHT, consumerInFlight, AtomicInteger::get)
                .description("Messages currently being processed")
                .register(registry);
        Gauge.builder(AccountMetricNames.CONSUMER_PERMITS_AVAILABLE, permitsAvailable, AtomicInteger::get)
                .description("Available concurrency permits")
                .register(registry);
    }

    public void markConsumerStarted(int maxConcurrent) {
        consumerRunning.set(true);
        permitsAvailable.set(maxConcurrent);
    }

    public void markConsumerStopped() {
        consumerRunning.set(false);
        consumerInFlight.set(0);
    }

    public void updatePermitsAvailable(int available) {
        permitsAvailable.set(available);
    }

    public void beginInFlight() {
        consumerInFlight.incrementAndGet();
    }

    public void endInFlight() {
        consumerInFlight.updateAndGet(v -> Math.max(0, v - 1));
    }

    public void recordOutcome(ProcessingOutcome outcome) {
        Counter.builder(AccountMetricNames.INGESTION_EVENTS)
                .tag("outcome", outcome.name())
                .register(registry)
                .increment();
        if (outcome == ProcessingOutcome.CONFLICTING) {
            Counter.builder(AccountMetricNames.INGESTION_CONFLICTS).register(registry).increment();
        }
        if (outcome == ProcessingOutcome.PERMANENTLY_FAILED) {
            Counter.builder(AccountMetricNames.INGESTION_PERMANENT_FAILURES).register(registry).increment();
        }
    }

    public void recordRetry() {
        Counter.builder(AccountMetricNames.INGESTION_EVENTS)
                .tag("outcome", "RETRY")
                .register(registry)
                .increment();
        Counter.builder(AccountMetricNames.INGESTION_RETRIES).register(registry).increment();
    }

    public void recordRetryExhausted() {
        Counter.builder(AccountMetricNames.INGESTION_RETRY_EXHAUSTED).register(registry).increment();
        recordOutcome(ProcessingOutcome.PERMANENTLY_FAILED);
    }

    public void recordPollError() {
        Counter.builder(AccountMetricNames.INGESTION_POLL_ERRORS).register(registry).increment();
    }

    public void recordProcessingLatency(Duration duration) {
        Timer.builder(AccountMetricNames.INGESTION_PROCESSING)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void recordReturnedBalanceAge(Duration age) {
        Timer.builder(AccountMetricNames.BALANCE_RETURNED_AGE_SECONDS)
                .publishPercentileHistogram()
                .register(registry)
                .record(age);
    }

    public <T> T timeDb(String operation, Supplier<T> work) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = work.get();
            sample.stop(Timer.builder(AccountMetricNames.DB_OPERATION)
                    .tag("operation", operation)
                    .tag("result", "success")
                    .register(registry));
            return result;
        } catch (RuntimeException ex) {
            sample.stop(Timer.builder(AccountMetricNames.DB_OPERATION)
                    .tag("operation", operation)
                    .tag("result", "failure")
                    .register(registry));
            Counter.builder(AccountMetricNames.DB_OPERATION_FAILURES)
                    .tag("operation", operation)
                    .register(registry)
                    .increment();
            throw ex;
        }
    }

    public void timeDbVoid(String operation, Runnable work) {
        timeDb(operation, () -> {
            work.run();
            return null;
        });
    }

    MeterRegistry registry() {
        return registry;
    }
}
