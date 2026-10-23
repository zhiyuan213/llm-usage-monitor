package com.metertrail;

/** Separate JVM used only by the isolated hard-kill recovery test. */
public final class WorkerProcessDrill {
  public static void main(String[] args) {
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(
        System.getenv("DRILL_JDBC_URL"),System.getenv("DRILL_DB_USER"),System.getenv("DRILL_DB_PASSWORD"));
    var store=new Store(new org.springframework.jdbc.core.JdbcTemplate(source),new Secrets("isolated-worker-process-test-secret-32-characters"));
    var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    var worker=new Worker(store,tx,new MailDelivery(null,null,0,0),true,"synthetic@example.test");
    for(int i=0;i<25;i++)worker.process();
  }
}
