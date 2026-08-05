package com.itau.account.bootstrap;

import com.itau.account.adapter.out.observability.IngestionMetrics;
import com.itau.account.support.SqsTestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqsTopologyValidatorTest {

    @Mock SqsClient sqsClient;

    IngestionMetrics metrics;
    SqsProperties properties;
    SqsTopologyValidator validator;

    @BeforeEach
    void setUp() {
        metrics = new IngestionMetrics(new SimpleMeterRegistry());
        properties = SqsTestProperties.of(
                true,
                "http://localhost/queue",
                "",
                "sa-east-1",
                16,
                10,
                60,
                5,
                "arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq",
                "enforce");
        validator = new SqsTopologyValidator(sqsClient, properties, metrics);
    }

    @Test
    void acceptsMatchingRedrivePolicy() {
        String redrive = "{\"deadLetterTargetArn\":\"arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq\",\"maxReceiveCount\":\"5\"}";
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrive))
                        .build());

        assertThat(validator.validate().valid()).isTrue();
        assertThat(validator.lastReason()).isEqualTo("ok");
        assertThat(validator.cachedValidation().valid()).isTrue();
    }

    @Test
    void rejectsDlqArnMismatch() {
        String redrive = "{\"deadLetterTargetArn\":\"arn:aws:sqs:sa-east-1:000000000000:wrong-dlq\",\"maxReceiveCount\":\"5\"}";
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrive))
                        .build());

        assertThat(validator.validate().valid()).isFalse();
        assertThat(validator.lastReason()).isEqualTo("dlq-arn-mismatch");
    }

    @Test
    void rejectsMissingRedrivePolicy() {
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder().attributes(Map.of()).build());

        assertThat(validator.validate().valid()).isFalse();
        assertThat(validator.lastReason()).isEqualTo("missing-redrive-policy");
    }

    @Test
    void allowsSafeConsumptionReadsCacheOnly() {
        SqsProperties observe = SqsTestProperties.of(
                true, "http://localhost/queue", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq", "observe");
        SqsTopologyValidator observeValidator = new SqsTopologyValidator(sqsClient, observe, metrics);
        assertThat(observeValidator.allowsSafeConsumption()).isTrue();

        // Enforce without refresh: cache still not-checked / invalid → block
        assertThat(validator.allowsSafeConsumption()).isFalse();
        verify(sqsClient, times(0)).getQueueAttributes(any(GetQueueAttributesRequest.class));

        String redrive = "{\"deadLetterTargetArn\":\"arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq\",\"maxReceiveCount\":\"5\"}";
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrive))
                        .build());
        validator.validate();
        assertThat(validator.allowsSafeConsumption()).isTrue();
        // Second gate read must not call SQS again
        assertThat(validator.allowsSafeConsumption()).isTrue();
        verify(sqsClient, times(1)).getQueueAttributes(any(GetQueueAttributesRequest.class));

        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder().attributes(Map.of()).build());
        validator.validate();
        assertThat(validator.allowsSafeConsumption()).isFalse();
    }

    @Test
    void rejectsMissingSourceQueueUrl() {
        SqsProperties blankUrl = SqsTestProperties.of(
                true, " ", "", "sa-east-1", 16, 10, 60, 5,
                "arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq", "enforce");
        SqsTopologyValidator blankUrlValidator = new SqsTopologyValidator(sqsClient, blankUrl, metrics);

        assertThat(blankUrlValidator.validate().valid()).isFalse();
        assertThat(blankUrlValidator.lastReason()).isEqualTo("missing-source-queue-url");
    }

    @Test
    void rejectsMissingExpectedDlqArn() {
        SqsProperties noDlq = SqsTestProperties.of(
                true, "http://localhost/queue", "", "sa-east-1", 16, 10, 60, 5, "", "enforce");
        SqsTopologyValidator noDlqValidator = new SqsTopologyValidator(sqsClient, noDlq, metrics);

        assertThat(noDlqValidator.validate().valid()).isFalse();
        assertThat(noDlqValidator.lastReason()).isEqualTo("missing-expected-dlq-arn");
    }

    @Test
    void rejectsMissingDeadLetterTargetAndMaxReceive() {
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, "{\"foo\":1}"))
                        .build());
        assertThat(validator.validate().reason()).isEqualTo("missing-dead-letter-target");

        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(
                                QueueAttributeName.REDRIVE_POLICY,
                                "{\"deadLetterTargetArn\":\"arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq\"}"))
                        .build());
        assertThat(validator.validate().reason()).isEqualTo("missing-max-receive-count");
    }

    @Test
    void rejectsMaxReceiveCountMismatch() {
        String redrive = "{\"deadLetterTargetArn\":\"arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq\",\"maxReceiveCount\":3}";
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(QueueAttributeName.REDRIVE_POLICY, redrive))
                        .build());

        assertThat(validator.validate().reason()).isEqualTo("max-receive-count-mismatch");
    }

    @Test
    void rejectsWhenQueueAttributesUnavailable() {
        when(sqsClient.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenThrow(new RuntimeException("denied"));

        assertThat(validator.validate().reason()).isEqualTo("queue-attributes-unavailable");
        assertThat(validator.lastValid()).isFalse();
    }
}
