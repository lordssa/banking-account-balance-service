package com.itau.account.adapter.in.messaging;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageEnvelopeCorrelationTest {

    @Test
    void prefersEventCorrelationIdAttribute() {
        Message message = Message.builder()
                .body("payload")
                .messageAttributes(Map.of(
                        MessageEnvelopeCorrelation.ATTR_EVENT_CORRELATION_ID,
                        MessageAttributeValue.builder().dataType("String").stringValue("evt-1").build(),
                        MessageEnvelopeCorrelation.ATTR_CORRELATION_ID,
                        MessageAttributeValue.builder().dataType("String").stringValue("ignored").build()))
                .attributes(Map.of(MessageSystemAttributeName.SENT_TIMESTAMP, "99"))
                .build();

        var resolved = MessageEnvelopeCorrelation.resolve(message, "secret");

        assertThat(resolved.correlationId()).isEqualTo("evt-1");
        assertThat(resolved.correlationSource()).isEqualTo(MessageEnvelopeCorrelation.SOURCE_PRODUCER_ATTR);
        assertThat(resolved.sentTimestampOrNull()).isEqualTo("99");
        assertThat(resolved.envelopeFingerprint()).isEqualTo(
                MessageEnvelopeCorrelation.hmacSha256Hex("secret", "payload"));
    }

    @Test
    void fallsBackToCorrelationIdAttribute() {
        Message message = Message.builder()
                .body("x")
                .messageAttributes(Map.of(
                        MessageEnvelopeCorrelation.ATTR_CORRELATION_ID,
                        MessageAttributeValue.builder().dataType("String").stringValue("corr-fallback").build()))
                .build();

        var resolved = MessageEnvelopeCorrelation.resolve(message, "secret");

        assertThat(resolved.correlationId()).isEqualTo("corr-fallback");
        assertThat(resolved.correlationSource()).isEqualTo(MessageEnvelopeCorrelation.SOURCE_PRODUCER_ATTR);
    }

    @Test
    void usesPayloadHmacWhenAttributesMissing() {
        Message message = Message.builder().body("same-body").build();
        Message otherId = Message.builder().messageId("other").body("same-body").build();

        var a = MessageEnvelopeCorrelation.resolve(message, "k");
        var b = MessageEnvelopeCorrelation.resolve(otherId, "k");

        assertThat(a.correlationId()).isEqualTo(b.correlationId());
        assertThat(a.correlationSource()).isEqualTo(MessageEnvelopeCorrelation.SOURCE_PAYLOAD_HMAC);
        assertThat(a.envelopeFingerprint()).isEqualTo(a.correlationId());
    }

    @Test
    void ignoresBlankProducerAttributes() {
        Message message = Message.builder()
                .body("body")
                .messageAttributes(Map.of(
                        MessageEnvelopeCorrelation.ATTR_EVENT_CORRELATION_ID,
                        MessageAttributeValue.builder().dataType("String").stringValue("  ").build()))
                .build();

        var resolved = MessageEnvelopeCorrelation.resolve(message, "k");

        assertThat(resolved.correlationSource()).isEqualTo(MessageEnvelopeCorrelation.SOURCE_PAYLOAD_HMAC);
    }

    @Test
    void nullBodyHashesAsEmpty() {
        Message message = Message.builder().build();
        assertThat(MessageEnvelopeCorrelation.resolve(message, null).envelopeFingerprint())
                .isEqualTo(MessageEnvelopeCorrelation.hmacSha256Hex(null, ""));
    }
}
