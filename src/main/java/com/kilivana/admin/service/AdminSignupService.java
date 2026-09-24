package com.kilivana.admin.service;

import com.kilivana.admin.api.AdminSignupRequest;
import com.kilivana.admin.config.AdminProperties;
import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.service.AuthService;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.users.domain.UserRole;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class AdminSignupService {

    public static final String BOOTSTRAP_KEY_HEADER = "X-Admin-Bootstrap-Key";

    private final AuthService authService;
    private final AdminProperties adminProperties;

    public AdminSignupService(AuthService authService, AdminProperties adminProperties) {
        this.authService = authService;
        this.adminProperties = adminProperties;
    }

    @Transactional
    public AuthResponse signup(AdminSignupRequest request, String bootstrapKey) {
        if (!adminProperties.isEnabled() || !adminProperties.bootstrapKey().equals(bootstrapKey)) {
            throw new UnauthorizedOperationException("Missing or invalid admin bootstrap key");
        }
        return authService.registerAndAuthenticate(
                request.email(),
                request.password(),
                request.fullName(),
                request.phoneNumber(),
                UserRole.ADMIN);
    }
}