package com.crok4it.audit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the default @Async executor with MdcPropagatingTaskDecorator.
 *
 * MdcPropagatingTaskDecorator snapshots the MDC of the submitting thread
 * and restores it in the executing thread.
 * Without this, the correlationId would be lost when crossing the async
 * boundary from the publisher thread to the listener thread.
 *
 * This executor is used by:
 *   - @AuditableListener methods (developer listeners)
 *   - GenericAuditListener.onAuditableEvent
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncTaskExecutor getAsyncExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-async-");
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
