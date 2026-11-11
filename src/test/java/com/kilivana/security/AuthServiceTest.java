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
import com.kilivana.common.exception.InvalidAccountDataException;
import com.kilivana.security.config.RateLimitProperties;
import com.kilivana.users.service.AccountIdentity;
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

    /**
     * The wiring assertion for {@link com.kilivana.users.service.AccountLoginThrottleKeyNormalizer}.
     *
     * <p>Deliberately on the <em>unresolved</em> branch, because that is the only one where the
     * typed spelling reaches the key: when a row is found the key comes from the stored address,
     * which every spelling already agrees on. Here there is no stored address, so the typed one is
     * the only canonical form that exists — and it is hashed without being normalised, an attacker
     * probing a target's real address gets a fresh budget for every dotless-i spelling Postgres
     * would have folded together. Asserting on the resolved branch instead would pass with the
     * normalizer deleted, because that branch hashes a constant.
     */
    @Test
    @DisplayName("an unknown address spends one budget across every spelling the collation folds")
    void anUnresolvedAddressSpendsOneBudgetAcrossCollatedSpellings() {
        when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

// Dotless-i, long-s, and case spellings of one address. Under en_US.utf8 all four
        // compare equal to "billing@assist.com", which is precisely why they must share a budget.
        // The base address carries both an i and an s, because a fixture containing only one of
        // them would leave the other fold unexercised.
        for (String typed : List.of(
                "billing@assist.com",
                "b\u0131ll\u0131ng@assist.com",
                "billing@assi\u017Ft.com",
                "  BILLING@ASSIST.COM  ")) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(typed, "guess")))
                    .isInstanceOf(AuthenticationFailedException.class);
        }

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(4)).tryConsume(key.capture(), eq(10), eq(Duration.ofMinutes(15)));
        assertThat(key.getAllValues()).containsOnly("login-account:" + sha256Hex("billing@assist.com"));
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

    /**
     * The chosen username and region must already be on the entity when {@code save} is called.
     *
     * <p>Asserting on the instance {@code save} returned is what made the earlier version of this
     * test vacuous: {@code save} is stubbed to hand back its own argument, so the captured entity
     * is the same mutable object {@code registerUser} goes on to mutate afterwards. Any assertion
     * made afterwards therefore observes the state at the <em>end</em> of the method, and cannot
     * distinguish "set before save" from "set after save" — including the property this test exists
     * to protect. Worse, the assertion it did make could not fail even with the production code
     * deleted: {@link User}'s constructor always populates {@code username} with a generated
     * regex-valid value, so {@code isNotNull().matches(...)} holds for the wrong reason.
     *
     * <p>The fix is a deep copy taken <em>inside</em> the stub — inside {@code save}, at the moment
     * the INSERT would be issued — and the assertions then compare that snapshot against the
     * username the request asked for rather than against a regex. Proving this test is no longer
     * vacuous means reverting {@code registerUser} to assign after save and watching it go red;
     * that was done and the failure is the assertion "expected 'u_...' but was 'test_abc...'".
     */
/**
 * A suspended account must be indistinguishable from a wrong password on the same endpoint.
 *
 * <p>While the status check sat after {@code authenticate}, this held by accident: the provider's
 * own failure path ran a bcrypt and the ordering never mattered. It stops holding the moment the
 * check is moved anywhere cheaper — which is a refactor a later reader would consider an
 * improvement, since authenticating before deciding is the more conventional shape. So the property
 * is pinned here rather than left implied: same exception type, same message, and above all the
 * same number of {@code matches} calls, which is the thing that actually moves when the check is
 * reordered.
 *
 * <p>{@code matches} is counted rather than the exception merely compared, because the exception was
 * already identical before the reorder and so cannot detect it. The count is what distinguishes
 * "decided up front and paid for the decision" from "decided up front and went home early".
 */
@Test
@DisplayName("a suspended account costs the same as a wrong password and says the same thing")
void aSuspendedAccountIsIndistinguishableFromAWrongPassword() {
    when(rateLimiter.tryConsume(any(), anyInt(), any())).thenReturn(RateLimitResult.allowed());
    when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
    when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

    User suspended = new User(
            "suspended@example.com", "hash", "Suspended", "+254712345678", UserRole.BUYER, UserStatus.SUSPENDED);
    ReflectionTestUtils.setField(suspended, "id", UUID.randomUUID());
    when(userRepository.findByEmailIgnoreCase("suspended@example.com")).thenReturn(Optional.of(suspended));

    Throwable refused = catchThrowable(() -> authService.login(
            new LoginRequest("suspended@example.com", "secretPass1")));

    assertThat(refused)
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessage("Invalid credentials");
    // The status check runs before authenticate, so the budget is spent and a real comparison is
    // paid even though no credential was ever verified. Without the reorder this is zero matches
    // from AuthService — authenticate is a mock here, so it contributes none — and a suspended
    // account answers where a wrong password takes a full-strength bcrypt.
    verify(passwordEncoder, times(1)).matches(anyString(), eq("dummy-hash"));
    // And it is a refusal, not a deferred authentication: no credential checking at all, so the
    // status cannot become a timing oracle in either direction.
    verify(authenticationManager, never()).authenticate(any());
    verify(rateLimiter).tryConsume(any(), eq(10), eq(Duration.ofMinutes(15)));
}

@Test
@DisplayName("username and region are on the entity when save is called, not after it returns")
    void identityIsSetBeforeTheRowIsPersisted() {
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        // Captured inside the answer, so this is the entity as it was at INSERT time and nothing
        // assigned afterwards can reach it.
        String[] atInsert = new String[2];
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User entity = invocation.getArgument(0);
            atInsert[0] = entity.getUsername();
            atInsert[1] = entity.getRegion();
            return entity;
        });
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");

        SignupRequest request = request(UserRole.BUYER);
        authService.signup(request);

        // save() on a new entity with an assigned UUID issues the INSERT immediately, so anything
        // assigned to the returned instance is an INSERT-then-UPDATE pair. V11 constrains
        // username, and a collision on the chosen name would then surface as DATA_CONFLICT from
        // the UPDATE instead of the BUSINESS_CONFLICT the pre-check promised.
        verify(userRepository).save(any(User.class));
        assertThat(atInsert[0]).isEqualTo(AccountIdentity.username(request.username()));
        assertThat(atInsert[1]).isEqualTo("Nairobi");
    }

    @Test
    @DisplayName("an account created from a proven address stores the username it was given")
    void aVerifiedEmailRegistrationStoresItsChosenUsername() {
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = authService.registerFromVerifiedEmail(
                "proven@example.com", "chosen_name", "Proven User", "+254712345678", "Nakuru",
                UserRole.BUYER, null, null, null, null);

        // Chosen, and canonicalised by the same AccountIdentity.username a signup uses. Asserted by
        // value: the assertion this replaces was isNotNull().matches(regex), which the old generated
        // u_<base36> name satisfied and so would have passed had the caller's choice been discarded.
        assertThat(user.getUsername()).isEqualTo("chosen_name");
        assertThat(user.getRegion()).isEqualTo("Nakuru");
        assertThat(user.getPhoneNumber()).isEqualTo("+254712345678");
        // Still no password on this path: a proven address is not a password.
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("a malformed username on the verified-email path is refused before the row is written")
    void aVerifiedEmailRegistrationRefusesAMalformedUsername() {
        // Raised by AccountIdentity.username, so the message is the one signup gives for the same
        // input rather than a Google-flavoured variant a caller could learn from.
        assertThatThrownBy(() -> authService.registerFromVerifiedEmail(
                "bad@example.com", "1nvalid", "Bad", "+254712345678", "Nakuru",
                UserRole.BUYER, null, null, null, null))
                .isInstanceOf(InvalidAccountDataException.class)
                .hasMessageContaining("username must match");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("the admin bootstrap derives its username from the address, because V11 constrains every row")
    void theAdminBootstrapDerivesItsUsername() {
        when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");

        authService.registerAndAuthenticate(
                "root@kilivana.example", "secretPass1", "System Root", "+254700000000", UserRole.ADMIN);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        // Derived from the address's local part, so an operator can predict the handle their
        // bootstrap created. Asserted by value, not by shape: the shape assertion here passed
        // against the old random generator and pinned nothing.
        assertThat(saved.getValue().getUsername()).isEqualTo("root");
        // Region is nullable and stays null: an administrator has no farm and no county, and
        // inventing one would be a value nobody could later correct from the row.
        assertThat(saved.getValue().getRegion()).isNull();
    }

    @Test
    @DisplayName("an operator address that is not a legal handle still bootstraps")
    void anOperatorAddressThatIsNotALegalHandleStillBootstraps() {
        // This endpoint creates exactly one account in the lifetime of a deployment, guarded by a
        // one-time key. A rejection here is an outage with no second attempt, so a perfectly valid
        // address whose local part contains characters the username pattern forbids must still
        // create the account. Asserted by value: the previous fix renamed the test's own address to
        // a legal one and so proved nothing about the behaviour it appeared to cover.
        when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");

        authService.registerAndAuthenticate(
                "admin-cm@kilivana.example", "secretPass1", "System Root", "+254700000000", UserRole.ADMIN);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin_cm");
    }

    @Test
    @DisplayName("a full-length local part that needs a prefix still fits the username column")
    void aFullLengthLocalPartThatNeedsAPrefixStillFits() {
        // A 30-character local part beginning with a character the username pattern forbids is
        // sanitised to 30 characters; prefixing the leading 'u' would then make 31 — one past
        // V11's varchar(30) and its check constraint. Without the clamp, AccountIdentity.username
        // rejects the only admin bootstrap a deployment ever gets.
        when(userRepository.existsByRole(UserRole.ADMIN)).thenReturn(false);
        when(passwordEncoder.encode("secretPass1")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any())).thenReturn("access-token");

        String localPart = "-" + "a".repeat(29);
        authService.registerAndAuthenticate(
                localPart + "@kilivana.example", "secretPass1", "System Root", "+254700000000", UserRole.ADMIN);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).hasSize(30).startsWith("u_");
    }

    @Test
    @DisplayName("a taken username is surfaced to the caller, never re-rolled")
    void aTakenUsernameIsSurfacedNotReRolled() {
        // The collision is reported to the caller rather than retried with a different name. A
        // re-roll is the property that would actually leak: an attacker repeating one name until it
        // is accepted turns a single 409 into a working "is this username taken" oracle, and it
        // would exist to protect a collision rate of zero — the old u_<base36> generator drew from
        // 36^25 against 2^128, so the loop it required could never have fired.
        when(userRepository.existsByUsername(anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.registerFromVerifiedEmail(
                "taken@example.com", "someone_elses", "Doomed", "+254712345678", "Nairobi",
                UserRole.BUYER, null, null, null, null))
                .isInstanceOf(BusinessConflictException.class)
                .hasMessage("Username is already registered");

        // Checked once. If it were checked twice, the second attempt would be a second chance for an
        // attacker to tell "taken" from "free" by whether the request took longer.
        verify(userRepository, times(1)).existsByUsername("someone_elses");
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
