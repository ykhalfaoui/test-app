package com.crok4it.audit.entry;

import com.crok4it.audit.core.MdcKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Captures (or generates) the correlationId at the earliest point in the HTTP request.
 * Reads X-Correlation-Id header; generates a UUID if absent.
 * Sets MDC and echoes the value back in the response header.
 *
 * Runs before everything else (@Order HIGHEST_PRECEDENCE).
 * Cleans up MDC in finally — no leaks between requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        response.setHeader(HEADER, correlationId);

        MDC.put(MdcKeys.CORRELATION_ID, correlationId);
        MDC.put(MdcKeys.SOURCE_SYSTEM, "HTTP");
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.CORRELATION_ID);
            MDC.remove(MdcKeys.SOURCE_SYSTEM);
        }
    }
}
