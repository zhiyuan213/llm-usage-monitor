package com.metertrail;

/** Real producer JVM killed by the recovery integration test after the durable-write marker. */
public final class DurableQueueProcessDrill {
  public static void main(String[] args) throws Exception {
    System.setProperty("spring.datasource.url", System.getenv("DRILL_JDBC_URL"));
    System.setProperty("spring.datasource.username", System.getenv("DRILL_DB_USER"));
    System.setProperty("spring.datasource.password", System.getenv("DRILL_DB_PASSWORD"));
    System.setProperty("metertrail.secret", "durable-process-drill-test-secret-32-characters");
    System.setProperty("metertrail.worker-enabled", "false");
    System.setProperty("metertrail.ingest.pipeline.enabled", "true");
    System.setProperty("metertrail.ingest.pipeline.acceptor-enabled", "false");
    System.setProperty("metertrail.paddle-api-key", "");
    System.setProperty("server.port", "0");
    System.setProperty("management.server.port", "0");
    var context = org.springframework.boot.SpringApplication.run(Application.class);
    var store = context.getBean(Store.class);
    var ticket =
        context
            .getBean(IngestPipeline.class)
            .submit(
                store.keyScope("Bearer " + System.getenv("DRILL_API_KEY")),
                Support.parse(System.getenv("DRILL_PAYLOAD")));
    ticket.durable.get(20, java.util.concurrent.TimeUnit.SECONDS);
    System.out.println("QUEUE_DURABLE " + ticket.id);
    System.out.flush();
    Thread.sleep(120000);
  }
}
