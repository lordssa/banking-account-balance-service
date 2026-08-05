package com.itau.account.adapter.in.messaging;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.bootstrap.SqsProperties;
import com.itau.account.bootstrap.SqsTopologyValidator;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SQS consumer. Acknowledges only after durable business outcomes
 * (ACCEPTED/DUPLICATE/STALE/CONFLICTING). Technical and validation failures leave the message
 * unacked so the broker RedrivePolicy can isolate it on the DLQ.
 * Equal-timestamp CONFLICTING is a business path (journal + conflict store), not technical DLQ isolation.
 *
 * <p>Topology gating reads only the in-memory cache (no GetQueueAttributes on the receive path).
 * Multiple concurrent long-poll receivers and DeleteMessageBatch reduce SQS round trips.
 */
@Component
@DependsOn("sqsTopologyValidator")
@ConditionalOnProperty(prefix = "account.sqs", name = "enabled", havingValue = "true")
public class SqsBalanceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsBalanceEventConsumer.class);
    static final long DEFAULT_TOPOLOGY_PAUSE_MS = 2_000L;
    static final long ACK_FLUSH_INTERVAL_MS = 50L;

    private final SqsClient sqsClient;
    private final SqsProperties properties;
    private final FinancialEventMapper mapper;
    private final IngestBalanceEventCommand ingestCommand;
    private final RejectInvalidEventCommand rejectInvalidCommand;
    private final IngestionMetrics metrics;
    private final SqsTopologyValidator topologyValidator;
    private final SqsRetryPolicy retryPolicy;
    private final SqsDeleteBatcher deleteBatcher;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean pollingPausedForTopology = new AtomicBoolean(false);
    private final Semaphore inFlight;
    private ExecutorService poller;
    private ScheduledExecutorService ackFlusher;
    volatile long topologyPauseMs = DEFAULT_TOPOLOGY_PAUSE_MS;

    public SqsBalanceEventConsumer(
            SqsClient sqsClient,
            SqsProperties properties,
            FinancialEventMapper mapper,
            IngestBalanceEventCommand ingestCommand,
            RejectInvalidEventCommand rejectInvalidCommand,
            IngestionMetrics metrics,
            SqsTopologyValidator topologyValidator
    ) {
        this.sqsClient = sqsClient;
        this.properties = properties;
        this.mapper = mapper;
        this.ingestCommand = ingestCommand;
        this.rejectInvalidCommand = rejectInvalidCommand;
        this.metrics = metrics;
        this.topologyValidator = topologyValidator;
        this.retryPolicy = new SqsRetryPolicy(properties.maxReceiveCount());
        this.inFlight = new Semaphore(Math.max(1, properties.maxConcurrent()));
        this.deleteBatcher = new SqsDeleteBatcher(sqsClient, properties.queueUrl(), metrics);
    }

    @PostConstruct
    void start() {
        metrics.markConsumerStarted(Math.max(1, properties.maxConcurrent()));
        if (properties.topologyEnforce() && !topologyValidator.allowsSafeConsumption()) {
            pollingPausedForTopology.set(true);
            log.warn(
                    "SQS consumer deferred until enforced topology is valid queue={} reason={}",
                    properties.queueUrl(),
                    topologyValidator.lastReason());
        } else {
            log.info(
                    "SQS consumer started queue={} receivers={}",
                    properties.queueUrl(),
                    Math.max(1, properties.receiverCount()));
        }
        poller = Executors.newVirtualThreadPerTaskExecutor();
        int receivers = Math.max(1, properties.receiverCount());
        for (int i = 0; i < receivers; i++) {
            poller.submit(this::pollLoop);
        }
        ackFlusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sqs-ack-flusher");
            t.setDaemon(true);
            return t;
        });
        ackFlusher.scheduleWithFixedDelay(
                deleteBatcher::flush, ACK_FLUSH_INTERVAL_MS, ACK_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        metrics.markConsumerStopped();
        if (ackFlusher != null) {
            ackFlusher.shutdownNow();
        }
        deleteBatcher.flush();
        if (poller != null) {
            poller.close();
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                runPollIteration();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("SQS poll failure", ex);
                metrics.recordPollError();
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * One poll-loop body iteration (topology gate + receive). Package-visible for tests.
     */
    void runPollIteration() throws InterruptedException {
        if (!topologyAllowsPolling()) {
            if (pollingPausedForTopology.compareAndSet(false, true)) {
                log.warn(
                        "SQS polling paused — enforced topology invalid reason={}",
                        topologyValidator.lastReason());
            }
            Thread.sleep(topologyPauseMs);
            return;
        }
        if (pollingPausedForTopology.compareAndSet(true, false)) {
            log.info("SQS polling resumed — enforced topology valid queue={}", properties.queueUrl());
        }
        pollOnce();
    }

    /**
     * Enforce mode requires a valid cached RedrivePolicy/DLQ relationship before ReceiveMessage.
     * Observe/off never block consumption. Cache-only — no SQS GetQueueAttributes.
     */
    boolean topologyAllowsPolling() {
        return topologyValidator.allowsSafeConsumption();
    }

    void pollOnce() {
        if (!topologyAllowsPolling()) {
            return;
        }
        int reserved = reservePermitsUpTo(10);
        if (reserved <= 0) {
            waitForCapacity();
            return;
        }
        metrics.updatePermitsAvailable(inFlight.availablePermits());

        List<Message> messages;
        try {
            var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(reserved)
                    .waitTimeSeconds(properties.waitTimeSeconds())
                    .visibilityTimeout(properties.visibilityTimeoutSeconds())
                    .messageSystemAttributeNames(
                            MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                            MessageSystemAttributeName.SENT_TIMESTAMP)
                    .messageAttributeNames(
                            MessageEnvelopeCorrelation.ATTR_EVENT_CORRELATION_ID,
                            MessageEnvelopeCorrelation.ATTR_CORRELATION_ID)
                    .build());
            messages = response.messages();
        } catch (RuntimeException ex) {
            inFlight.release(reserved);
            metrics.updatePermitsAvailable(inFlight.availablePermits());
            throw ex;
        }

        int assigned = Math.min(messages.size(), reserved);
        int unused = reserved - assigned;
        if (unused > 0) {
            inFlight.release(unused);
            metrics.updatePermitsAvailable(inFlight.availablePermits());
        }
        if (messages.size() > reserved) {
            log.warn(
                    "SQS returned {} messages after reserving {} — leaving extras unacked (no visibility reset)",
                    messages.size(),
                    reserved);
        }
        for (int i = 0; i < assigned; i++) {
            Message message = messages.get(i);
            Thread.startVirtualThread(() -> {
                metrics.beginInFlight();
                try {
                    processMessage(message);
                } finally {
                    inFlight.release();
                    metrics.endInFlight();
                    metrics.updatePermitsAvailable(inFlight.availablePermits());
                }
            });
        }
    }

    /**
     * Take up to {@code max} in-flight slots before ReceiveMessage so concurrent receivers cannot
     * over-fetch and bounce healthy messages with visibility 0 (receive-count++ / DLQ risk).
     */
    int reservePermitsUpTo(int max) {
        int reserved = 0;
        int limit = Math.max(1, Math.min(10, max));
        while (reserved < limit && inFlight.tryAcquire()) {
            reserved++;
        }
        return reserved;
    }

    private void waitForCapacity() {
        try {
            if (inFlight.tryAcquire(1, TimeUnit.SECONDS)) {
                inFlight.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    void processMessage(Message message) {
        Instant started = Instant.now();
        MessageEnvelopeCorrelation.Resolved envelope =
                MessageEnvelopeCorrelation.resolve(message, properties.envelopeHmacSecret());
        String correlationId = envelope.correlationId();
        int receiveCount = retryPolicy.parseReceiveCount(
                message.attributesAsStrings().getOrDefault("ApproximateReceiveCount", "1"));
        String attemptKey = message.messageId() + ":" + receiveCount;
        Map<String, Object> transportCtx = transportContext(message, receiveCount, envelope);
        MDC.put("correlationId", correlationId);
        BalanceEvent event = null;
        try {
            try {
                event = mapper.parse(message.body(), Instant.now());
            } catch (InvalidFinancialEventException ex) {
                journalInvalidBestEffort(attemptKey, correlationId, transportCtx, ex);
                // Leave unacked so broker RedrivePolicy preserves the raw envelope on the DLQ.
                return;
            }

            IngestResult result = ingestCommand.ingest(event, attemptKey, correlationId);
            metrics.recordOutcome(result.outcome());
            acknowledgeAfterDurableOutcome(message);
        } catch (Exception ex) {
            handleProcessingFailure(message, event, attemptKey, correlationId, receiveCount, transportCtx, ex);
        } finally {
            metrics.recordProcessingLatency(Duration.between(started, Instant.now()));
            MDC.remove("correlationId");
        }
    }

    private void handleProcessingFailure(
            Message message,
            BalanceEvent event,
            String attemptKey,
            String correlationId,
            int receiveCount,
            Map<String, Object> transportCtx,
            Exception ex
    ) {
        if (retryPolicy.isAtOrAboveBrokerThreshold(receiveCount)) {
            journalPermanentlyFailedBestEffort(attemptKey, correlationId, transportCtx, event);
            metrics.recordRetryExhausted();
            log.warn("Observed broker receive threshold after ingest failure attemptKey={} — leaving unacked for DLQ",
                    attemptKey, ex);
            return;
        }
        metrics.recordRetry();
        log.warn("Transient ingest failure attemptKey={}", attemptKey);
    }

    private void journalInvalidBestEffort(
            String attemptKey,
            String correlationId,
            Map<String, Object> transportCtx,
            InvalidFinancialEventException cause
    ) {
        try {
            rejectInvalidCommand.reject(attemptKey, correlationId, "INVALID_PAYLOAD", transportCtx, null);
            metrics.recordOutcome(ProcessingOutcome.INVALID);
            log.warn("Invalid event journaled attemptKey={} — not acknowledging (broker isolation)",
                    attemptKey, cause);
        } catch (Exception journalEx) {
            metrics.recordUnexpectedFailure();
            log.warn("Invalid-journal write failed attemptKey={} — leaving unacked", attemptKey, journalEx);
        }
    }

    private void journalPermanentlyFailedBestEffort(
            String attemptKey,
            String correlationId,
            Map<String, Object> transportCtx,
            BalanceEvent event
    ) {
        try {
            rejectInvalidCommand.reject(attemptKey, correlationId, "RETRY_EXHAUSTED", transportCtx, event);
        } catch (Exception journalEx) {
            log.warn("Best-effort PERMANENTLY_FAILED journal failed attemptKey={}", attemptKey, journalEx);
        }
    }

    private void acknowledgeAfterDurableOutcome(Message message) {
        deleteBatcher.enqueue(message);
    }

    void flushAcknowledgements() {
        deleteBatcher.flush();
    }

    private static Map<String, Object> transportContext(
            Message message,
            int receiveCount,
            MessageEnvelopeCorrelation.Resolved envelope
    ) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("messageId", message.messageId());
        ctx.put("approximateReceiveCount", receiveCount);
        ctx.put("envelopeFingerprint", envelope.envelopeFingerprint());
        ctx.put("correlationSource", envelope.correlationSource());
        if (envelope.sentTimestampOrNull() != null) {
            ctx.put("sentTimestamp", envelope.sentTimestampOrNull());
        }
        return ctx;
    }

    boolean tryAcquireInFlightPermit() {
        return inFlight.tryAcquire();
    }

    void releaseInFlightPermit() {
        inFlight.release();
    }
}
