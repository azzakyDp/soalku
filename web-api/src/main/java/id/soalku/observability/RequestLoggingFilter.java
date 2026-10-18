package id.soalku.observability;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Set<String> KNOWN_PATHS = Set.of(
            "/api/auth/register", "/api/auth/login", "/api/auth/me", "/actuator/health");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String suppliedId = request.getHeader("X-Request-ID");
        String requestId = suppliedId != null && suppliedId.matches("[A-Za-z0-9._-]{1,64}")
                ? suppliedId : UUID.randomUUID().toString();
        String previousId = MDC.get("request_id");
        MDC.put("request_id", requestId);
        response.setHeader("X-Request-ID", requestId);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            // Never log bodies, query strings, Authorization, or arbitrary unmatched paths.
            log.atInfo().addKeyValue("event", "http.request.completed")
                    .addKeyValue("method", METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER")
                    .addKeyValue("path", route(request))
                    .addKeyValue("status", completed ? response.getStatus() : 500)
                    .addKeyValue("duration_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                    .log("HTTP request completed");
            if (previousId == null) MDC.remove("request_id");
            else MDC.put("request_id", previousId);
        }
    }

    private static String route(HttpServletRequest request) {
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (matched != null) return matched.toString();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return KNOWN_PATHS.contains(path) ? path : "<unmatched>";
    }
}
