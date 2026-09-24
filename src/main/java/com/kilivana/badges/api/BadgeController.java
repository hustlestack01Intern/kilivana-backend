package com.kilivana.badges.api;

import com.kilivana.badges.service.BadgeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/badges")
public class BadgeController {

    private final BadgeService badgeService;

    public BadgeController(BadgeService badgeService) {
        this.badgeService = badgeService;
    }

    @GetMapping
    public ResponseEntity<List<BadgeResponse>> list() {
        return ResponseEntity.ok(badgeService.listBadges());
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<UserBadgeResponse>> badgesForUser(@PathVariable UUID userId) {
        return ResponseEntity.ok(badgeService.badgesForUser(userId));
    }

    @GetMapping("/mine")
    public ResponseEntity<List<UserBadgeResponse>> myBadges() {
        return ResponseEntity.ok(badgeService.myBadges());
    }

    @PostMapping("/award")
    public ResponseEntity<UserBadgeResponse> award(@Valid @RequestBody AwardBadgeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(badgeService.award(request));
    }
}