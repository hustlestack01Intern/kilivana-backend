package com.kilivana.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilivana.common.api.ApiError;
import com.kilivana.common.config.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ApiSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, 401, "AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, 403, "FORBIDDEN", "You do not have permission to perform this action");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String requestId = MDC.get(RequestContextFilter.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = (String) request.getAttribute(RequestContextFilter.REQUEST_ID);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader("X-Request-Id");
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = response.getHeader("X-Request-Id");
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (requestId != null && !requestId.isBlank()) {
            response.setHeader("X-Request-Id", requestId);
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
}
