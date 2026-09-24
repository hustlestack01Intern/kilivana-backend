package com.kilivana.auth.service;

import com.kilivana.users.repository.PasswordResetTokenRepository;
import com.kilivana.users.repository.RefreshTokenRepository;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ExpiredTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;

    public ExpiredTokenCleanupJob(
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
    }

    @Scheduled(fixedDelayString = "${kilivana.security.jwt.refresh-cleanup-ms:3600000}")
    @Transactional
    public void removeExpiredTokens() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        refreshTokenRepository.deleteByExpiresAtBefore(now);
        passwordResetTokenRepository.deleteByExpiresAtBefore(now);
    }
}
