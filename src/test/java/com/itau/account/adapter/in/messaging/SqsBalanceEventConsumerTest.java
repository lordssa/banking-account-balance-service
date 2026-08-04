package com.itau.account.adapter.in.messaging;

import com.itau.account.adapter.out.observability.AccountMetricNames;
import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.application.port.in.IngestBalanceEventCommand;
import com.itau.account.application.port.in.RejectInvalidEventCommand;
import com.itau.account.bootstrap.SqsProperties;
import com.itau.account.domain.IngestResult;
import com.itau.account.domain.ProcessingOutcome;
import com.itau.account.domain.SnapshotEffect;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsBalanceEventConsumerTest {

    @Mock SqsClient sqsClient;
    @Mock IngestBalanceEventCommand ingestCommand;
    @Mock RejectInvalidEventCommand rejectInvalidCommand;

    SqsProperties properties;
    FinancialEventMapper mapper;
    SimpleMeterRegistry registry;
    IngestionMetrics metrics;
    SqsBalanceEventConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new SqsProperties(true, "http://localhost/queue", "", "sa-east-1", 2, 10, 60, 5);
        mapper = new FinancialEventMapper(JsonMapper.builder().build());
        registry = new SimpleMeterRegistry();
        metrics = new IngestionMetrics(registry);
        consumer = new SqsBalanceEventConsumer(
                sqsClient, properties, mapper, ingestCommand, rejectInvalidCommand, metrics);
    }

    @Test
    void validMessageIsIngestedAndAcknowledged() {
        UUID tx = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String body = validBody(tx, account, owner);
        Message message = Message.builder()
                .messageId("m-1")
                .receiptHandle("rh-1")
                .body(body)
                .build();

        when(ingestCommand.ingest(any(), eq("m-1:1"), any()))
                .thenReturn(IngestResult.of(ProcessingOutcome.ACCEPTED, SnapshotEffect.UPDATED, "ACCEPTED_NEWER"));

        consumer.processMessage(message);

        verify(ingestCommand).ingest(any(), eq("m-1:1"), any());
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        verify(rejectInvalidCommand, never()).reject(any(), any(), any());
        assertThat(registry.counter(AccountMetricNames.INGESTION_EVENTS, "outcome", "ACCEPTED").count()).isEqualTo(1.0);
    }

    @Test
    void invalidPayloadIsIsolatedAndAcknowledged() {
        Message message = Message.builder()
                .messageId("m-bad")
                .receiptHandle("rh-bad")
                .body("{}")
                .build();

        consumer.processMessage(message);

        verify(rejectInvalidCommand).reject(eq("m-bad:1"), any(), eq("INVALID_PAYLOAD"));
        verify(ingestCommand, never()).ingest(any(), any(), any());
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
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
        verify(rejectInvalidCommand, never()).reject(any(), any(), any());
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRIES).count()).isEqualTo(1.0);
    }

    @Test
    void transientFailureDoesNotAcknowledge() {
        UUID tx = UUID.randomUUID();
        String body = validBody(tx, UUID.randomUUID(), UUID.randomUUID());
        Message message = Message.builder()
                .messageId("m-retry")
                .receiptHandle("rh-retry")
                .body(body)
                .build();

        when(ingestCommand.ingest(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        consumer.processMessage(message);

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
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

        verify(ingestCommand).ingest(any(), eq("m-recover:5"), any());
        verify(rejectInvalidCommand, never()).reject(any(), any(), any());
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRY_EXHAUSTED).count()).isZero();
    }

    @Test
    void retryExhaustionJournalsPermanentFailureAndAcksOnlyAfterIngestFails() {
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
        verify(rejectInvalidCommand).reject(eq("m-ex:5"), any(), eq("RETRY_EXHAUSTED"));
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(registry.counter(AccountMetricNames.INGESTION_RETRY_EXHAUSTED).count()).isEqualTo(1.0);
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
        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                verify(sqsClient, atLeastOnce()).deleteMessage(any(DeleteMessageRequest.class)));
    }

    @Test
    void surplusReceivedMessagesAreReleasedImmediately() {
        properties = new SqsProperties(true, "http://localhost/queue", "", "sa-east-1", 1, 10, 60, 5);
        consumer = new SqsBalanceEventConsumer(
                sqsClient, properties, mapper, ingestCommand, rejectInvalidCommand, metrics);

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

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() ->
                verify(sqsClient).deleteMessage(argThat((DeleteMessageRequest req) ->
                        "rh-assigned".equals(req.receiptHandle()))));
        verify(sqsClient).changeMessageVisibility(argThat((ChangeMessageVisibilityRequest req) ->
                "rh-surplus".equals(req.receiptHandle()) && Integer.valueOf(0).equals(req.visibilityTimeout())));
        verify(sqsClient, never()).deleteMessage(argThat((DeleteMessageRequest req) ->
                "rh-surplus".equals(req.receiptHandle())));
        verify(ingestCommand, times(1)).ingest(any(), any(), any());
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
