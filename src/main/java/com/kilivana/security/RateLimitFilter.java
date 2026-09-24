package com.kilivana.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilivana.common.api.ApiError;
import com.kilivana.common.config.RequestContextFilter;
import com.kilivana.security.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final RateLimitProperties properties;
    private final Integer legacyLimit;
    private final Duration legacyWindow;

    public RateLimitFilter(
            RateLimitProperties properties,
            RateLimiter rateLimiter,
            ClientIpResolver clientIpResolver,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.legacyLimit = null;
        this.legacyWindow = WINDOW;
    }

    public RateLimitFilter(int maxRequests, Duration window) {
        this.properties = null;
        this.rateLimiter = new InMemoryRateLimiter(Math.max(1, maxRequests * 10));
        this.clientIpResolver = new ClientIpResolver(
                new com.kilivana.security.config.ProxyProperties(false, java.util.List.of()));
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.legacyLimit = maxRequests;
        this.legacyWindow = window == null || window.isZero() || window.isNegative() ? WINDOW : window;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (properties != null && !properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimit rule = ruleFor(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = rule.name() + ":" + clientIpResolver.resolve(request);
        Duration window = properties == null ? legacyWindow : WINDOW;
        RateLimitResult result = rateLimiter.tryConsume(clientKey, rule.limit(), window);
        if (result.status() == RateLimitResult.Status.ALLOWED) {
            filterChain.doFilter(request, response);
            return;
        }
        if (result.status() == RateLimitResult.Status.UNAVAILABLE) {
            writeError(request, response, 503, "RATE_LIMIT_UNAVAILABLE", "Request protection is temporarily unavailable");
            return;
        }
        response.setHeader("Retry-After", Long.toString(result.retryAfterSeconds()));
        writeError(request, response, 429, "RATE_LIMITED", "Too many requests. Please retry later.");
    }

    private RateLimit ruleFor(HttpServletRequest request) {
        if (legacyLimit != null) {
            return request.getRequestURI().startsWith("/api/")
                    ? new RateLimit("all", legacyLimit)
                    : null;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        if (path.equals("/api/v1/auth/login")) {
            return new RateLimit("login", properties.loginRequestsPerMinute());
        }
        if (path.equals("/api/v1/auth/signup") || path.equals("/api/v1/auth/register")) {
            return new RateLimit("register", properties.registrationRequestsPerMinute());
        }
        if (path.equals("/api/v1/auth/forgot-password")) {
            return new RateLimit("forgot-password", properties.forgotPasswordRequestsPerMinute());
        }
        if (path.equals("/api/v1/admins/signup")) {
            return new RateLimit("admin-bootstrap", properties.adminBootstrapRequestsPerMinute());
        }
        return null;
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String requestId = MDC.get(RequestContextFilter.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = (String) request.getAttribute(RequestContextFilter.REQUEST_ID);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(RequestContextFilter.REQUEST_ID_HEADER);
        }
        if (requestId != null && !requestId.isBlank()) {
            response.setHeader(RequestContextFilter.REQUEST_ID_HEADER, requestId);
        }
        ApiError error = new ApiError(
                code,
                message,
                status,
                request.getRequestURI(),
                requestId,
                OffsetDateTime.now(),
                List.of());
        objectMapper.writeValue(response.getOutputStream(), error);
    }

    private record RateLimit(String name, int limit) {
    }
}
