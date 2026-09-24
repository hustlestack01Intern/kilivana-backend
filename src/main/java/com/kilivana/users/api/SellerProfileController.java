package com.kilivana.users.api;

import com.kilivana.users.service.UserService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sellers")
public class SellerProfileController {

    private final UserService userService;

    public SellerProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/{sellerId}/profile")
    public ResponseEntity<PublicSellerProfileResponse> profile(@PathVariable UUID sellerId) {
        return ResponseEntity.ok(userService.publicSellerProfile(sellerId));
    }
}
