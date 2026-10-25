package com.metertrail;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class SchedulingConfiguration {
  // Existing maintenance/provider schedulers remain owned by UsageBillingScheduler.
  // SMTP timeouts and statistics collection cannot pause this dedicated consumer.
  @Bean(name = "ingestWorkerScheduler")
  ThreadPoolTaskScheduler ingestScheduler() {
    return scheduler("ingest-worker-");
  }

  private ThreadPoolTaskScheduler scheduler(String prefix) {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix(prefix);
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(20);
    return scheduler;
  }

  @Bean(name = "weeklyInsightsScheduler")
  ThreadPoolTaskScheduler weeklyInsightsScheduler() {
    return scheduler("weekly-insights-");
  }
}
