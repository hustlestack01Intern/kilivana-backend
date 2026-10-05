package com.kilivana.auth.service;

import com.kilivana.auth.api.ForgotPasswordResponse;
import com.kilivana.auth.api.ResetPasswordRequest;
import com.kilivana.common.exception.InvalidTokenException;
import com.kilivana.security.TokenService;
import com.kilivana.security.config.PasswordResetProperties;
import com.kilivana.users.domain.PasswordResetToken;
import com.kilivana.users.domain.RefreshToken;
import com.kilivana.users.domain.User;
import com.kilivana.users.repository.PasswordResetTokenRepository;
import com.kilivana.users.repository.RefreshTokenRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final PasswordResetMailSender mailSender;
    private final PasswordResetProperties properties;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository resetTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            PasswordResetMailSender mailSender,
            PasswordResetProperties properties) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.mailSender = mailSender;
        this.properties = properties;
        properties.validate();
    }

    @Transactional
    public ForgotPasswordResponse requestReset(String email) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String rawToken = userRepository.findByEmailIgnoreCase(normalizedEmail).map(user -> {
            resetTokenRepository.findByUserId(user.getId()).forEach(PasswordResetToken::markUsed);
            String candidate = tokenService.generate();
            OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(properties.tokenTtl());
            PasswordResetToken resetToken = new PasswordResetToken(
                    user,
                    tokenService.hash(candidate),
                    expiresAt);
            resetTokenRepository.save(resetToken);
            if (properties.simulateDelivery()) {
                return candidate;
            }
            try {
                mailSender.sendResetLink(user.getEmail(), candidate);
            } catch (RuntimeException exception) {
                // Deliberately no throwable argument: Spring's MailSendException message carries the
                // SMTP host and the authenticated username, and the stack trace would print both.
                // The exception class alone is enough to tell a timeout from a 5xx or a bad address.
                log.warn("Password reset mail delivery failed for userId={}, exceptionType={}; "
                        + "the reset token has been invalidated",
                        user.getId(), exception.getClass().getName());
                resetToken.markUsed();
            }
            return null;
        }).orElse(null);
        if (properties.simulateDelivery() && rawToken != null) {
            return ForgotPasswordResponse.devToken(rawToken);
        }
        return ForgotPasswordResponse.generic();
    }

    @Transactional
    public void reset(ResetPasswordRequest request) {
        PasswordResetToken resetToken = resetTokenRepository.findByToken(tokenService.hash(request.token().trim()))
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset token"));
        User user = resetToken.getUser();
        if (!user.getEmail().equalsIgnoreCase(request.email().trim())
                || !resetToken.isValid(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new InvalidTokenException("Invalid or expired reset token");
        }

        user.updatePassword(passwordEncoder.encode(request.newPassword()));
        resetTokenRepository.findByUserId(user.getId()).forEach(PasswordResetToken::markUsed);
        refreshTokenRepository.findByUserId(user.getId()).forEach(RefreshToken::revoke);
    }
}
