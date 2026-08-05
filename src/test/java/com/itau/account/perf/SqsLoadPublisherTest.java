package com.itau.account.perf;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqsLoadPublisherTest {

    @Test
    void delaySkippedForSeedIndexes() {
        assertThat(SqsLoadPublisher.delayFor(1, 1, 30)).isZero();
        assertThat(SqsLoadPublisher.delayFor(2, 1, 30)).isEqualTo(30);
        assertThat(SqsLoadPublisher.delayFor(2, 0, 30)).isEqualTo(30);
        assertThat(SqsLoadPublisher.delayFor(2, 1, 0)).isZero();
    }

    @Test
    void buildsFullBatchesThenRemainder() {
        List<String[]> accounts = List.of(
                new String[]{"acct-a", "own-a"},
                new String[]{"acct-b", "own-b"}
        );
        List<SendMessageBatchRequest> batches = SqsLoadPublisher.buildBatches(
                accounts, 1, 6, 1_700_000_000_000_000L, "eventCorrelationId",
                "abcd", "ef01", 30, 1, "http://localhost:4566/queue");

        // 2 accounts × 6 events = 12 messages → 1 full batch + remainder of 2
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).entries()).hasSize(10);
        assertThat(batches.get(1).entries()).hasSize(2);
        assertThat(batches.get(0).entries().get(0).delaySeconds()).isNull();
        assertThat(batches.get(0).entries().get(1).delaySeconds()).isEqualTo(30);
    }
}
