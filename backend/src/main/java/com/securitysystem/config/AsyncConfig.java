package com.securitysystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures the async thread pool used by AISecurityService.
 *
 * All @Async("aiTaskExecutor") calls (TTS generation + WebSocket push) run on
 * this pool so the sensor-event HTTP thread is never blocked by OpenAI latency.
 *
 * Sizing rationale:
 *   - Core 2: handles simultaneous alarms on two zones without delay
 *   - Max 4:  burst headroom; more than 4 concurrent alarms = system overload anyway
 *   - Queue 10: absorbs rapid bursts before rejecting (CallerRunsPolicy fallback)
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "aiTaskExecutor")
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("ai-async-");
        executor.initialize();
        return executor;
    }
}
