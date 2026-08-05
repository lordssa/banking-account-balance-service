package com.itau.account.adapter.out.observability;


public final class AccountMetricNames {

    public static final String INGESTION_EVENTS = "ingestion.events";
    public static final String INGESTION_PROCESSING = "ingestion.processing";
    public static final String INGESTION_POLL_ERRORS = "ingestion.poll.errors";
    public static final String INGESTION_RETRIES = "ingestion.retries";
    public static final String INGESTION_RETRY_EXHAUSTED = "ingestion.retry.exhausted";
    public static final String INGESTION_PERMANENT_FAILURES = "ingestion.permanent.failures";
    public static final String INGESTION_CONFLICTS = "ingestion.conflicts";
    public static final String INGESTION_ACK_FAILURES = "ingestion.ack.failures";
    public static final String INGESTION_UNEXPECTED_FAILURES = "ingestion.unexpected.failures";
    public static final String SQS_TOPOLOGY_VALID = "sqs.topology.valid";

    public static final String CONSUMER_RUNNING = "ingestion.consumer.running";
    public static final String CONSUMER_IN_FLIGHT = "ingestion.consumer.in_flight";
    public static final String CONSUMER_PERMITS_AVAILABLE = "ingestion.consumer.permits_available";

    public static final String DB_OPERATION = "db.operation";
    public static final String DB_OPERATION_FAILURES = "db.operation.failures";

    public static final String BALANCE_RETURNED_AGE_SECONDS = "balance.returned_age_seconds";

    private AccountMetricNames() {
    }
}
