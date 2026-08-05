package com.itau.account.adapter.in.messaging;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Resolves a stable correlation identity for an SQS envelope that survives native redrive
 * (which assigns a new broker message ID). Prefers a producer-supplied opaque message attribute;
 * otherwise uses a privacy-safe HMAC of the payload body.
 */
public final class MessageEnvelopeCorrelation {

    public static final String ATTR_EVENT_CORRELATION_ID = "eventCorrelationId";
    public static final String ATTR_CORRELATION_ID = "correlationId";
    public static final String SOURCE_PRODUCER_ATTR = "PRODUCER_ATTR";
    public static final String SOURCE_PAYLOAD_HMAC = "PAYLOAD_HMAC";

    private MessageEnvelopeCorrelation() {
    }

    public record Resolved(
            String correlationId,
            String envelopeFingerprint,
            String sentTimestampOrNull,
            String correlationSource
    ) {
    }

    public static Resolved resolve(Message message, String hmacSecret) {
        String body = message.body() == null ? "" : message.body();
        String fingerprint = hmacSha256Hex(hmacSecret, body);
        String sentTimestamp = blankToNull(message.attributesAsStrings().get("SentTimestamp"));
        String producer = firstProducerAttr(message);
        if (producer != null) {
            return new Resolved(producer, fingerprint, sentTimestamp, SOURCE_PRODUCER_ATTR);
        }
        return new Resolved(fingerprint, fingerprint, sentTimestamp, SOURCE_PAYLOAD_HMAC);
    }

    private static String firstProducerAttr(Message message) {
        Map<String, MessageAttributeValue> attrs = message.messageAttributes();
        if (attrs == null || attrs.isEmpty()) {
            return null;
        }
        String fromCanonical = stringAttr(attrs.get(ATTR_EVENT_CORRELATION_ID));
        if (fromCanonical != null) {
            return fromCanonical;
        }
        return stringAttr(attrs.get(ATTR_CORRELATION_ID));
    }

    private static String stringAttr(MessageAttributeValue value) {
        if (value == null) {
            return null;
        }
        return blankToNull(value.stringValue());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }

    static String hmacSha256Hex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] keyBytes = (secret == null || secret.isEmpty())
                    ? new byte[]{0}
                    : secret.getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] digest = mac.doFinal((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }
}
