package com.kilivana.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.LoginRequest;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.common.exception.AccountLoginThrottledException;
import com.kilivana.auth.service.AuthService;
import com.kilivana.common.exception.AuthenticationFailedException;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.security.config.RateLimitProperties;
import com.kilivana.users.domain.RefreshToken;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserRole;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.RefreshTokenRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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

    @Mock
    private RateLimiter rateLimiter;

    private AuthService authService;
    private TokenService tokenService;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
        jwtProperties = new JwtProperties("issuer", "audience", 15, 14, "a-very-long-high-entropy-secret-value-123456");
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                authenticationManager,
                jwtService,
                jwtProperties,
                tokenService,
                entityManager,
                rateLimiter,
                new RateLimitProperties(true, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100));
    }

    @Test
    void publicSignupRejectsPrivilegedRolesBeforeCreatingAnAccount() {
        for (UserRole role : new UserRole[]{UserRole.DRIVER, UserRole.ADMIN}) {
            SignupRequest request = request(role);

            assertThatThrownBy(() -> authService.signup(request))
                    .isInstanceOf(BusinessConflictException.class)
                    .hasMessageContaining("limited");
        }

        verifyNoInteractions(userRepository, passwordEncoder, entityManager);
    }

    @Test
    void publicSignupRequiresEmployeeCodeForInspector() {
        SignupRequest request = request(UserRole.INSPECTOR);

        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("employeeCode");

        verifyNoInteractions(userRepository, passwordEncoder, entityManager);
    }

    @Test
    void publicSignupStoresOnlyAHashedRefreshToken() {
        UUID userId = UUID.randomUUID();
        User user = new User("buyer@example.com", "encoded", "Buyer", "+254700000000", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");

        AuthResponse response = authService.signup(request(UserRole.BUYER));

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(tokenCaptor.capture());
        RefreshToken stored = tokenCaptor.getValue();
        assertThat(stored.getTokenValue()).isNotEqualTo(response.refreshToken());
        assertThat(stored.getTokenValue()).hasSize(64);
        assertThat(stored.getFamilyId()).isNotNull();
    }

    @Test
    void adminProvisioningIsRefusedAfterAnAdminExists() {
        when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(true);

        assertThatThrownBy(() -> authService.registerAndAuthenticate(
                "admin@example.com",
                "secretPass1",
                "Admin",
                "+254700000001",
                UserRole.ADMIN))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessageContaining("already");

        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void inspectorSignupCreatesPendingProfileAndTokens() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("access-token");
        AuthResponse response = authService.signup(new SignupRequest(
                "inspector@example.com",
                "secretPass1",
                "Inspector",
                "+254700000001",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.INSPECTOR,
                null,
                null,
                null,
                "  any code  ",
                null,
                null,
                null,
                null));
        ArgumentCaptor<com.kilivana.users.domain.InspectorProfile> profile =
                ArgumentCaptor.forClass(com.kilivana.users.domain.InspectorProfile.class);
        verify(entityManager).persist(profile.capture());
        assertThat(profile.getValue().getEmployeeCode()).isEqualTo("any code");
        assertThat(profile.getValue().getUser().getVerificationStatus())
                .isEqualTo(com.kilivana.users.domain.VerificationStatus.PENDING);
        assertThat(profile.getValue().getUser().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.verificationStatus()).isEqualTo(com.kilivana.users.domain.VerificationStatus.PENDING);
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isNotBlank();
    }

    @Test
    void invalidInspectorCodesAreRejectedBeforePersistence() {
        for (String code : new String[]{null, "", "   ", "x".repeat(101)}) {
            assertThatThrownBy(() -> authService.signup(new SignupRequest(
                "inspector@example.com",
                "secretPass1",
                "Inspector",
                "+254700000001",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                UserRole.INSPECTOR,
                null,
                null,
                null,
                code,
                null,
                null,
                null,
                null)))
                    .isInstanceOf(BusinessConflictException.class);
        }
        verifyNoInteractions(userRepository, passwordEncoder, entityManager, refreshTokenRepository);
    }

    @Test
    @DisplayName("the per-account limit refuses the attempt without ever reaching the credentials")
    void exceedingThePerAccountLimitNeverAuthenticates() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.limited(42));

        assertThatThrownBy(() -> authService.login(new LoginRequest("victim@example.com", "guess")))
                .isInstanceOf(AccountLoginThrottledException.class)
                .hasMessage("Too many login attempts for this account. Please retry later.");

        // The whole point: a bounded attempt count has to bound the expensive work, not merely
        // the response. If authenticate ran first, the limit would answer 429 while the password
        // was still being checked, and credential guessing would be exactly as fast as before.
        //
        // The account lookup now happens BEFORE the budget, because the key has to come from the
        // stored address. That is a cheap indexed read and no credential work, so the property
        // this test exists for is unaffected: authenticate still never runs.
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("the retry-after the caller is given is the one the limiter reported")
    void theRetryAfterIsCarriedFromTheLimiter() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.limited(300));

        assertThatThrownBy(() -> authService.login(new LoginRequest("victim@example.com", "guess")))
                .isInstanceOf(AccountLoginThrottledException.class)
                .extracting(exception -> ((AccountLoginThrottledException) exception).retryAfterSeconds())
                .isEqualTo(300L);
    }

    @Test
    @DisplayName("the account key comes from the stored address, not the one that was typed")
    void theAccountKeyIsDerivedFromTheStoredAddress() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        // The account lookup resolves on a case-insensitive comparison, so a typed address that
        // differs from the stored one is exactly the normal case, not an exotic one.
        User user = new User("victim@example.com", "hash", "Victim", "+254700000000", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByEmailIgnoreCase("victim@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");

        authService.login(new LoginRequest("Victim@Example.com", "secretPass1"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(key.capture(), eq(10), eq(Duration.ofMinutes(15)));
        assertThat(key.getValue()).isEqualTo("login-account:" + sha256Hex("victim@example.com"));
        // Note what this pair of fixtures CANNOT prove, and why the next test exists. A pure case
        // difference is invisible here: "Victim@Example.com".trim().toLowerCase(ROOT) is already
        // "victim@example.com", so the typed form and the stored form are the same string and
        // hashing either one passes. Distinguishing "hashed the typed value" from "hashed the
        // stored value" needs a typed form that survives Java normalisation but not the database
        // comparison — a dotless i. That is what the next test does, and what the real-collation
        // integration test does against Postgres.
    }

    /**
     * The load-bearing unit assertion of Fix 2, in a form a Mockito test can express: the key is a
     * function of the stored address and nothing else. The integration counterpart in
     * {@code AccountLoginLimitIntegrationTest} proves the same thing against a real Postgres
     * collation, which is the only way to show the spellings really do collide.
     */
    @Test
    @DisplayName("every spelling that resolves to the same account spends the same budget")
    void everySpellingOfOneAccountSpendsOneBudget() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        User user = new User("victim@example.com", "hash", "Victim", "+254700000000", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");

        // Whitespace, case, and the dotless-i spellings the database collation folds to ASCII i.
        // Whatever the lookup accepts, the budget must not vary.
        for (String typed : List.of(
                "victim@example.com",
                "  VICTIM@Example.COM  ",
                "vıctım@example.com",
                "victım@example.com",
                "vıctim@example.com")) {
            authService.login(new LoginRequest(typed, "secretPass1"));
        }

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(5)).tryConsume(key.capture(), eq(10), eq(Duration.ofMinutes(15)));
        assertThat(key.getAllValues())
                .as("five spellings, one budget — otherwise the limit is worth 2^n budgets for n "
                        + "dotless i's")
                .containsOnly("login-account:" + sha256Hex("victim@example.com"));
    }

    /** An unknown address is still bounded, and still bounded under its own typed key. */
    @Test
    @DisplayName("an unknown address is still throttled, under the address that was typed")
    void anUnknownAddressIsStillBounded() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.limited(30));
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody-at-all@example.com", "guess")))
                .isInstanceOf(AccountLoginThrottledException.class);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(key.capture(), eq(10), eq(Duration.ofMinutes(15)));
        assertThat(key.getValue()).isEqualTo("login-account:" + sha256Hex("nobody-at-all@example.com"));
        // No credentials are ever checked on a throttled attempt, registered or not.
        verify(authenticationManager, never()).authenticate(any());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Test
    @DisplayName("within the per-account limit, login still succeeds")
    void loginWithinTheLimitSucceeds() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        User user = new User("buyer@example.com", "hash", "Buyer", "+254700000000", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByEmailIgnoreCase("buyer@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");

        AuthResponse response = authService.login(new LoginRequest("  Buyer@Example.com ", "secretPass1"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(authenticationManager).authenticate(any());
    }

    @Test
    @DisplayName("disabling rate limiting disables the per-account limit too")
    void theAccountLimitHonoursTheGlobalSwitch() {
        // The service reads the RateLimiter bean directly rather than going through
        // RateLimitFilter, so without an explicit check on the same property an operator who set
        // kilivana.security.rate-limit.enabled=false would have turned off the address limits and
        // silently left a per-account lockout running. Both halves of that switch have to agree.
        ReflectionTestUtils.setField(authService, "rateLimitProperties",
                new RateLimitProperties(false, "memory", 5, 5, 3, 3, 10, 5, 5, 5, 10, 15, 100));
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("victim@example.com", "guess")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(rateLimiter, never()).tryConsume(any(), anyInt(), any());
    }

    @Test
    @DisplayName("the account limit is keyed on a hash of the email, never on the address itself")
    void theAccountKeyIsHashedAndNormalised() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        // The login itself fails on the (absent) account; what matters is that the key was
        // consumed before it ever got there.
        assertThatThrownBy(() -> authService.login(new LoginRequest("Victim@Example.COM", "guess")))
                .isInstanceOf(AuthenticationFailedException.class);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).tryConsume(key.capture(), eq(10), eq(Duration.ofMinutes(15)));
        // Plaintext addresses in Redis key names would put every registered customer's email into
        // whatever reads those keys, and into every Redis slowlog and MONITOR capture. The hash
        // is also what makes the key unforgeable by a header, which is the limit's whole purpose.
        assertThat(key.getValue())
                .startsWith("login-account:")
                .doesNotContain("victim@example.com")
                .doesNotContain("Victim")
                .doesNotContain("@")
                .hasSize("login-account:".length() + 64);
    }

    @Test
    @DisplayName("one address cannot spend another address's account budget")
    void theAccountKeyDoesNotDependOnAnythingTheClientCanForge() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        // Two different source addresses, same account: the key is a function of the normalised
        // email alone, so both attempts land in the same bucket. A key that mixed in a header-
        // derived address would give an attacker one fresh budget per forged address.
        assertThatThrownBy(() -> authService.login(new LoginRequest("victim@example.com", "guess")))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest("victim@example.com", "guess")))
                .isInstanceOf(AuthenticationFailedException.class);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).tryConsume(key.capture(), eq(10), eq(Duration.ofMinutes(15)));
        assertThat(key.getAllValues()).containsOnly(key.getAllValues().get(0));
    }

    @Test
    @DisplayName("an unknown address and a known one are refused identically")
    void theRefusalCannotBeUsedToEnumerateAccounts() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.limited(60));

        // Both throws are captured and compared, not merely their types. A message that said
        // "no such account" for one and "too many attempts" for the other would answer the only
        // question an attacker has, and it would answer it on a 429, which is not a status anyone
        // thinks to rate-limit their enumeration against.
        Throwable unknown = catchThrowable(() -> authService.login(
                new LoginRequest("nobody-at-all@example.com", "guess")));
        Throwable known = catchThrowable(() -> authService.login(
                new LoginRequest("registered@example.com", "guess")));

        assertThat(unknown).isInstanceOf(AccountLoginThrottledException.class);
        assertThat(known).isInstanceOf(AccountLoginThrottledException.class);
        assertThat(known.getClass()).isEqualTo(unknown.getClass());
        assertThat(known.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(known.getMessage())
                .doesNotContain("nobody-at-all")
                .doesNotContain("registered")
                .doesNotContain("account does not exist");
        // The budget is consumed on BOTH paths — the registered account on its stored address, the
        // unregistered one on the address that was typed, because there is no row to read a
        // canonical form from. That is what stops the two from being distinguishable by status: if
        // the unregistered address were skipped here it could never be throttled, so it would
        // answer 401 forever while the registered one answers 429 once spent, and one status
        // difference enumerates the platform's customers.
        verify(rateLimiter, times(2)).tryConsume(anyString(), eq(10), eq(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("an unavailable limiter fails open here, because the filter already answered 503")
    void anUnavailableAccountLimiterFallsThrough() {
        // The filter answers 503 on all four credential endpoints whenever the limiter cannot
        // answer, so a request only reaches this method when limiting is disabled or the path
        // carries no rule. Failing open is the deliberate choice there; failing closed here would
        // widen a Redis blip into an outage on paths the filter had already exempted.
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.unavailable());
        User user = new User("buyer@example.com", "hash", "Buyer", "+254700000000", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByEmailIgnoreCase("buyer@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");

        AuthResponse response = authService.login(new LoginRequest("buyer@example.com", "secretPass1"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(authenticationManager).authenticate(any());
    }

    @Test
    @DisplayName("an identifier that is not a Kenyan phone is a credential failure, not a 400")
    void anUnparseablePhoneIsIndistinguishableFromAWrongPassword() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        // "ext 1" and a US number both fail AccountIdentity.phone. Letting its
        // InvalidAccountDataException escape would be answered 400 VALIDATION_ERROR — a body no
        // other credential failure produces — and it would arrive before any budget was consumed,
        // so a malformed guess was cheaper than a well-formed one.
        for (String identifier : new String[]{"0712345678 ext 1", "+12025550123", "call 0712345678", "12345"}) {
            Throwable thrown = catchThrowable(() -> authService.login(new LoginRequest(identifier, "guess")));

            assertThat(thrown).isInstanceOf(AuthenticationFailedException.class);
            assertThat(thrown.getMessage()).isEqualTo("Invalid credentials");
        }
        // Every one of them spent a budget and paid for a real bcrypt comparison, so none of them
        // is distinguishable from a guess that had the right shape and the wrong password.
        verify(rateLimiter, times(4)).tryConsume(anyString(), eq(10), eq(Duration.ofMinutes(15)));
        verify(passwordEncoder, times(4)).matches(anyString(), eq("dummy-hash"));
        verify(authenticationManager, never()).authenticate(any());
        // And no lookup happened at all: the identifier was never an account, so there was nothing
        // to look up. findByEmailIgnoreCase must not be reached either, since it contains no "@".
        verify(userRepository, never()).findByPhoneNumber(anyString());
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    @DisplayName("an unknown address pays a password comparison too, or timing enumerates it")
    void anUnknownAccountCostsWhatAKnownOneCosts() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        // This path does one indexed read and nothing else, while a wrong password costs a
        // full-strength bcrypt. That gap is the difference between "no such account" and "wrong
        // password" measured in wall-clock time on an unauthenticated endpoint.
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "guess")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(passwordEncoder).matches(anyString(), eq("dummy-hash"));
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("a phone login spends the budget of the account it found, not of the number typed")
    void aPhoneLoginKeysTheBudgetOnTheStoredAddress() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        User user = new User("victim@example.com", "hash", "Victim", "+254712345678", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByPhoneNumber("+254712345678")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");

        // Five spellings of one Kenyan number. If the key were derived from the typed digits each
        // would get its own budget, and the limit would be worth n budgets for n spellings — which
        // is exactly the defect the email path already fixed by keying on the stored row.
        for (String typed : new String[]{
                "0712345678", " 0712 345 678 ", "+254712345678", "254712345678", "0712345678"}) {
            authService.login(new LoginRequest(typed, "secretPass1"));
        }

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(5)).tryConsume(key.capture(), eq(10), eq(Duration.ofMinutes(15)));
        assertThat(key.getAllValues())
                .containsOnly("login-account:" + sha256Hex("victim@example.com"));
    }

    @Test
    @DisplayName("an account with no password spends the dummy comparison before it is refused")
    void aGoogleOnlyAccountSpendsARealComparison() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        User user = new User("google@example.com", "Name", "+254712345678", UserRole.BUYER, UserStatus.ACTIVE);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByEmailIgnoreCase("google@example.com")).thenReturn(Optional.of(user));

        // matches(raw, null) returns false without running bcrypt at all, which returned in
        // milliseconds against the ~300ms a real comparison takes — a Google account separable from
        // a password account by response time alone.
        assertThatThrownBy(() -> authService.login(new LoginRequest("google@example.com", "guess")))
                .isInstanceOf(AuthenticationFailedException.class);

        verify(passwordEncoder).matches(anyString(), eq("dummy-hash"));
        verify(authenticationManager, never()).authenticate(any());
    }

    @Test
    @DisplayName("username and region are on the entity when save is called, not after it returns")
    void identityIsSetBeforeTheRowIsPersisted() {
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");

        authService.signup(request(UserRole.BUYER));

        // save() on a new entity with an assigned UUID issues the INSERT immediately, so anything
        // assigned to the returned instance is an INSERT-then-UPDATE pair. V11 constrains
        // username, and a collision on the chosen name would then surface as DATA_CONFLICT from
        // the UPDATE instead of the BUSINESS_CONFLICT the pre-check promised.
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User atInsert = saved.getValue();
        assertThat(atInsert.getUsername()).isNotNull().matches("[a-z][a-z0-9_]{2,29}");
        assertThat(atInsert.getRegion()).isEqualTo("Nairobi");
    }

    @Test
    @DisplayName("an account created from a proven address gets a generated username")
    void aVerifiedEmailRegistrationGeneratesItsUsername() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = authService.registerFromVerifiedEmail(
                "proven@example.com", "Proven User", "+254712345678", "Nakuru",
                UserRole.BUYER, null, null, null, null);

        // No username is collected on this path, so one has to exist by the time the row is
        // written or V11's NOT NULL refuses the INSERT. The shape matters as much as the presence:
        // the column is 30 characters and chk_users_username demands ^[a-z][a-z0-9_]{2,29}$.
        assertThat(user.getUsername())
                .isNotNull()
                .hasSizeLessThanOrEqualTo(30)
                .matches("[a-z][a-z0-9_]{2,29}");
        assertThat(user.getRegion()).isEqualTo("Nakuru");
        assertThat(user.getPhoneNumber()).isEqualTo("+254712345678");
        // And it must not be a value the caller could have been shown was free.
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("the admin bootstrap creates a username too, because V11 constrains every row")
    void theAdminBootstrapGeneratesItsUsername() {
        when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");

        authService.registerAndAuthenticate(
                "root@kilivana.example", "secretPass1", "System Root", "+254700000000", UserRole.ADMIN);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isNotNull().matches("[a-z][a-z0-9_]{2,29}");
        // Region is nullable and stays null: an administrator has no farm and no county, and
        // inventing one would be a value nobody could later correct from the row.
        assertThat(saved.getValue().getRegion()).isNull();
    }

    @Test
    @DisplayName("a generated username is re-rolled on collision, not surfaced to the caller")
    void aGeneratedUsernameCollisionIsRetried() {
        // existsByUsername is what reports a collision, so the FIRST attempt collides. With the
        // retry loop absent this throws and the caller sees a 409 for a name it never chose and
        // cannot change; with it, the second attempt is made with a different name.
        when(userRepository.existsByUsername(anyString())).thenReturn(true, false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = authService.registerFromVerifiedEmail(
                "retry@example.com", "Retry User", "+254712345678", "Nairobi",
                UserRole.BUYER, null, null, null, null);

        assertThat(user.getUsername()).isNotNull().matches("[a-z][a-z0-9_]{2,29}");
        ArgumentCaptor<String> attempted = ArgumentCaptor.forClass(String.class);
        verify(userRepository, times(2)).existsByUsername(attempted.capture());
        // Re-rolled, not re-checked: two identical attempts would collide identically forever.
        assertThat(attempted.getAllValues().get(0)).isNotEqualTo(attempted.getAllValues().get(1));
    }

    @Test
    @DisplayName("a username that collides every time fails loudly instead of looping forever")
    void anExhaustedUsernameRetryThrows() {
        when(userRepository.existsByUsername(anyString())).thenReturn(true);

        // 2^-128 is not an event, so this is the shape the loop is not written to survive. What it
        // must do is stop and say so — a loop that gave up silently, or that retried without a
        // bound, would be a worse defect than the collision it is defending against.
        assertThatThrownBy(() -> authService.registerFromVerifiedEmail(
                "doomed@example.com", "Doomed User", "+254712345678", "Nairobi",
                UserRole.BUYER, null, null, null, null))
                .isInstanceOf(BusinessConflictException.class);

        verify(userRepository, times(3)).existsByUsername(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    private SignupRequest request(UserRole role) {
        return new SignupRequest(
                "buyer@example.com",
                "secretPass1",
                "Buyer",
                "+254700000000",
                "test_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 25),
                "Nairobi",
                role,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
