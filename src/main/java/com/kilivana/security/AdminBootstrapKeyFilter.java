package com.kilivana.security;

import com.kilivana.admin.config.AdminProperties;
import com.kilivana.admin.service.AdminSignupService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminBootstrapKeyFilter extends OncePerRequestFilter {

    private static final String BOOTSTRAP_PATH = "/api/v1/admins/signup";
    private static final int MINIMUM_KEY_LENGTH = 16;

    private final AdminProperties adminProperties;
    private final ApiSecurityErrorHandler securityErrorHandler;

    public AdminBootstrapKeyFilter(
            AdminProperties adminProperties,
            ApiSecurityErrorHandler securityErrorHandler) {
        this.adminProperties = adminProperties;
        this.securityErrorHandler = securityErrorHandler;
        validateConfiguredKey();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isBootstrapRequest(request) || validKey(request.getHeader(AdminSignupService.BOOTSTRAP_KEY_HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }
        securityErrorHandler.handle(request, response, new AccessDeniedException("Invalid administrator bootstrap key"));
    }

    private boolean isBootstrapRequest(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return BOOTSTRAP_PATH.equals(path);
    }

    private boolean validKey(String candidate) {
        String configured = adminProperties.bootstrapKey();
        if (!adminProperties.isEnabled() || configured.length() < MINIMUM_KEY_LENGTH
                || candidate == null || candidate.length() < MINIMUM_KEY_LENGTH) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private void validateConfiguredKey() {
        if (adminProperties.isEnabled() && adminProperties.bootstrapKey().length() < MINIMUM_KEY_LENGTH) {
            throw new IllegalStateException("Administrator bootstrap key must be at least 16 characters");
        }
    }
}
