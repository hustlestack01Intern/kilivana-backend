package com.kilivana.audit.web;

import com.kilivana.audit.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuditLogInterceptor implements HandlerInterceptor {

    private static final Pattern ENTITY_ID = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private final AuditLogService auditLogService;

    public AuditLogInterceptor(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || request.getMethod().equals("GET")
                || request.getMethod().equals("OPTIONS")
                || request.getMethod().equals("HEAD")) {
            return true;
        }
        String controllerName = handlerMethod.getBeanType().getSimpleName();
        String entityType = controllerName.endsWith("Controller")
                ? controllerName.substring(0, controllerName.length() - "Controller".length())
                : controllerName;
        String uri = request.getRequestURI();
        UUID entityId = null;
        Matcher matcher = ENTITY_ID.matcher(uri);
        if (matcher.find()) {
            entityId = UUID.fromString(matcher.group(1));
        }
        auditLogService.record(
                request.getMethod(),
                entityType,
                entityId,
                uri,
                request.getRemoteAddr());
        return true;
    }
}