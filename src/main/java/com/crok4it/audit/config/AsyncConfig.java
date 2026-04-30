package com.crok4it.audit.config;

import io.micrometer.context.ContextRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the default @Async executor with Micrometer's ContextPropagatingTaskDecorator.
 *
 * ContextPropagatingTaskDecorator snapshots ALL registered ThreadLocalAccessors (MDC, SLF4J,
 * CurrentAuditHolder, etc.) from the submitting thread and restores them in the executing thread.
 * Requires io.micrometer:context-propagation on the classpath (managed by Spring Boot BOM).
 *
 * This executor is used by:
 *   - @AuditableListener methods (developer listeners)
 *   - GenericAuditListener.onAuditableEvent
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    static {
        // Register CurrentAuditHolder so ContextPropagatingTaskDecorator propagates it
        // alongside MDC across every @Async boundary in this application.
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(new CurrentAuditHolderAccessor());
    }

    @Override
    public AsyncTaskExecutor getAsyncExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("audit-async-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
