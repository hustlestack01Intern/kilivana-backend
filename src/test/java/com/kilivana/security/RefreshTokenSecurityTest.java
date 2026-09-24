package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.RefreshTokenRequest;
import com.kilivana.auth.service.AuthService;
import com.kilivana.common.exception.AuthenticationFailedException;
import com.kilivana.users.domain.RefreshToken;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.RefreshTokenRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RefreshTokenSecurityTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private EntityManager entityManager;

    private AuthService authService;
    private TokenService tokenService;
    private User user;
    private UUID familyId;
    private String rawToken;
    private RefreshToken storedToken;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        user = new User(
                "buyer@example.com",
                "password-hash",
                "Buyer",
                "+254700000000",
                UserRole.BUYER,
                UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        familyId = UUID.randomUUID();
        rawToken = "refresh-token-value";
        storedToken = new RefreshToken(
                user,
                tokenService.hash(rawToken),
                OffsetDateTime.now().plusDays(1),
                familyId);
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                authenticationManager,
                jwtService,
                new JwtProperties("issuer", "audience", 15, 14, "a-very-long-high-entropy-secret-value-123456"),
                tokenService,
                entityManager);
    }

    @Test
    void rotatingATokenDetectsReuseAndRevokesTheWholeFamily() {
        when(refreshTokenRepository.findByTokenValue(tokenService.hash(rawToken)))
                .thenReturn(Optional.of(storedToken));
        when(refreshTokenRepository.findByFamilyId(familyId))
                .thenReturn(List.of(storedToken));
        when(jwtService.generateAccessToken(user)).thenReturn("new-access-token");
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse rotated = authService.refresh(new RefreshTokenRequest(rawToken));

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshToken replacement = tokenCaptor.getValue();
        assertThat(rotated.refreshToken()).isNotEqualTo(rawToken);
        assertThat(replacement.getFamilyId()).isEqualTo(familyId);
        assertThat(storedToken.isRevoked()).isTrue();

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest(rawToken)))
                .isInstanceOf(AuthenticationFailedException.class);
        verify(refreshTokenRepository, atLeastOnce()).findByFamilyId(familyId);
    }
}
