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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetService {

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
        userRepository.findByEmailIgnoreCase(normalizedEmail).ifPresent(user -> {
            resetTokenRepository.findByUserId(user.getId()).forEach(PasswordResetToken::markUsed);
            String rawToken = tokenService.generate();
            OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(properties.tokenTtl());
            PasswordResetToken resetToken = new PasswordResetToken(
                    user,
                    tokenService.hash(rawToken),
                    expiresAt);
            resetTokenRepository.save(resetToken);
            try {
                mailSender.sendResetLink(user.getEmail(), rawToken);
            } catch (RuntimeException exception) {
                resetToken.markUsed();
            }
        });
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
