package com.kilivana.users.api;

import com.kilivana.users.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me() {
        return ResponseEntity.ok(userService.currentProfile());
    }

    @PutMapping("/me")
    public ResponseEntity<UserProfileResponse> updateMe(@Valid @RequestBody UpdateUserProfileRequest request) {
        return ResponseEntity.ok(userService.updateOwnProfile(request));
    }

    @GetMapping
    public ResponseEntity<List<UserProfileResponse>> listProfiles() {
        return ResponseEntity.ok(userService.listProfiles());
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserProfileResponse> profileById(@PathVariable UUID userId) {
        return ResponseEntity.ok(userService.profileById(userId));
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<UserProfileResponse> updateStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserStatusRequest request) {
        return ResponseEntity.ok(userService.updateStatus(userId, request));
    }

    @PatchMapping("/{userId}/verification")
    public ResponseEntity<UserProfileResponse> updateVerification(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserVerificationRequest request) {
        return ResponseEntity.ok(userService.updateVerification(userId, request));
    }
}