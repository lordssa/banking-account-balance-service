package com.itau.account.adapter.in.messaging;

import com.itau.account.adapter.out.observability.AccountMetricNames;
import com.itau.account.adapter.out.observability.IngestionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsDeleteBatcherTest {

    @Mock SqsClient sqsClient;

    @Test
    void flushesAtTenAndRecordsPartialFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IngestionMetrics metrics = new IngestionMetrics(registry);
        SqsDeleteBatcher batcher = new SqsDeleteBatcher(sqsClient, "http://q", metrics);

        when(sqsClient.deleteMessageBatch(any(DeleteMessageBatchRequest.class)))
                .thenReturn(DeleteMessageBatchResponse.builder()
                        .failed(BatchResultErrorEntry.builder()
                                .id("0")
                                .code("ReceiptHandleIsInvalid")
                                .senderFault(true)
                                .build())
                        .build());

        for (int i = 0; i < 10; i++) {
            batcher.enqueue(Message.builder().messageId("m-" + i).receiptHandle("rh-" + i).build());
        }

        ArgumentCaptor<DeleteMessageBatchRequest> captor = ArgumentCaptor.forClass(DeleteMessageBatchRequest.class);
        verify(sqsClient).deleteMessageBatch(captor.capture());
        assertThat(captor.getValue().entries()).hasSize(10);
        assertThat(registry.counter(AccountMetricNames.INGESTION_ACK_FAILURES).count()).isEqualTo(1.0);
    }
}
