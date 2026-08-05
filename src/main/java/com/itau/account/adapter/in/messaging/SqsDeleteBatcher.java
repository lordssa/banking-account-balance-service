package com.itau.account.adapter.in.messaging;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Bounded acknowledgement stage: queues receipt handles and flushes with DeleteMessageBatch (≤10).
 * Individual batch entry failures are surfaced even when the HTTP call returns 200.
 */
final class SqsDeleteBatcher {

    private static final Logger log = LoggerFactory.getLogger(SqsDeleteBatcher.class);
    static final int MAX_BATCH = 10;

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final IngestionMetrics metrics;
    private final ConcurrentLinkedQueue<Message> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final Object flushLock = new Object();

    SqsDeleteBatcher(SqsClient sqsClient, String queueUrl, IngestionMetrics metrics) {
        this.sqsClient = sqsClient;
        this.queueUrl = queueUrl;
        this.metrics = metrics;
    }

    void enqueue(Message message) {
        pending.offer(message);
        if (pendingCount.incrementAndGet() >= MAX_BATCH) {
            flush();
        }
    }

    void flush() {
        synchronized (flushLock) {
            while (true) {
                List<Message> batch = drainUpTo(MAX_BATCH);
                if (batch.isEmpty()) {
                    return;
                }
                deleteBatch(batch);
            }
        }
    }

    private List<Message> drainUpTo(int limit) {
        List<Message> batch = new ArrayList<>(limit);
        while (batch.size() < limit) {
            Message next = pending.poll();
            if (next == null) {
                break;
            }
            pendingCount.decrementAndGet();
            batch.add(next);
        }
        return batch;
    }

    private void deleteBatch(List<Message> batch) {
        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Message message = batch.get(i);
            entries.add(DeleteMessageBatchRequestEntry.builder()
                    .id(Integer.toString(i))
                    .receiptHandle(message.receiptHandle())
                    .build());
        }
        try {
            DeleteMessageBatchResponse response = sqsClient.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build());
            if (response.hasFailed() && !response.failed().isEmpty()) {
                IntConsumer recordFailure = ignored -> metrics.recordAckFailure();
                response.failed().forEach(failure -> {
                    recordFailure.accept(0);
                    log.warn(
                            "DeleteMessageBatch entry failed id={} code={} senderFault={}",
                            failure.id(),
                            failure.code(),
                            failure.senderFault());
                });
            }
        } catch (Exception ex) {
            batch.forEach(ignored -> metrics.recordAckFailure());
            log.warn("DeleteMessageBatch failed size={} — redelivery must remain idempotent", batch.size(), ex);
        }
    }
}
