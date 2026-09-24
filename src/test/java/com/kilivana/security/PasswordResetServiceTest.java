package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kilivana.auth.api.ForgotPasswordResponse;
import com.kilivana.auth.api.ResetPasswordRequest;
import com.kilivana.auth.service.PasswordResetMailSender;
import com.kilivana.auth.service.PasswordResetService;
import com.kilivana.security.config.PasswordResetProperties;
import com.kilivana.users.domain.PasswordResetToken;
import com.kilivana.users.domain.RefreshToken;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.PasswordResetTokenRepository;
import com.kilivana.users.repository.RefreshTokenRepository;
import com.kilivana.users.repository.UserRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordResetTokenRepository resetTokenRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PasswordResetMailSender mailSender;

    private PasswordResetService passwordResetService;
    private TokenService tokenService;
    private PasswordResetProperties properties;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        properties = new PasswordResetProperties(Duration.ofMinutes(15), "https://example.test/reset");
        passwordResetService = new PasswordResetService(
                userRepository,
                resetTokenRepository,
                refreshTokenRepository,
                passwordEncoder,
                tokenService,
                mailSender,
                properties);
    }

    @Test
    void forgotPasswordUsesTheSameResponseForExistingAndUnknownEmails() {
        UUID userId = UUID.randomUUID();
        User user = user("buyer@example.com", userId);
        when(userRepository.findByEmailIgnoreCase("buyer@example.com"))
                .thenReturn(Optional.of(user))
                .thenReturn(Optional.empty());
        when(resetTokenRepository.findByUserId(userId)).thenReturn(List.of());

        ForgotPasswordResponse existing = passwordResetService.requestReset("buyer@example.com");
        ForgotPasswordResponse missing = passwordResetService.requestReset("missing@example.com");

        assertThat(existing).isEqualTo(missing);
        assertThat(existing.message()).doesNotContain("token");
        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(resetTokenRepository).save(tokenCaptor.capture());
        ArgumentCaptor<String> rawTokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailSender).sendResetLink(org.mockito.ArgumentMatchers.eq("buyer@example.com"), rawTokenCaptor.capture());
        assertThat(tokenCaptor.getValue().getToken())
                .hasSize(64)
                .isNotEqualTo(rawTokenCaptor.getValue());
    }

    @Test
    void resetConsumesTheTokenAndRevokesEveryRefreshToken() {
        UUID userId = UUID.randomUUID();
        User user = user("buyer@example.com", userId);
        String rawToken = "reset-token-value";
        PasswordResetToken resetToken = new PasswordResetToken(
                user,
                tokenService.hash(rawToken),
                OffsetDateTime.now().plusMinutes(10));
        RefreshToken refreshToken = new RefreshToken(
                user,
                tokenService.hash("refresh-token"),
                OffsetDateTime.now().plusDays(1));
        when(resetTokenRepository.findByToken(tokenService.hash(rawToken)))
                .thenReturn(Optional.of(resetToken));
        when(resetTokenRepository.findByUserId(userId)).thenReturn(List.of(resetToken));
        when(refreshTokenRepository.findByUserId(userId)).thenReturn(List.of(refreshToken));
        when(passwordEncoder.encode("newSecret1")).thenReturn("new-password-hash");

        passwordResetService.reset(new ResetPasswordRequest(
                "buyer@example.com",
                rawToken,
                "newSecret1"));

        assertThat(user.getPasswordHash()).isEqualTo("new-password-hash");
        assertThat(resetToken.isValid(OffsetDateTime.now())).isFalse();
        assertThat(refreshToken.isRevoked()).isTrue();
    }

    private User user(String email, UUID id) {
        User user = new User(email, "old-password-hash", "Buyer", "+254700000000", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
