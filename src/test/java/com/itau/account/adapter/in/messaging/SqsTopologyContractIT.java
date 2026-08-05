package com.itau.account.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/**
 * Broker RedrivePolicy contract: unacked messages move to DLQ after maxReceiveCount.
 * Application DeleteMessage-on-exhaustion is covered by unit tests; this proves broker isolation.
 */
@Testcontainers(disabledWithoutDocker = true)
class SqsTopologyContractIT {

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7.2"))
            .withServices(SQS);

    @Test
    void sourceRedrivePolicyTargetsDlqWithMaxReceiveCountFive() {
        try (SqsClient client = client()) {
            String dlqUrl = client.createQueue(CreateQueueRequest.builder()
                    .queueName("transacoes-financeiras-processadas-dlq")
                    .attributes(Map.of(QueueAttributeName.MESSAGE_RETENTION_PERIOD, "1209600"))
                    .build()).queueUrl();
            String dlqArn = client.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(dlqUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()).attributes().get(QueueAttributeName.QUEUE_ARN);

            String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"5\"}";
            String sourceUrl = client.createQueue(CreateQueueRequest.builder()
                    .queueName("transacoes-financeiras-processadas")
                    .attributes(Map.of(
                            QueueAttributeName.REDRIVE_POLICY, redrive,
                            QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                            QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "1"
                    ))
                    .build()).queueUrl();

            String policy = client.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(sourceUrl)
                    .attributeNames(QueueAttributeName.REDRIVE_POLICY)
                    .build()).attributes().get(QueueAttributeName.REDRIVE_POLICY);

            assertThat(policy).contains(dlqArn);
            assertThat(policy).contains("\"maxReceiveCount\":\"5\"");
        }
    }

    @Test
    void unackedMessagesMoveToDlqAfterMaxReceives() {
        try (SqsClient client = client()) {
            String dlqUrl = client.createQueue(CreateQueueRequest.builder()
                    .queueName("it-dlq-" + System.nanoTime())
                    .build()).queueUrl();
            String dlqArn = client.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(dlqUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)
                    .build()).attributes().get(QueueAttributeName.QUEUE_ARN);

            String sourceName = "it-source-" + System.nanoTime();
            String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}";
            String sourceUrl = client.createQueue(CreateQueueRequest.builder()
                    .queueName(sourceName)
                    .attributes(Map.of(
                            QueueAttributeName.REDRIVE_POLICY, redrive,
                            QueueAttributeName.VISIBILITY_TIMEOUT, "1"
                    ))
                    .build()).queueUrl();

            client.sendMessage(SendMessageRequest.builder()
                    .queueUrl(sourceUrl)
                    .messageBody("{\"poison\":true}")
                    .build());

            for (int i = 0; i < 2; i++) {
                var msgs = client.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(sourceUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(2)
                        .visibilityTimeout(1)
                        .build()).messages();
                assertThat(msgs).isNotEmpty();
                // intentionally no DeleteMessage
                await().pollDelay(Duration.ofMillis(1100)).until(() -> true);
            }

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var dlqMsgs = client.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(dlqUrl)
                        .maxNumberOfMessages(1)
                        .waitTimeSeconds(2)
                        .build()).messages();
                assertThat(dlqMsgs).isNotEmpty();
                assertThat(dlqMsgs.getFirst().body()).contains("poison");
            });
        }
    }

    private static SqsClient client() {
        return SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(SQS))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();
    }
}
