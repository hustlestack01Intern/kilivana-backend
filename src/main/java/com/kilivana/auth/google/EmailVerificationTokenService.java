package com.kilivana.auth.google;

import com.kilivana.common.exception.InvalidTokenException;
import com.kilivana.security.TokenService;
import com.kilivana.users.domain.EmailVerificationToken;
import com.kilivana.users.repository.EmailVerificationTokenRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write to {@code email_verification_tokens}, and the only transaction boundaries in the
 * verification flow.
 *
 * <p>Separate from the service that sends mail, and that separation is the whole point: SMTP
 * costs seconds and {@code application-prod.yml} sizes the datasource pool at ten, so a mail
 * send inside a transaction holds a connection for as long as the relay takes. On an
 * unauthenticated endpoint that is a lever — ten concurrent requests and the platform has no
 * database. So the row is committed first, then the mail goes out on a transaction that owns
 * nothing.
 *
 * <p>Uses {@code org.springframework.transaction.annotation.Transactional}, unlike the
 * {@code jakarta.transaction} flavour the rest of this repository uses, because the conditional
 * "update only if unused" read-modify-write here needs Spring's readOnly/rollback defaults and
 * because mixing the two annotations in one package makes which one is in force a matter of
 * import order.
 */
@Service
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
public class EmailVerificationTokenService {

    private final EmailVerificationTokenRepository repository;
    private final TokenService tokenService;
    private final GoogleProperties properties;
    private final Clock clock;

    public EmailVerificationTokenService(
            EmailVerificationTokenRepository repository,
            TokenService tokenService,
            GoogleProperties properties) {
        this.repository = repository;
        this.tokenService = tokenService;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /**
     * Commits a fresh token for an address. The returned raw value is the only copy: at rest
     * there is a SHA-256 digest, so a database dump is not a set of working links.
     */
    @Transactional
    public IssuedToken mint(String normalizedEmail) {
        String raw = tokenService.generate();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))
                .plus(properties.verificationTokenTtl());
        EmailVerificationToken saved = repository.save(new EmailVerificationToken(
                tokenService.hash(raw),
                normalizedEmail,
                expiresAt));
        return new IssuedToken(saved.getId(), normalizedEmail, raw, expiresAt);
    }

    /**
     * Spends a token and returns the address it proved.
     *
     * <p>The spend is a conditional update, so two concurrent redemptions produce one success
     * and one rejection. An entity read-then-write would let both observe {@code used_at is
     * null} and let the version column decide the winner, which surfaces as a 409 instead of
     * the 400 every rejection is supposed to be.
     *
     * <p>Unknown, expired and already-spent are one answer. A caller who could tell them apart
     * could confirm that an address once started a registration.
     */
    @Transactional
    public String redeem(String rawToken) {
        String hash = tokenService.hash(rawToken.trim());
        EmailVerificationToken token = repository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired verification token"));
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
        if (token.isExpired(now) || repository.spend(token.getId(), now) == 0) {
            throw new InvalidTokenException("Invalid or expired verification token");
        }
        return token.getEmail();
    }

    /**
     * Invalidates a token whose mail never went out.
     *
     * <p>Short transaction, run after the failed send rather than around it. The user never
     * received the token, so leaving it live would leave a credential alive that nobody knows
     * exists; the cost is that a mail failure costs the user a second email, which is the
     * correct direction to fail in.
     */
    @Transactional
    public void markUndeliverable(UUID tokenId) {
        repository.spend(tokenId, OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)));
    }

    /** The only copy of a live token exists here, in memory, on its way to a mailbox. */
    public record IssuedToken(
            UUID id,
            String email,
            String rawToken,
            OffsetDateTime expiresAt) {
    }
}