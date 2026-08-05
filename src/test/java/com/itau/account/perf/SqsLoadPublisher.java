package com.itau.account.perf;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * High-throughput SQS publisher for deploy/perf.
 * Avoids Maven/aws.exe per batch: one JVM, async SendMessageBatch with many in-flight calls.
 *
 * Env:
 *   WORK_DIR, SQS_QUEUE_URL, AWS_ENDPOINT_OVERRIDE, AWS_REGION,
 *   START_INDEX, END_INDEX, CORRELATION_ATTR, DELAY_SECONDS,
 *   IMMEDIATE_THROUGH_INDEX (idx &lt;= this get delay 0; default 0 = all delayed when DELAY&gt;0),
 *   PUBLISH_MAX_INFLIGHT (concurrent SendMessageBatch calls, default 64)
 */
public final class SqsLoadPublisher {

    private SqsLoadPublisher() {
    }

    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(required("WORK_DIR"));
        Properties meta = loadMeta(workDir.resolve("workload.meta"));
        long baseMicros = Long.parseLong(meta.getProperty("BASE_MICROS", "1700000000000000"));
        int eventsPerAccount = Integer.parseInt(meta.getProperty("EVENTS_PER_ACCOUNT", "40"));
        int start = Integer.parseInt(env("START_INDEX", "1"));
        int end = Integer.parseInt(env("END_INDEX", Integer.toString(eventsPerAccount)));
        int delaySeconds = Math.max(0, Integer.parseInt(env("DELAY_SECONDS", "0")));
        int immediateThrough = Math.max(0, Integer.parseInt(env("IMMEDIATE_THROUGH_INDEX", "0")));
        int maxInflight = Math.max(1, Integer.parseInt(env("PUBLISH_MAX_INFLIGHT", "64")));
        if (delaySeconds > 900) {
            throw new IllegalArgumentException("DELAY_SECONDS must be <= 900 (SQS limit), got " + delaySeconds);
        }
        String correlationAttr = env("CORRELATION_ATTR", "eventCorrelationId");
        String queueUrl = required("SQS_QUEUE_URL");
        String endpoint = env("AWS_ENDPOINT_OVERRIDE", "");
        String region = env("AWS_REGION", "sa-east-1");

        if (start < 1 || end < start) {
            throw new IllegalArgumentException("invalid START_INDEX/END_INDEX: " + start + ".." + end);
        }

        String runHash = meta.getProperty("RUN_HASH", "00000000").trim().toLowerCase(Locale.ROOT);
        if (!runHash.matches("[0-9a-f]{8}")) {
            throw new IllegalStateException("workload.meta RUN_HASH must be 8 hex chars, got: " + runHash);
        }
        String txHash0 = runHash.substring(0, 4);
        String txHash1 = runHash.substring(4, 8);

        List<String[]> accounts = loadAccounts(workDir.resolve("accounts.csv"));
        List<SendMessageBatchRequest> batches = buildBatches(
                accounts, start, end, baseMicros, correlationAttr, txHash0, txHash1,
                delaySeconds, immediateThrough, queueUrl);

        int messageCount = (end - start + 1) * accounts.size();
        System.out.printf(
                Locale.ROOT,
                "Publishing %d messages (%d batches, idx %d..%d, inflight=%d, delay=%ds immediate_through=%d) to %s (run_hash=%s)%n",
                messageCount,
                batches.size(),
                start,
                end,
                maxInflight,
                delaySeconds,
                immediateThrough,
                queueUrl,
                runHash);

        long started = System.nanoTime();
        try (SqsAsyncClient sqs = buildClient(endpoint, region, maxInflight)) {
            sendAll(sqs, batches, maxInflight);
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        if (elapsed < 0.001) {
            elapsed = 0.001;
        }
        double rate = messageCount / elapsed;
        System.out.printf(
                Locale.ROOT,
                "Publish complete: messages=%d elapsed_s=%.3f publish_rate_msg_s=%.1f%n",
                messageCount,
                elapsed,
                rate);
        Files.writeString(workDir.resolve("published_count.txt"), Integer.toString(messageCount));
        Files.writeString(workDir.resolve("publish_elapsed_s.txt"), String.format(Locale.ROOT, "%.3f", elapsed));
        Files.writeString(workDir.resolve("publish_rate.txt"), String.format(Locale.ROOT, "%.1f", rate));
    }

    static int delayFor(int eventIndex, int immediateThroughIndex, int delaySeconds) {
        if (delaySeconds <= 0 || eventIndex <= immediateThroughIndex) {
            return 0;
        }
        return delaySeconds;
    }

    static List<SendMessageBatchRequest> buildBatches(
            List<String[]> accounts,
            int start,
            int end,
            long baseMicros,
            String correlationAttr,
            String txHash0,
            String txHash1,
            int delaySeconds,
            int immediateThroughIndex,
            String queueUrl
    ) {
        List<SendMessageBatchRequest> batches = new ArrayList<>();
        List<SendMessageBatchRequestEntry> batch = new ArrayList<>(10);
        for (int a = 0; a < accounts.size(); a++) {
            String[] account = accounts.get(a);
            int accountOrdinal = a + 1;
            for (int idx = start; idx <= end; idx++) {
                long micros = baseMicros + idx;
                String balance = String.format(Locale.ROOT, "%.2f", (double) idx);
                long packed = ((long) accountOrdinal << 20) | (idx & 0xFFFFF);
                String tx = String.format(Locale.ROOT, "bbbbbbbb-%s-%s-8eee-%012x", txHash0, txHash1, packed);
                String corr = "perf-" + account[0] + "-" + idx;
                String body = "{\"transaction\":{\"id\":\"" + tx
                        + "\",\"type\":\"CREDIT\",\"amount\":\"10.00\",\"currency\":\"BRL\",\"status\":\"APPROVED\",\"timestamp\":"
                        + micros + "},\"account\":{\"id\":\"" + account[0] + "\",\"owner\":\"" + account[1]
                        + "\",\"created_at\":1609459200,\"status\":\"ENABLED\",\"balance\":{\"amount\":"
                        + balance + ",\"currency\":\"BRL\"}}}";

                var entry = SendMessageBatchRequestEntry.builder()
                        .id(Integer.toString(batch.size()))
                        .messageBody(body)
                        .messageAttributes(java.util.Map.of(
                                correlationAttr,
                                MessageAttributeValue.builder().dataType("String").stringValue(corr).build()));
                int delay = delayFor(idx, immediateThroughIndex, delaySeconds);
                if (delay > 0) {
                    entry.delaySeconds(delay);
                }
                batch.add(entry.build());
                if (batch.size() == 10) {
                    batches.add(SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(batch).build());
                    batch = new ArrayList<>(10);
                }
            }
        }
        if (!batch.isEmpty()) {
            batches.add(SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(batch).build());
        }
        return batches;
    }

    private static void sendAll(SqsAsyncClient sqs, List<SendMessageBatchRequest> batches, int maxInflight) {
        Semaphore inflight = new Semaphore(maxInflight);
        List<CompletableFuture<Void>> futures = new ArrayList<>(batches.size());
        for (SendMessageBatchRequest request : batches) {
            try {
                inflight.acquire();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while publishing", ex);
            }
            CompletableFuture<Void> future = sqs.sendMessageBatch(request)
                    .whenComplete((response, error) -> inflight.release())
                    .thenAccept(response -> {
                        if (response.hasFailed() && !response.failed().isEmpty()) {
                            throw new IllegalStateException("SendMessageBatch partial failure: " + response.failed());
                        }
                    });
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private static SqsAsyncClient buildClient(String endpoint, String region, int maxInflight) {
        var http = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(Math.max(maxInflight, 32))
                .connectionTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
        var builder = SqsAsyncClient.builder()
                .region(Region.of(region))
                .httpClient(http);
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(
                                    env("AWS_ACCESS_KEY_ID", "test"),
                                    env("AWS_SECRET_ACCESS_KEY", "test"))));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private static Properties loadMeta(Path metaFile) throws IOException {
        Properties props = new Properties();
        for (String line : Files.readAllLines(metaFile)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return props;
    }

    private static List<String[]> loadAccounts(Path csv) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(csv)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split(",", 2);
            if (parts.length == 2) {
                rows.add(parts);
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("no accounts in " + csv);
        }
        return rows;
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("missing env " + name);
        }
        return v;
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? defaultValue : v;
    }
}
