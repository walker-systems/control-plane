package dev.jwalker.controlplane.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// Explicit TaskScheduler bean so scheduling stays on a fixed-size
// thread pool even when spring.threads.virtual.enabled=true.
//
// Without this bean, Spring Boot 4 sees the virtual-threads flag and
// auto-wires a SimpleAsyncTaskScheduler, which ignores
// spring.task.scheduling.pool.size entirely. That leaves the three
// @Scheduled ticks (schedule materializer, executor, watchdog)
// sharing a single internal timing thread — so a slow simulated
// executor batch (up to ~160s at the demo defaults) would delay the
// watchdog well past its 60s tick target.
//
// We want virtual threads for HTTP request handling and @Async, but
// not for scheduling — scheduling is low-frequency, benefits from a
// small deterministic pool, and needs guaranteed non-blocking
// behavior across tasks. Defining our own TaskScheduler here overrides
// the auto-configuration cleanly (TaskSchedulingAutoConfiguration is
// @ConditionalOnMissingBean).
@Configuration
public class SchedulerConfig {

    @Bean
    public TaskScheduler taskScheduler(
            @Value("${app.scheduler.pool-size:5}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("cp-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
