package com.test.shortlink.conf;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class ExecutorConf {
    @Value("${app.executor.core-pool-size:10}")
    private int corePoolSize;
    @Value("${app.executor.max-pool-size:100}")
    private int maxPoolSize;
    @Value("${app.executor.queue-capacity:1000}")
    private int queueCapacity;

    @Bean(name="asyncExecutor")
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.initialize();
        return executor;
    }
}
