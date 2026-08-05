package com.itau.account.deploy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqsIamPolicyFixtureTest {

    @Test
    void consumerPolicyHasNoDlqRedriveActions() throws Exception {
        String json = Files.readString(Path.of("deploy/terraform/tests/consumer-sqs-policy.fixture.json"));
        assertThat(json).doesNotContain("StartMessageMoveTask");
        assertThat(json).doesNotContain("dlq");
        assertThat(json).contains("ReceiveMessage");
        assertThat(json).contains("DeleteMessage");
    }

    @Test
    void kedaScalerPolicyIsReadOnlyQueueDepth() throws Exception {
        String json = Files.readString(Path.of("deploy/terraform/tests/keda-sqs-scaler-policy.fixture.json"));
        assertThat(json).contains("GetQueueAttributes");
        assertThat(json).contains("GetQueueUrl");
        assertThat(json).doesNotContain("ReceiveMessage");
        assertThat(json).doesNotContain("DeleteMessage");
        assertThat(json).doesNotContain("StartMessageMoveTask");
        assertThat(json).doesNotContain("SendMessage");
    }

    @Test
    void recoveryPolicyAllowsMoveAndLacksQueueAdmin() throws Exception {
        String json = Files.readString(Path.of("deploy/terraform/tests/recovery-sqs-policy.fixture.json"));
        assertThat(json).contains("StartMessageMoveTask");
        assertThat(json).contains("CancelMessageMoveTask");
        assertThat(json).doesNotContain("SetQueueAttributes");
        assertThat(json).doesNotContain("DeleteQueue");
    }
}
