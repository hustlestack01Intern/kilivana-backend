package com.kilivana.auth.service;

import com.kilivana.audit.service.AuditLogService;
import com.kilivana.auth.api.AccountLinkRequest;
import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.EmailVerificationRequest;
import com.kilivana.auth.api.GoogleAuthRequest;
import com.kilivana.auth.api.GoogleHandshakeResponse;
import com.kilivana.auth.api.GoogleOnboardingRequest;
import com.kilivana.auth.api.OnboardingTicketResponse;
import com.kilivana.auth.google.EmailVerificationService;
import com.kilivana.auth.google.EmailVerificationTokenService;
import com.kilivana.auth.google.GoogleIdentityClaims;
import com.kilivana.auth.google.GoogleProperties;
import com.kilivana.auth.google.GoogleTokenVerifier;
import com.kilivana.auth.google.OnboardingTicketStore;
import com.kilivana.common.exception.AuthenticationFailedException;
import com.kilivana.common.exception.BusinessConflictException;
import com.kilivana.common.exception.InvalidTokenException;
import com.kilivana.common.exception.ResourceNotFoundException;
import com.kilivana.security.AuthenticatedUser;
import com.kilivana.security.CurrentUser;
import com.kilivana.users.domain.User;
import com.kilivana.users.domain.UserIdentity;
import com.kilivana.users.domain.UserStatus;
import com.kilivana.users.repository.UserIdentityRepository;
import com.kilivana.users.repository.UserRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the Google handshake: verify, resolve, and either authenticate, onboard, or
 * prove the mailbox first.
 *
 * <p><strong>Resolution is by {@code sub} and nothing else.</strong> Every lookup on this path
 * is {@code findByProviderAndSubject}. There is no query against {@code users.email} anywhere
 * in the handshake, because that query is what turns an unauthenticated endpoint into an
 * account-existence oracle, and its absence is a property no test of response codes can be
 * trusted to hold onto its own.
 *
 * <p>Google verifies identity; this service owns the account. The role, the inspector
 * approval state, the access token and the refresh token are all minted here and are all
 * unaffected by what the token's claims say beyond identity.
 */
@Service
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
public class GoogleAuthService {

    private static final String PROVIDER = "GOOGLE";

    /**
     * One message for every way onboarding can be refused because the address is not available
     * to this account.
     *
     * <p>Three cases collapse onto it: the address is already registered, the address is
     * registered by a row whose email was never proven, and the Google identity is already
     * linked elsewhere. They differ in their causes and not one bit in their answer, so the
     * response cannot be used to learn which of them applies.
     *
     * <p>The pre-staged-row case is the one with teeth. If an attacker can get a row created for
     * an address with no proven email, then attaching a verified identity to it would mean the
     * victim's own {@code forgot-password} arms a password on the attacker's row — and the
     * victim is handed the attacker's cart, orders and payment methods. Refusing every account
     * whose email is unproven closes that, at the cost of never auto-repairing a row whose
     * email predates the column. That trade is deliberate and is not behind a flag.
     */
    private static final String ONBOARDING_CONFLICT =
            "This account cannot be completed with Google sign-in";

    private final GoogleTokenVerifier tokenVerifier;
    private final OnboardingTicketStore ticketStore;
    private final EmailVerificationTokenService verificationTokens;
    private final EmailVerificationService verificationService;
    private final UserIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditLogService auditLogService;
    private final CurrentUser currentUser;
    private final AuthenticationManager authenticationManager;
    private final GoogleProperties properties;
    private final Clock clock;

    public GoogleAuthService(
            GoogleTokenVerifier tokenVerifier,
            OnboardingTicketStore ticketStore,
            EmailVerificationTokenService verificationTokens,
            EmailVerificationService verificationService,
            UserIdentityRepository identityRepository,
            UserRepository userRepository,
            AuthService authService,
            AuditLogService auditLogService,
            CurrentUser currentUser,
            AuthenticationManager authenticationManager,
            GoogleProperties properties) {
        this.tokenVerifier = tokenVerifier;
        this.ticketStore = ticketStore;
        this.verificationTokens = verificationTokens;
        this.verificationService = verificationService;
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditLogService = auditLogService;
        this.currentUser = currentUser;
        this.authenticationManager = authenticationManager;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    /**
     * The unauthenticated handshake. Never varies on whether an account exists.
     */
    public GoogleHandshakeResponse handshake(GoogleAuthRequest request) {
        GoogleIdentityClaims claims = tokenVerifier.verify(request.idToken());
        String normalizedEmail = claims.normalizedEmail();

        // Resolved before the email is looked at, and on the subject alone. A linked account is
        // authenticated by the one thing that identifies a person here; whether this particular
        // token happens to carry an email is not part of that, and making it a precondition
        // would let anyone who could suppress the email claim lock a Google account out.
        Optional<UserIdentity> existing = identityRepository
                .findByProviderAndSubject(PROVIDER, claims.subject());
        if (existing.isPresent()) {
            return authenticateLinked(claims, existing.get());
        }

        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            // The email scope was not granted, so there is no address to resolve and no address
            // to mail. The client must re-authorise. No ticket is minted, because a ticket is a
            // route to creating an account and nothing about this request has earned one.
            return GoogleHandshakeResponse.emailVerificationRequired(null, null, null);
        }

        if (claims.authoritative()) {
            OnboardingTicketStore.IssuedTicket issued = ticketStore.mint(normalizedEmail, claims);
            return GoogleHandshakeResponse.onboardingRequired(
                    issued.ticket(), claims.name(), issued.expiresAt());
        }

        // Google has not vouched for this address, so it is proven the only way that works
        // without trusting Google or the client: the mailbox itself.
        //
        // A ticket is still minted, and it is minted unproven. It carries the identity and the
        // address so the verification email can be tied to the flow that asked for it, and it
        // is NOT a route to an account: `onboard` refuses anything whose mailbox is unproven,
        // so this ticket only buys the right to be exchanged for a proven one.
        //
        // No users row and no user_identities row is created — the account does not exist yet,
        // and that is the entire point.
        OnboardingTicketStore.IssuedTicket issued = ticketStore.mint(normalizedEmail, claims);
        String simulated = verificationService.requestVerification(normalizedEmail, claims.subject());
        return GoogleHandshakeResponse.emailVerificationRequired(
                issued.ticket(), issued.expiresAt(), simulated);
    }

    private GoogleHandshakeResponse authenticateLinked(GoogleIdentityClaims claims, UserIdentity identity) {
        if (!identity.isActive()) {
            throw new AuthenticationFailedException("Google sign-in could not be verified");
        }
        User user = identity.getUser();
        if (user.getStatus() != UserStatus.ACTIVE) {
            // One fixed 401. The body never says whether the account was suspended or the link
            // revoked, because that difference is a fact about an account to an unauthenticated
            // caller who already proved control of a Google identity.
            throw new AuthenticationFailedException("Google sign-in could not be verified");
        }
        identity.markVerified(now());
        identityRepository.save(identity);
        return GoogleHandshakeResponse.authenticated(authService.issueTokensFor(user));
    }

    /**
     * Exchanges mailbox proof for a proven onboarding ticket.
     *
     * <p>Requires both halves: the token from the mailbox, and the unproven ticket from the
     * browser that started the flow. Neither is sufficient alone. The token alone would let
     * anyone who intercepted one email bind an identity; the ticket alone would be the
     * bypass. Together they must name the same address, which is why the ticket is consumed
     * and inspected before the token is spent — a mismatched pair spends nothing.
     *
     * <p>Not transactional, deliberately. The token spend commits inside
     * {@link EmailVerificationTokenService#redeem}, and the replacement ticket is a Redis
     * write afterwards. Wrapping both would mean holding a database transaction across Redis,
     * which buys no atomicity the flow needs: a ticket that fails to mint is a failed request,
     * and the honest response to a failed request is to start the handshake again.
     */
    public OnboardingTicketResponse verifyEmail(EmailVerificationRequest request) {
        OnboardingTicketStore.Ticket pending = ticketStore.consume(request.onboardingTicket());
        if (pending.emailProven()) {
            // A proven ticket has no use here, and spending it would destroy a route the client
            // may still legitimately be holding.
            throw new InvalidTokenException("Invalid or expired onboarding ticket");
        }
        String email = verificationTokens.redeem(request.token());
        if (!email.equalsIgnoreCase(pending.email())) {
            // The mail went to one address and the flow is for another. Nothing is created.
            throw new InvalidTokenException("Invalid or expired verification token");
        }
        OnboardingTicketStore.IssuedTicket issued = ticketStore.mint(
                email,
                new GoogleIdentityClaims(
                        pending.subject(), email, false, pending.hostedDomain(), null),
                true);
        return new OnboardingTicketResponse(issued.ticket(), issued.expiresAt());
    }

    /**
     * Creates the account for a ticket whose mailbox has been proven.
     *
     * <p>The ticket is redeemed before anything else, so it is single use whatever happens next.
     */
    @Transactional
    public AuthResponse onboard(GoogleOnboardingRequest request) {
        OnboardingTicketStore.Ticket ticket = ticketStore.consume(request.onboardingTicket());
        if (!ticket.emailProven()) {
            // The mailbox was never proven, so this ticket proves nothing about who owns the
            // address. The same answer as a forged ticket: without it, a ticket is a credential
            // for creating an account with an address the caller has not shown they control.
            throw new InvalidTokenException("Invalid or expired onboarding ticket");
        }

        // A ticket proves an address, never an account. Whether an account already exists for
        // it is a different question and one that must be answered identically either way.
        if (userRepository.existsByEmailIgnoreCase(ticket.email())) {
            throw new BusinessConflictException(ONBOARDING_CONFLICT);
        }

        User user = authService.registerFromVerifiedEmail(
                ticket.email(),
                request.fullName(),
                request.phoneNumber(),
                request.role(),
                request.businessName(),
                request.farmName(),
                request.farmLocation(),
                request.employeeCode());

        UserIdentity identity = new UserIdentity(
                user,
                PROVIDER,
                ticket.subject(),
                ticket.email(),
                ticket.providerEmailVerified(),
                ticket.hostedDomain());
        // The domain allowlist is a signal for an administrator, never an approval: `hd` proves
        // the mailbox sits on an organisation's domain, not that the person holds a role in it.
        if (user.getRole() == com.kilivana.users.domain.UserRole.INSPECTOR
                && properties.isInspectorDomainApproved(ticket.hostedDomain())) {
            identity.approveInspectorDomain();
        }
        identity.markVerified(now());
        identityRepository.save(identity);

        auditLogService.record(
                "GOOGLE_IDENTITY_LINKED",
                "USER",
                user.getId(),
                "provider=" + PROVIDER + "; email_proven=true",
                null);
        return authService.issueTokensFor(user);
    }

    /**
     * Binds a Google identity to the account the caller is already authenticated as.
     *
     * <p>No merge, ever. An identity already bound elsewhere is refused rather than adopted, so
     * signing in with Google can never merge two accounts into one.
     */
    @Transactional
    public AuthResponse linkAccount(AccountLinkRequest request, String ipAddress) {
        AuthenticatedUser caller = currentUser.required();
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // The per-account budget is spent unconditionally, before any other check and whether
        // or not the rest of the request is honest. It is the same budget login spends: this
        // endpoint takes a password, and a password-guessing oracle is a password-guessing
        // oracle through whichever door it is reachable by.
        authService.consumeLoginBudgetFor(user.getEmail());

        GoogleIdentityClaims claims = tokenVerifier.verify(request.googleIdToken());

        // Checked BEFORE the password, and answered 401 rather than 409. A 409 here would be a
        // statement that this Google account exists on this platform, which is a fact about
        // somebody else's account and is exactly the kind of thing an unauthenticated-ish
        // endpoint must never confirm.
        if (identityRepository.existsByProviderAndSubject(PROVIDER, claims.subject())) {
            throw new AuthenticationFailedException("Invalid credentials");
        }

        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(user.getEmail(), request.password()));

        UserIdentity identity = new UserIdentity(
                user,
                PROVIDER,
                claims.subject(),
                claims.normalizedEmail(),
                claims.emailVerified(),
                claims.hostedDomain());
        identity.markVerified(now());
        identityRepository.save(identity);

        auditLogService.record(
                "GOOGLE_IDENTITY_LINKED",
                "USER",
                user.getId(),
                "provider=" + PROVIDER + "; method=account_link",
                ipAddress);
        return authService.issueTokensFor(user);
    }
}