package com.cobalto.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@Profile("!migrate")
public class RateLimitFilter extends OncePerRequestFilter {
    private static final int REQUESTS_PER_MINUTE = 120;
    private final Cache<String, RequestWindow> windows = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofHours(1))
        .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long now = System.currentTimeMillis();
        RequestWindow window = windows.get(request.getRemoteAddr(), ignored -> new RequestWindow(now));
        if (!window.acquire(now)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write(
                "{\"status\":429,\"code\":\"RATE_LIMITED\",\"message\":\"Demasiadas solicitudes\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static final class RequestWindow {
        private long startedAt;
        private int requestCount;

        private RequestWindow(long now) {
            startedAt = now;
        }

        private synchronized boolean acquire(long now) {
            if (now - startedAt >= 60_000) {
                startedAt = now;
                requestCount = 0;
            }
            return ++requestCount <= REQUESTS_PER_MINUTE;
        }
    }
}
