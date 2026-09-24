package com.kilivana.badges.service;

import com.kilivana.badges.api.AwardBadgeRequest;
import com.kilivana.badges.api.BadgeResponse;
import com.kilivana.badges.api.UserBadgeResponse;
import com.kilivana.badges.domain.Badge;
import com.kilivana.badges.domain.UserBadge;
import com.kilivana.badges.repository.BadgeRepository;
import com.kilivana.badges.repository.UserBadgeRepository;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.common.exception.UnauthorizedOperationException;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BadgeService {

    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public BadgeService(
            BadgeRepository badgeRepository,
            UserBadgeRepository userBadgeRepository,
            UserRepository userRepository,
            CurrentUser currentUser) {
        this.badgeRepository = badgeRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    public List<BadgeResponse> listBadges() {
        return badgeRepository.findAll().stream().map(this::toResponse).toList();
    }

    public List<UserBadgeResponse> badgesForUser(UUID userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return userBadgeRepository.findByUserId(userId).stream().map(this::toResponse).toList();
    }

    public List<UserBadgeResponse> myBadges() {
        return badgesForUser(currentUser.required().getId());
    }

    @Transactional
    public UserBadgeResponse award(AwardBadgeRequest request) {
        AuthenticatedUser actor = currentUser.required();
        if (actor.getRole() != UserRole.INSPECTOR) {
            throw new UnauthorizedOperationException("Only inspectors can award badges");
        }

        User recipient = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Badge badge = badgeRepository.findByCode(request.badgeCode().trim())
                .orElseThrow(() -> new ResourceNotFoundException("Badge not found"));
        User awardedBy = userRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (userBadgeRepository.existsByUserIdAndBadgeId(recipient.getId(), badge.getId())) {
            throw new BusinessConflictException("User already holds this badge");
        }

        String note = request.note() == null ? "" : request.note().trim();
        UserBadge userBadge = new UserBadge(recipient, badge, awardedBy, note);
        userBadgeRepository.save(userBadge);
        return toResponse(userBadge);
    }

    @Transactional
    public void autoAwardByCode(User recipient, User awardedBy, String badgeCode) {
        badgeRepository.findByCode(badgeCode).ifPresent(badge -> {
            if (!userBadgeRepository.existsByUserIdAndBadgeId(recipient.getId(), badge.getId())) {
                userBadgeRepository.save(new UserBadge(
                        recipient,
                        badge,
                        awardedBy,
                        "Awarded automatically following a passed inspection"));
            }
        });
    }

    private BadgeResponse toResponse(Badge badge) {
        return new BadgeResponse(
                badge.getId(),
                badge.getCode(),
                badge.getName(),
                badge.getDescription(),
                badge.getIcon());
    }

    private UserBadgeResponse toResponse(UserBadge userBadge) {
        Badge badge = userBadge.getBadge();
        return new UserBadgeResponse(
                userBadge.getId(),
                userBadge.getUser().getId(),
                badge.getCode(),
                badge.getName(),
                badge.getDescription(),
                badge.getIcon(),
                userBadge.getAwardedBy().getFullName(),
                userBadge.getNote(),
                userBadge.getAwardedAt());
    }
}