package com.kilivana.auth.service;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.LoginRequest;
import com.kilivana.auth.api.RefreshTokenRequest;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.common.exception.AuthenticationFailedException;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.security.JwtProperties;
import com.kilivana.security.JwtService;
import com.kilivana.security.TokenService;
import com.kilivana.users.domain.BuyerProfile;
import com.kilivana.users.domain.FarmerProfile;
import com.kilivana.users.domain.RefreshToken;
import com.kilivana.users.domain.SupplierProfile;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.RefreshTokenRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Set<UserRole> PUBLIC_ROLES = Set.of(
            UserRole.BUYER,
            UserRole.FARMER,
            UserRole.SUPPLIER);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final TokenService tokenService;
    private final EntityManager entityManager;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            JwtProperties jwtProperties,
            TokenService tokenService,
            EntityManager entityManager) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.tokenService = tokenService;
        this.entityManager = entityManager;
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (request.role() == null || !PUBLIC_ROLES.contains(request.role())) {
            throw new BusinessConflictException("Public registration is limited to buyers, farmers and suppliers");
        }
        validateRoleProfileData(request);

        User user = registerUser(request.email(), request.password(), request.fullName(), request.phoneNumber(), request.role());
        createRoleProfile(request, user);
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse registerAndAuthenticate(
            String email,
            String rawPassword,
            String fullName,
            String phoneNumber,
            UserRole role) {
        if (role != UserRole.ADMIN) {
            throw new BusinessConflictException("The provisioning path can only create administrator accounts");
        }
        if (userRepository.existsByRole(UserRole.ADMIN)) {
            throw new BusinessConflictException("Administrator bootstrap has already been completed");
        }
        User user = registerUser(email, rawPassword, fullName, phoneNumber, role);
        return issueTokens(user);
    }

    private User registerUser(String email, String rawPassword, String fullName, String phoneNumber, UserRole role) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessConflictException("Email is already registered");
        }
        User user = new User(
                normalizedEmail,
                passwordEncoder.encode(rawPassword),
                fullName.trim(),
                phoneNumber.trim(),
                role,
                UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AuthenticationFailedException("Invalid credentials"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationFailedException("Invalid credentials");
        }
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String presentedToken = request.refreshToken().trim();
        RefreshToken existing = refreshTokenRepository.findByTokenValue(tokenService.hash(presentedToken))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (existing.isRevoked()) {
            revokeFamily(existing);
            throw new AuthenticationFailedException("Invalid refresh token");
        }
        if (existing.isExpired(now)) {
            revokeFamily(existing);
            throw new AuthenticationFailedException("Invalid refresh token");
        }

        User user = existing.getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            revokeFamily(existing);
            throw new AuthenticationFailedException("Invalid refresh token");
        }

        existing.revoke();
        return issueTokens(user, existing.getFamilyId());
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.findByTokenValue(tokenService.hash(refreshToken.trim()))
                .map(RefreshToken::getUser)
                .ifPresent(user -> refreshTokenRepository.findByUserId(user.getId())
                        .forEach(RefreshToken::revoke));
    }

    private void revokeFamily(RefreshToken token) {
        if (token.getFamilyId() == null) {
            refreshTokenRepository.findByUserId(token.getUser().getId()).forEach(RefreshToken::revoke);
            return;
        }
        refreshTokenRepository.findByFamilyId(token.getFamilyId()).forEach(RefreshToken::revoke);
    }

    private void validateRoleProfileData(SignupRequest request) {
        if (request.role() == UserRole.SUPPLIER && isBlank(request.businessName())) {
            throw new BusinessConflictException("Supplier accounts require businessName");
        }
        if (request.role() == UserRole.FARMER && (isBlank(request.farmName()) || isBlank(request.farmLocation()))) {
            throw new BusinessConflictException("Farmer accounts require farmName and farmLocation");
        }
    }

    private void createRoleProfile(SignupRequest request, User user) {
        switch (request.role()) {
            case BUYER -> entityManager.persist(new BuyerProfile(user));
            case SUPPLIER -> entityManager.persist(new SupplierProfile(user, request.businessName().trim()));
            case FARMER -> entityManager.persist(new FarmerProfile(
                    user,
                    request.farmName().trim(),
                    request.farmLocation().trim()));
            case INSPECTOR, DRIVER, ADMIN -> throw new BusinessConflictException("Role is not available for public registration");
        }
    }

    private AuthResponse issueTokens(User user) {
        return issueTokens(user, UUID.randomUUID());
    }

    private AuthResponse issueTokens(User user, UUID familyId) {
        String accessToken = jwtService.generateAccessToken(user);
        OffsetDateTime refreshExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(jwtProperties.refreshTokenDays());
        String refreshValue = tokenService.generate();
        RefreshToken refreshToken = new RefreshToken(
                user,
                tokenService.hash(refreshValue),
                refreshExpiresAt,
                familyId);
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                accessToken,
                refreshValue,
                refreshExpiresAt);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
