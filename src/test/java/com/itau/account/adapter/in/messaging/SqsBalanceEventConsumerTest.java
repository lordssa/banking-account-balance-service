package com.itau.account.adapter.in.messaging;

import com.itau.account.adapter.out.observability.AccountMetricNames;
import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.bootstrap.SqsProperties;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import com.itau.account.support.SqsTestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsBalanceEventConsumerTest {

    @Mock SqsClient sqsClient;
    @Mock IngestBalanceEventCommand ingestCommand;
    @Mock RejectInvalidEventCommand rejectInvalidCommand;
    @Mock com.itau.account.bootstrap.SqsTopologyValidator topologyValidator;

    SqsProperties properties;
    FinancialEventMapper mapper;
    SimpleMeterRegistry registry;
    IngestionMetrics metrics;
    SqsBalanceEventConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = props(2, "observe");
        mapper = new FinancialEventMapper(JsonMapper.builder().build());
        registry = new SimpleMeterRegistry();
        metrics = new IngestionMetrics(registry);
        lenient().when(topologyValidator.allowsSafeConsumption()).thenReturn(true);
        lenient().when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder().build());
        consumer = newConsumer(properties);
    }

    private SqsBalanceEventConsumer newConsumer(SqsProperties props) {
        return new SqsBalanceEventConsumer(
                sqsClient, props, mapper, ingestCommand, rejectInvalidCommand, metrics, topologyValidator);
    }

    private static SqsProperties props(int maxConcurrent, String topologyMode) {
        return SqsTestProperties.of(
                true, "http://localhost/queue", "", "sa-east-1", maxConcurrent, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq", topologyMode);
    }

    @Test
    void validMessageIsIngestedAndAcknowledged() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-1")
                .receiptHandle("rh-1")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .build();

        when(ingestCommand.ingest(any(), eq("m-1:1"), any()))
                .thenReturn(IngestResult.of(ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER"));

        consumer.processMessage(message);
        consumer.flushAcknowledgements();

        verify(ingestCommand).ingest(any(), eq("m-1:1"), any());
        verify(sqsClient).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        verify(rejectInvalidCommand, never()).reject(any(), any(), any(), any(), any());
        assertThat(registry.counter(AccountMetricNames.INGESTION_EVENTS, "outcome", "ACCEPTED").count()).isEqualTo(1.0);
    }

    @Test
    void invalidPayloadIsJournaledButNotAcknowledged() {
        Message message = Message.builder()
                .messageId("m-bad")
                .receiptHandle("rh-bad")
                .body("{}")
                .build();

        consumer.processMessage(message);

        verify(rejectInvalidCommand).reject(eq("m-bad:1"), any(), eq("INVALID_PAYLOAD"), any(), any());
        verify(ingestCommand, never()).ingest(any(), any(), any());
        consumer.flushAcknowledgements();
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
    }

    @Test
    void invalidJournalFailureDoesNotAcknowledge() {
        Message message = Message.builder()
                .messageId("m-bad2")
                .receiptHandle("rh-bad2")
                .body("{}")
                .build();
        when(rejectInvalidCommand.reject(any(), any(), eq("INVALID_PAYLOAD"), any(), any()))
                .thenThrow(new RuntimeException("journal down"));

        consumer.processMessage(message);

        consumer.flushAcknowledgements();
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_UNEXPECTED_FAILURES).count()).isEqualTo(1.0);
    }

    @Test
    void illegalArgumentFromIngestRemainsRetryableAndDoesNotAck() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-iae")
                .receiptHandle("rh-iae")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .build();
        when(ingestCommand.ingest(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("internal mapping bug"));

        consumer.processMessage(message);

        verify(ingestCommand).ingest(any(), any(), any());
        verify(rejectInvalidCommand, never()).reject(any(), any(), any(), any(), any());
        consumer.flushAcknowledgements();
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRIES).count()).isEqualTo(1.0);
    }

    @Test
    void transientFailureDoesNotAcknowledge() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-retry")
                .receiptHandle("rh-retry")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .build();

        when(ingestCommand.ingest(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        consumer.processMessage(message);

        consumer.flushAcknowledgements();
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRIES).count()).isEqualTo(1.0);
    }

    @Test
    void lastReceiveStillIngestsWhenRecovered() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-recover")
                .receiptHandle("rh-recover")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "5"))
                .build();
        when(ingestCommand.ingest(any(), eq("m-recover:5"), any()))
                .thenReturn(IngestResult.of(ProcessingOutcome.DUPLICATE, SnapshotEffect.UNCHANGED, "IDEMPOTENT"));

        consumer.processMessage(message);
        consumer.flushAcknowledgements();

        verify(ingestCommand).ingest(any(), eq("m-recover:5"), any());
        verify(rejectInvalidCommand, never()).reject(any(), any(), any(), any(), any());
        verify(sqsClient).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRY_EXHAUSTED).count()).isZero();
    }

    @Test
    void retryExhaustionJournalsBestEffortButDoesNotAck() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-ex")
                .receiptHandle("rh-ex")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "5"))
                .build();
        when(ingestCommand.ingest(any(), eq("m-ex:5"), any())).thenThrow(new RuntimeException("db down"));

        consumer.processMessage(message);

        verify(ingestCommand).ingest(any(), eq("m-ex:5"), any());
        verify(rejectInvalidCommand).reject(eq("m-ex:5"), any(), eq("RETRY_EXHAUSTED"), any(), any());
        consumer.flushAcknowledgements();
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRY_EXHAUSTED).count()).isEqualTo(1.0);
    }

    @Test
    void ackFailureAfterDurableOutcomeIsRecordedAndDoesNotThrow() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-ack")
                .receiptHandle("rh-ack")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .build();
        when(ingestCommand.ingest(any(), any(), any()))
                .thenReturn(IngestResult.of(ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER"));
        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenThrow(new RuntimeException("sqs delete failed"));

        consumer.processMessage(message);
        consumer.flushAcknowledgements();

        assertThat(registry.counter(AccountMetricNames.INGESTION_ACK_FAILURES).count()).isEqualTo(1.0);
    }

    @Test
    void pollOnceDispatchesReceivedMessages() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-poll")
                .receiptHandle("rh-poll")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .build();
        AtomicReference<ReceiveMessageRequest> received = new AtomicReference<>();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            received.set(invocation.getArgument(0));
            return ReceiveMessageResponse.builder().messages(List.of(message)).build();
        });
        when(ingestCommand.ingest(any(), eq("m-poll:1"), any()))
                .thenReturn(IngestResult.of(ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER"));

        consumer.pollOnce();

        assertThat(received.get().maxNumberOfMessages()).isEqualTo(2);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            consumer.flushAcknowledgements();
            verify(sqsClient, atLeastOnce()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        });
    }

    @Test
    void pollOnceRequestsOnlyReservedPermitsAndReleasesUnused() {
        properties = props(5, "observe");
        consumer = newConsumer(properties);
        AtomicReference<ReceiveMessageRequest> received = new AtomicReference<>();
        Message message = Message.builder()
                .messageId("m-one")
                .receiptHandle("rh-one")
                .body(validBody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .build();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class))).thenAnswer(invocation -> {
            received.set(invocation.getArgument(0));
            return ReceiveMessageResponse.builder().messages(List.of(message)).build();
        });
        when(ingestCommand.ingest(any(), eq("m-one:1"), any()))
                .thenReturn(IngestResult.of(ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER"));

        consumer.pollOnce();

        assertThat(received.get().maxNumberOfMessages()).isEqualTo(5);
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            consumer.flushAcknowledgements();
            verify(sqsClient, atLeastOnce()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        });
        verify(sqsClient, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        assertThat(consumer.tryAcquireInFlightPermit()).isFalse();
    }

    @Test
    void unexpectedExtraMessagesAreLeftUnackedWithoutVisibilityReset() {
        properties = props(1, "observe");
        consumer = newConsumer(properties);

        Message assigned = Message.builder()
                .messageId("m-assigned")
                .receiptHandle("rh-assigned")
                .body(validBody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .build();
        Message surplus = Message.builder()
                .messageId("m-surplus")
                .receiptHandle("rh-surplus")
                .body(validBody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .build();
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of(assigned, surplus)).build());
        when(ingestCommand.ingest(any(), eq("m-assigned:1"), any()))
                .thenReturn(IngestResult.of(ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER"));

        consumer.pollOnce();

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            consumer.flushAcknowledgements();
            verify(sqsClient).deleteMessageBatch(argThat((DeleteMessageBatchRequest req) ->
                    req.entries().stream().anyMatch(e -> "rh-assigned".equals(e.receiptHandle()))));
        });
        verify(sqsClient, never()).changeMessageVisibility(any(ChangeMessageVisibilityRequest.class));
        verify(sqsClient, never()).deleteMessageBatch(argThat((DeleteMessageBatchRequest req) ->
                req.entries().stream().anyMatch(e -> "rh-surplus".equals(e.receiptHandle()))));
        verify(ingestCommand, times(1)).ingest(any(), any(), any());
    }

    @Test
    void enforceModeDoesNotReceiveWhenTopologyInvalid() {
        properties = props(2, "enforce");
        when(topologyValidator.allowsSafeConsumption()).thenReturn(false);
        consumer = newConsumer(properties);

        consumer.pollOnce();

        verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void enforceModeReceivesWhenTopologyValid() {
        properties = props(2, "enforce");
        when(topologyValidator.allowsSafeConsumption()).thenReturn(true);
        consumer = newConsumer(properties);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        consumer.pollOnce();

        verify(sqsClient).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void producerCorrelationAttributeIsStableAcrossMessageIds() {
        String body = "{}";
        Message beforeRedrive = Message.builder()
                .messageId("m-before")
                .receiptHandle("rh-before")
                .body(body)
                .messageAttributes(Map.of(
                        MessageEnvelopeCorrelation.ATTR_EVENT_CORRELATION_ID,
                        MessageAttributeValue.builder().dataType("String").stringValue("prod-corr-1").build()))
                .attributes(Map.of(MessageSystemAttributeName.SENT_TIMESTAMP, "1700000000000"))
                .build();
        Message afterRedrive = Message.builder()
                .messageId("m-after")
                .receiptHandle("rh-after")
                .body(body)
                .messageAttributes(Map.of(
                        MessageEnvelopeCorrelation.ATTR_EVENT_CORRELATION_ID,
                        MessageAttributeValue.builder().dataType("String").stringValue("prod-corr-1").build()))
                .build();

        consumer.processMessage(beforeRedrive);
        consumer.processMessage(afterRedrive);

        verify(rejectInvalidCommand).reject(
                eq("m-before:1"),
                eq("prod-corr-1"),
                eq("INVALID_PAYLOAD"),
                argThat(ctx ->
                        MessageEnvelopeCorrelation.SOURCE_PRODUCER_ATTR.equals(ctx.get("correlationSource"))
                                && ctx.get("envelopeFingerprint") != null
                                && "1700000000000".equals(ctx.get("sentTimestamp"))),
                any());
        verify(rejectInvalidCommand).reject(
                eq("m-after:1"),
                eq("prod-corr-1"),
                eq("INVALID_PAYLOAD"),
                argThat(ctx ->
                        MessageEnvelopeCorrelation.SOURCE_PRODUCER_ATTR.equals(ctx.get("correlationSource"))
                                && !ctx.containsKey("sentTimestamp")),
                any());
    }

    @Test
    void payloadHmacCorrelationIsStableWhenMessageIdChanges() {
        String body = "{\"broken\":true}";
        Message first = Message.builder().messageId("id-1").receiptHandle("rh-1").body(body).build();
        Message second = Message.builder().messageId("id-2").receiptHandle("rh-2").body(body).build();
        String expected = MessageEnvelopeCorrelation.hmacSha256Hex("test-hmac-secret", body);

        consumer.processMessage(first);
        consumer.processMessage(second);

        verify(rejectInvalidCommand, times(2)).reject(any(), eq(expected), eq("INVALID_PAYLOAD"), argThat(ctx ->
                expected.equals(ctx.get("envelopeFingerprint"))
                        && MessageEnvelopeCorrelation.SOURCE_PAYLOAD_HMAC.equals(ctx.get("correlationSource"))), any());
    }

    @Test
    void permanentlyFailedJournalFailureStillLeavesUnacked() {
        UUID tx = UUID.randomUUID();
        Message message = Message.builder()
                .messageId("m-pf")
                .receiptHandle("rh-pf")
                .body(validBody(tx, UUID.randomUUID(), UUID.randomUUID()))
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "5"))
                .build();
        when(ingestCommand.ingest(any(), eq("m-pf:5"), any())).thenThrow(new RuntimeException("db down"));
        when(rejectInvalidCommand.reject(any(), any(), eq("RETRY_EXHAUSTED"), any(), any()))
                .thenThrow(new RuntimeException("journal down"));

        consumer.processMessage(message);

        consumer.flushAcknowledgements();
        verify(sqsClient, never()).deleteMessageBatch(any(DeleteMessageBatchRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRY_EXHAUSTED).count()).isEqualTo(1.0);
    }

    @Test
    void receiveFailureReleasesReservedPermits() {
        properties = props(2, "observe");
        consumer = newConsumer(properties);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenThrow(new RuntimeException("sqs down"));

        assertThatThrownBy(consumer::pollOnce)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sqs down");

        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        assertThat(consumer.tryAcquireInFlightPermit()).isFalse();
    }

    @Test
    void pollOnceWaitsWhenNoCapacity() {
        properties = props(1, "observe");
        consumer = newConsumer(properties);
        assertThat(consumer.tryAcquireInFlightPermit()).isTrue();
        try {
            consumer.pollOnce();
            verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));
        } finally {
            consumer.releaseInFlightPermit();
        }
    }

    @Test
    void runPollIterationPausesAndResumesOnTopology() throws Exception {
        properties = props(2, "enforce");
        consumer = newConsumer(properties);
        consumer.topologyPauseMs = 1L;
        when(topologyValidator.allowsSafeConsumption()).thenReturn(false);
        when(topologyValidator.lastReason()).thenReturn("missing-redrive-policy");

        consumer.runPollIteration();
        verify(sqsClient, never()).receiveMessage(any(ReceiveMessageRequest.class));

        when(topologyValidator.allowsSafeConsumption()).thenReturn(true);
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());
        consumer.runPollIteration();
        verify(sqsClient).receiveMessage(any(ReceiveMessageRequest.class));
    }

    @Test
    void startAndStopWhenTopologyDeferred() {
        properties = props(2, "enforce");
        when(topologyValidator.allowsSafeConsumption()).thenReturn(false);
        when(topologyValidator.lastReason()).thenReturn("dlq-arn-mismatch");
        consumer = newConsumer(properties);
        consumer.topologyPauseMs = 50L;

        consumer.start();
        consumer.stop();
    }

    @Test
    void startLogsWhenTopologyAllows() {
        consumer = newConsumer(props(2, "observe"));
        consumer.topologyPauseMs = 50L;
        lenient().when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(List.of()).build());

        consumer.start();
        consumer.stop();
    }

    private static String validBody(UUID tx, UUID account, UUID owner) {
        return """
                {
                  "transaction": {
                    "id": "%s",
                    "type": "CREDIT",
                    "amount": "10.50",
                    "currency": "BRL",
                    "status": "APPROVED",
                    "timestamp": 1700000000000001
                  },
                  "account": {
                    "id": "%s",
                    "owner": "%s",
                    "created_at": 1609459200,
                    "status": "ENABLED",
                    "balance": { "amount": 100.25, "currency": "BRL" }
                  }
                }
                """.formatted(tx, account, owner);
    }
}
