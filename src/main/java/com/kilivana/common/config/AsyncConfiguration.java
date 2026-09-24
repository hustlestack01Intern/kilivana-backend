package com.kilivana.common.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfiguration {

    public static final String EVENT_EXECUTOR = "kilivanaEventExecutor";

    @Bean(name = EVENT_EXECUTOR)
    public Executor eventExecutor() {
        return new SimpleAsyncTaskExecutor("kilivana-events-");
    }

    @Bean
    public ApplicationEventMulticaster applicationEventMulticaster(
            @Qualifier(EVENT_EXECUTOR) Executor eventExecutor) {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setTaskExecutor(eventExecutor);
        return multicaster;
    }
}