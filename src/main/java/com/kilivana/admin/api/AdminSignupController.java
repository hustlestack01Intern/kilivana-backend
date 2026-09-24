package com.kilivana.admin.api;

import com.kilivana.admin.service.AdminSignupService;
import com.kilivana.auth.api.AuthResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admins")
public class AdminSignupController {

    private final AdminSignupService adminSignupService;

    public AdminSignupController(AdminSignupService adminSignupService) {
        this.adminSignupService = adminSignupService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(
            @RequestHeader(value = AdminSignupService.BOOTSTRAP_KEY_HEADER, required = false) String bootstrapKey,
            @Valid @RequestBody AdminSignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminSignupService.signup(request, bootstrapKey));
    }
}