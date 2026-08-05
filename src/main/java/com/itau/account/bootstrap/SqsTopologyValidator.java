package com.itau.account.bootstrap;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates source-queue RedrivePolicy against expected DLQ ARN and maxReceiveCount.
 * Hot paths (poller, health) read only an in-memory cache; {@code GetQueueAttributes}
 * runs once at startup and on a background refresh schedule.
 */
@Component
@ConditionalOnProperty(prefix = "account.sqs", name = "enabled", havingValue = "true")
public class SqsTopologyValidator {

    private static final Logger log = LoggerFactory.getLogger(SqsTopologyValidator.class);
    private static final Pattern DEAD_LETTER_ARN = Pattern.compile("\"deadLetterTargetArn\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MAX_RECEIVE = Pattern.compile("\"maxReceiveCount\"\\s*:\\s*\"?(\\d+)\"?");

    private final SqsClient sqsClient;
    private final SqsProperties properties;
    private final IngestionMetrics metrics;
    private final AtomicBoolean refreshScheduled = new AtomicBoolean(false);

    private volatile boolean lastValid;
    private volatile String lastReason = "not-checked";
    private ScheduledExecutorService scheduler;

    public SqsTopologyValidator(SqsClient sqsClient, SqsProperties properties, IngestionMetrics metrics) {
        this.sqsClient = sqsClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    @PostConstruct
    void start() {
        if (!properties.topologyObserveOrEnforce()) {
            applyCache(true, "off");
            return;
        }
        refreshNow();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sqs-topology-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduleNextRefresh();
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Synchronous refresh used at startup, by the background ticker, and in unit tests.
     * Must not be called from the receive hot path.
     */
    public ValidationResult validate() {
        return refreshNow();
    }

    /** Cached snapshot for health / readiness — never triggers SQS I/O. */
    public ValidationResult cachedValidation() {
        return new ValidationResult(lastValid, lastReason);
    }

    public boolean lastValid() {
        return lastValid;
    }

    public String lastReason() {
        return lastReason;
    }

    /**
     * Enforce mode gates on the cached result only. Observe/off never block consumption.
     */
    public boolean allowsSafeConsumption() {
        if (!properties.topologyEnforce()) {
            return true;
        }
        return lastValid;
    }

    ValidationResult refreshNow() {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) {
            return fail("missing-source-queue-url");
        }
        if (properties.expectedDlqArn() == null || properties.expectedDlqArn().isBlank()) {
            return fail("missing-expected-dlq-arn");
        }
        try {
            Map<QueueAttributeName, String> attrs = sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                            .queueUrl(properties.queueUrl())
                            .attributeNames(QueueAttributeName.REDRIVE_POLICY)
                            .build())
                    .attributes();
            String redrive = attrs.get(QueueAttributeName.REDRIVE_POLICY);
            if (redrive == null || redrive.isBlank()) {
                return fail("missing-redrive-policy");
            }
            String dlqArn = extract(DEAD_LETTER_ARN, redrive);
            String maxReceive = extract(MAX_RECEIVE, redrive);
            if (dlqArn == null) {
                return fail("missing-dead-letter-target");
            }
            if (!dlqArn.equals(properties.expectedDlqArn())) {
                return fail("dlq-arn-mismatch");
            }
            if (maxReceive == null) {
                return fail("missing-max-receive-count");
            }
            int brokerMax = Integer.parseInt(maxReceive);
            if (brokerMax != properties.maxReceiveCount()) {
                return fail("max-receive-count-mismatch");
            }
            return ok();
        } catch (Exception ex) {
            log.warn("SQS topology validation failed reason=unreachable-or-denied");
            return fail("queue-attributes-unavailable");
        }
    }

    private void scheduleNextRefresh() {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        if (!refreshScheduled.compareAndSet(false, true)) {
            return;
        }
        long delaySec = lastValid
                ? Math.max(1, properties.topologyRefreshValidSeconds())
                : Math.max(1, properties.topologyRefreshInvalidSeconds());
        scheduler.schedule(() -> {
            refreshScheduled.set(false);
            try {
                refreshNow();
            } finally {
                scheduleNextRefresh();
            }
        }, delaySec, TimeUnit.SECONDS);
    }

    private ValidationResult ok() {
        applyCache(true, "ok");
        return new ValidationResult(true, "ok");
    }

    private ValidationResult fail(String reason) {
        applyCache(false, reason);
        log.warn("SQS topology invalid reason={}", reason);
        return new ValidationResult(false, reason);
    }

    private void applyCache(boolean valid, String reason) {
        lastValid = valid;
        lastReason = reason;
        metrics.setTopologyValid(valid);
    }

    private static String extract(Pattern pattern, String redrive) {
        Matcher m = pattern.matcher(redrive);
        return m.find() ? m.group(1) : null;
    }

    public record ValidationResult(boolean valid, String reason) {
    }
}
