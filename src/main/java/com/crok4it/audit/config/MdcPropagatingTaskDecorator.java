package com.crok4it.audit.config;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * TaskDecorator that copies the MDC context map of the submitting thread
 * into the executing thread, then restores the previous state after execution.
 *
 * Required for correlationId to survive the async boundary between the publisher
 * thread (where CorrelationIdFilter put it) and the listener thread.
 *
 * Replaces Spring's ContextPropagatingTaskDecorator which requires
 * micrometer-context-propagation on the classpath.
 */
class MdcPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Snapshot MDC of the submitting (publisher) thread
        Map<String, String> submitterMdc = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> executorPreviousMdc = MDC.getCopyOfContextMap();
            try {
                if (submitterMdc != null) {
                    MDC.setContextMap(submitterMdc);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                if (executorPreviousMdc != null) {
                    MDC.setContextMap(executorPreviousMdc);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
