package com.metertrail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Provider IO must not delay ingestion workers, mail retries, or retention jobs. */
@Configuration("usageBillingSchedulingConfiguration")
class UsageBillingScheduler {
  @Bean(name="taskScheduler")
  ThreadPoolTaskScheduler taskScheduler() {
    var scheduler=new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("worker-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(20);
    return scheduler;
  }
  @Bean(name="usageBillingScheduler")
  ThreadPoolTaskScheduler usageBillingScheduler() {
    var scheduler=new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("usage-billing-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(20);
    return scheduler;
  }
}
