package com.itau.account.adapter.in.messaging;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.bootstrap.SqsProperties;
import com.itau.account.domain.BalanceEvent;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "account.sqs", name = "enabled", havingValue = "true")
public class SqsBalanceEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsBalanceEventConsumer.class);

    private final SqsClient sqsClient;
    private final SqsProperties properties;
    private final FinancialEventMapper mapper;
    private final IngestBalanceEventCommand ingestCommand;
    private final RejectInvalidEventCommand rejectInvalidCommand;
    private final IngestionMetrics metrics;
    private final SqsRetryPolicy retryPolicy;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Semaphore inFlight;
    private ExecutorService poller;

    public SqsBalanceEventConsumer(
            SqsClient sqsClient,
            SqsProperties properties,
            FinancialEventMapper mapper,
            IngestBalanceEventCommand ingestCommand,
            RejectInvalidEventCommand rejectInvalidCommand,
            IngestionMetrics metrics
    ) {
        this.sqsClient = sqsClient;
        this.properties = properties;
        this.mapper = mapper;
        this.ingestCommand = ingestCommand;
        this.rejectInvalidCommand = rejectInvalidCommand;
        this.metrics = metrics;
        this.retryPolicy = new SqsRetryPolicy(properties.maxReceiveCount());
        this.inFlight = new Semaphore(Math.max(1, properties.maxConcurrent()));
    }

    @PostConstruct
    void start() {
        metrics.markConsumerStarted(Math.max(1, properties.maxConcurrent()));
        poller = Executors.newVirtualThreadPerTaskExecutor();
        poller.submit(this::pollLoop);
        log.info("SQS consumer started queue={}", properties.queueUrl());
    }

    @PreDestroy
    void stop() {
        running.set(false);
        metrics.markConsumerStopped();
        if (poller != null) {
            poller.close();
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                pollOnce();
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

    void pollOnce() {
        int capacity = Math.min(10, inFlight.availablePermits());
        if (capacity <= 0) {
            waitForCapacity();
            return;
        }

        var response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .maxNumberOfMessages(capacity)
                .waitTimeSeconds(properties.waitTimeSeconds())
                .visibilityTimeout(properties.visibilityTimeoutSeconds())
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .build());
        for (Message message : response.messages()) {
            if (!inFlight.tryAcquire()) {
                releaseToQueue(message);
                continue;
            }
            metrics.updatePermitsAvailable(inFlight.availablePermits());
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

    private void waitForCapacity() {
        try {
            if (inFlight.tryAcquire(1, TimeUnit.SECONDS)) {
                inFlight.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseToQueue(Message message) {
        try {
            sqsClient.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(properties.queueUrl())
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(0)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to release unassigned message messageId={}", message.messageId(), ex);
        }
    }

    void processMessage(Message message) {
        Instant started = Instant.now();
        String correlationId = UUID.randomUUID().toString();
        int receiveCount = retryPolicy.parseReceiveCount(
                message.attributesAsStrings().getOrDefault("ApproximateReceiveCount", "1"));
        String attemptKey = message.messageId() + ":" + receiveCount;
        MDC.put("correlationId", correlationId);
        try {
            final BalanceEvent event;
            try {
                event = mapper.parse(message.body(), Instant.now());
            } catch (InvalidFinancialEventException ex) {
                rejectInvalidCommand.reject(attemptKey, correlationId, "INVALID_PAYLOAD");
                metrics.recordOutcome(ProcessingOutcome.INVALID);
                acknowledge(message);
                log.warn("Invalid event isolated attemptKey={}", attemptKey);
                return;
            }

            // Always attempt processing, even on the last receive, so a recovered dependency
            // (or fixed poison cause) can still succeed. Exhaustion only applies when ingest fails again.
            IngestResult result = ingestCommand.ingest(event, attemptKey, correlationId);
            metrics.recordOutcome(result.outcome());
            acknowledge(message);
        } catch (Exception ex) {
            if (retryPolicy.isRetryExhausted(receiveCount)) {
                rejectInvalidCommand.reject(attemptKey, correlationId, "RETRY_EXHAUSTED");
                metrics.recordRetryExhausted();
                acknowledge(message);
                log.warn("Retry exhausted after ingest failure attemptKey={}", attemptKey, ex);
                return;
            }
            metrics.recordRetry();
            log.warn("Transient ingest failure attemptKey={}", attemptKey);
            // TODO: no ACK -> visibility timeout / redelivery; app journals PERMANENTLY_FAILED after maxReceiveCount
        } finally {
            metrics.recordProcessingLatency(Duration.between(started, Instant.now()));
            MDC.remove("correlationId");
        }
    }

    private void acknowledge(Message message) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle())
                .build());
    }
}
