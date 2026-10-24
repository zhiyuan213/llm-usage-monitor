package com.metertrail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("metertrail.ingest.pipeline")
record IngestQueueOptions(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("true") boolean acceptorEnabled,
    @DefaultValue("2048") int maxInflight,
    @DefaultValue("33554432") long maxBufferedBytes,
    @DefaultValue("200") int writeBatchRequests,
    @DefaultValue("500") int groupEvents,
    @DefaultValue("2097152") int flushBytes,
    @DefaultValue("20") int coalesceMillis,
    @DefaultValue("20") int receiptPollMillis,
    @DefaultValue("5000") int waitMillis,
    @DefaultValue("20000") int maxQueuedRequests,
    @DefaultValue("50000") int maxQueuedEvents,
    @DefaultValue("134217728") long maxQueuedBytes,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("600") int terminalRetentionSeconds) {
  IngestQueueOptions {
    if (maxInflight < 1
        || maxBufferedBytes < 1
        || writeBatchRequests < 1
        || writeBatchRequests > 500
        || groupEvents < 1
        || groupEvents > 500
        || flushBytes < 1
        || coalesceMillis < 0
        || receiptPollMillis < 1
        || waitMillis < 1
        || maxQueuedRequests < 1
        || maxQueuedEvents < 1
        || maxQueuedBytes < 1
        || maxAttempts < 1
        || terminalRetentionSeconds < 60)
      throw new IllegalArgumentException("Invalid durable ingest queue budgets");
  }
}
