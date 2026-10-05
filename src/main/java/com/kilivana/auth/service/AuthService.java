package com.kilivana.auth.service;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.LoginRequest;
import com.kilivana.auth.api.RefreshTokenRequest;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.common.exception.AuthenticationFailedException;
import com.kilivana.common.exception.AccountLoginThrottledException;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.security.JwtProperties;
import com.kilivana.security.JwtService;
import com.kilivana.security.RateLimitResult;
import com.kilivana.security.RateLimiter;
import com.kilivana.security.TokenService;
import com.kilivana.security.config.RateLimitProperties;
import com.kilivana.users.domain.BuyerProfile;
import com.kilivana.users.domain.InspectorProfile;
import com.kilivana.users.domain.VerificationStatus;
import com.kilivana.users.domain.FarmerProfile;
import com.kilivana.users.domain.InspectorProfile;
import com.kilivana.users.domain.RefreshToken;
import com.kilivana.users.domain.SupplierProfile;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.RefreshTokenRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Namespaced so this key can never collide with the filter's {@code <rule>:<address>} keys. */
    private static final String ACCOUNT_LOGIN_KEY_PREFIX = "login-account:";

    private static final Set<UserRole> PUBLIC_ROLES = Set.of(
            UserRole.BUYER,
            UserRole.FARMER,
            UserRole.SUPPLIER,
            UserRole.INSPECTOR);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final TokenService tokenService;
    private final EntityManager entityManager;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;

    /** Lazily built by {@link #spendDummyPasswordComparison()}; see there for why it is not a constant. */
    private volatile String dummyPasswordHash;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            JwtProperties jwtProperties,
            TokenService tokenService,
            EntityManager entityManager,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.tokenService = tokenService;
        this.entityManager = entityManager;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
    }

    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (request.role() == null || !PUBLIC_ROLES.contains(request.role())) {
            throw new BusinessConflictException("Public registration is limited to buyers, farmers, suppliers and inspectors");
        }
        validateRoleProfileData(
                request.role(),
                request.businessName(),
                request.farmName(),
                request.farmLocation(),
                request.employeeCode());

        User user = registerUser(request.email(), request.password(), request.fullName(), request.phoneNumber(), request.role());
        if (request.role() == UserRole.INSPECTOR) {
            user.markVerification(VerificationStatus.PENDING);
        }
        createRoleProfile(
                request.role(),
                request.businessName(),
                request.farmName(),
                request.farmLocation(),
                request.employeeCode(),
                user);
        return issueTokens(user);
    }

    /**
     * Registration for an account that has no password because it was created from a verified
     * Google identity.
     *
     * <p>Delegates to the same role gate, the same role-field validation, the same profile
     * creation and the same token issuance as {@link #signup(SignupRequest)}, and differs in
     * exactly two things: there is no password to encode, and {@code emailVerifiedAt} is set
     * inside this transaction so the row is never briefly visible in a state where the
     * address is claimed but not proven.
     */
    @Transactional
    public User registerFromVerifiedEmail(
            String normalizedEmail,
            String fullName,
            String phoneNumber,
            UserRole role,
            String businessName,
            String farmName,
            String farmLocation,
            String employeeCode) {
        if (role == null || !PUBLIC_ROLES.contains(role)) {
            throw new BusinessConflictException("Public registration is limited to buyers, farmers, suppliers and inspectors");
        }
        validateRoleProfileData(role, businessName, farmName, farmLocation, employeeCode);

        User user = registerUser(normalizedEmail, null, fullName, phoneNumber, role);
        user.proveEmail(OffsetDateTime.now(ZoneOffset.UTC));
        if (role == UserRole.INSPECTOR) {
            user.markVerification(VerificationStatus.PENDING);
        }
        createRoleProfile(role, businessName, farmName, farmLocation, employeeCode, user);
        return user;
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

    /**
     * A null {@code rawPassword} creates an account with no password. That is a different
     * account from one with a password, so the two constructors are chosen at the call site
     * rather than one constructor being handed a null it cannot distinguish from a
     * misconfigured encoder.
     */
    private User registerUser(String email, String rawPassword, String fullName, String phoneNumber, UserRole role) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessConflictException("Email is already registered");
        }
        User user;
        if (rawPassword == null) {
            user = new User(normalizedEmail, fullName.trim(), phoneNumber.trim(), role, UserStatus.ACTIVE);
        } else {
            user = new User(
                    normalizedEmail,
                    passwordEncoder.encode(rawPassword),
                    fullName.trim(),
                    phoneNumber.trim(),
                    role,
                    UserStatus.ACTIVE);
        }
        return userRepository.save(user);
    }

    public AuthResponse issueTokensFor(User user) {
        return issueTokens(user);
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<User> stored = userRepository.findByEmailIgnoreCase(email);
        if (stored.isEmpty()) {
            // No row, so there is no stored spelling to key on and the typed one is the only
            // canonical form that exists. It is still consumed, and this is load-bearing rather
            // than tidy: skipping the consume here would leave an unregistered address throttlable
            // never, so it would answer 401 forever while a registered one answers 429 once spent.
            // That single status difference enumerates the platform's customers from an endpoint
            // nobody thinks to rate-limit their enumeration against. Measured, not assumed — the
            // ordering without this consume fails
            // AccountLoginLimitIntegrationTest#theThrottleCannotBeUsedToEnumerateAccounts with
            // 401-where-429-was-expected.
            consumeAccountLoginAttempt(email);
            throw new AuthenticationFailedException("Invalid credentials");
        }
        User user = stored.get();
        // Keyed on the STORED address, not the one that was typed. Postgres resolves the account
        // with upper(email) = upper(:typed), and that collation folds U+0131 DOTLESS I to ASCII I,
        // so several distinct spellings of one address all reach this account. Hashing the typed
        // value gave each spelling its own budget — 2^n budgets for n dotless i's, from any source
        // address, which is the whole limit defeated. Deriving the key from the row is what makes
        // one account mean one bucket without replicating ICU folding in Java.
        consumeAccountLoginAttempt(user.getEmail());
        if (user.getPasswordHash() == null) {
            spendDummyPasswordComparison();
            throw new AuthenticationFailedException("Invalid credentials");
        }
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AuthenticationFailedException("Invalid credentials");
        }
        return issueTokens(user);
    }

    /**
     * An account with no password must cost what an account with one costs.
     *
     * <p>{@code BCryptPasswordEncoder.matches(raw, null)} returns false without running
     * bcrypt at all — a couple of milliseconds against the ~300ms a real comparison takes at
     * strength 12 — so a Google-only account was distinguishable from a password account by
     * response time alone, on an unauthenticated endpoint. Spending a real comparison against
     * a dummy hash before refusing closes it.
     *
     * <p>The dummy is encoded with the configured encoder rather than pasted in as a literal,
     * so it cannot drift to a cheaper strength than the real hashes and quietly reopen the gap
     * this closes. It is built on first use, not at startup, so the ~300ms it costs is paid
     * once rather than on every boot.
     */
    private void spendDummyPasswordComparison() {
        String dummy = dummyPasswordHash;
        if (dummy == null) {
            dummy = passwordEncoder.encode("kilivana-dummy-" + UUID.randomUUID());
            dummyPasswordHash = dummy;
        }
        passwordEncoder.matches("kilivana-dummy-presentation", dummy);
    }

    /**
     * Bounds login attempts against the account rather than against an address.
     *
     * <p>Every other credential limit in this service is keyed on a client address that
     * {@link com.kilivana.security.ClientIpResolver} had to derive from a proxy header, so all of
     * them are exactly as trustworthy as the edge in front of this service: forge the address and
     * they reset. This key is a SHA-256 of the normalised email, which no header can produce, so
     * the limit holds however wrong the address derivation is — which is the point of having it at
     * all. Hashing the email also keeps plaintext addresses out of Redis key names.
     *
     * <p>Consumed <em>before</em> authentication, and unconditionally: an attempt is bounded
     * whether it succeeds or not, which is the property a credential-guessing attack needs and the
     * one that a "only count failures" design cannot offer. It is also why the response cannot
     * differ between a registered and an unregistered address — both throw the same
     * {@code AuthenticationFailedException} with the same message, and both have already spent a
     * budget by the time that happens, so neither the status nor the body reveals existence.
     *
     * <p>The key is the hash of the <em>stored</em> address whenever an account row was found, and
     * of the normalised typed address only when it was not. See {@link #login} for why that
     * asymmetry is required and why the stored form is the one that has to be canonical.
     */
    private void consumeAccountLoginAttempt(String normalizedEmail) {
        consumeLoginBudgetFor(normalizedEmail);
    }

    /**
     * The per-account login budget, shared with the Google account-link path.
     *
     * <p>Account link is authenticated and must spend the same budget, for the same reason:
     * it takes a password, and a password-guessing oracle is a password-guessing oracle
     * whichever endpoint it is reachable through.
     */
    void consumeLoginBudgetFor(String normalizedEmail) {
        if (!rateLimitProperties.enabled()) {
            // Honour the same switch as the per-address limits. An operator who sets
            // kilivana.security.rate-limit.enabled=false expects every limiter in the service to
            // stop, and this one reads the RateLimiter bean unconditionally — so without this
            // gate a deliberate "turn limiting off" silently left a per-account lockout running,
            // which is the more surprising of the two failures in either direction.
            return;
        }
        String key = ACCOUNT_LOGIN_KEY_PREFIX + tokenService.hash(normalizedEmail);
        RateLimitResult result = rateLimiter.tryConsume(
                key,
                rateLimitProperties.loginAttemptsPerAccountPerWindow(),
                Duration.ofMinutes(rateLimitProperties.loginAccountWindowMinutes()));
        if (result.status() == RateLimitResult.Status.LIMITED) {
            throw new AccountLoginThrottledException(result.retryAfterSeconds());
        }
        if (result.status() == RateLimitResult.Status.UNAVAILABLE) {
            // Failing open here is deliberate and does not weaken the normal path. RateLimitFilter
            // already answers 503 on every one of the four credential endpoints when the limiter
            // cannot answer, so a request only reaches this method at all when limiting is
            // disabled outright or the path carries no rule. Adding a second availability gate here
            // would convert "limiter down" into "no logins" for a path the filter had already
            // decided is unlimited, which is a wider outage than the one being guarded against.
            log.warn("Per-account login limiter is unavailable; falling open for this attempt");
        }
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

    private void validateRoleProfileData(
            UserRole role,
            String businessName,
            String farmName,
            String farmLocation,
            String employeeCode) {
        if (role == UserRole.INSPECTOR
                && (isBlank(employeeCode) || employeeCode.length() > 100)) {
            throw new BusinessConflictException("Inspector accounts require a nonblank employeeCode of at most 100 characters");
        }
        if (role == UserRole.SUPPLIER && isBlank(businessName)) {
            throw new BusinessConflictException("Supplier accounts require businessName");
        }
        if (role == UserRole.FARMER && (isBlank(farmName) || isBlank(farmLocation))) {
            throw new BusinessConflictException("Farmer accounts require farmName and farmLocation");
        }
        if (role == UserRole.INSPECTOR && isBlank(employeeCode)) {
            throw new BusinessConflictException("Inspector accounts require employeeCode");
        }
    }

    private void createRoleProfile(
            UserRole role,
            String businessName,
            String farmName,
            String farmLocation,
            String employeeCode,
            User user) {
        switch (role) {
            case BUYER -> entityManager.persist(new BuyerProfile(user));
            case SUPPLIER -> entityManager.persist(new SupplierProfile(user, businessName.trim()));
            case FARMER -> entityManager.persist(new FarmerProfile(
                    user,
                    farmName.trim(),
                    farmLocation.trim()));
            case INSPECTOR -> entityManager.persist(new InspectorProfile(user, employeeCode.trim()));
            case DRIVER, ADMIN -> throw new BusinessConflictException("Role is not available for public registration");
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
                user.getVerificationStatus(),
                accessToken,
                refreshValue,
                refreshExpiresAt);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
