package com.kilivana.auth.google;

import com.kilivana.common.exception.ServiceUnavailableException;
import com.kilivana.security.RateLimitResult;
import com.kilivana.security.RateLimiter;
import com.kilivana.security.TokenService;
import com.kilivana.security.config.RateLimitProperties;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Decides whether a verification email may be sent, and sends it outside any transaction.
 *
 * <p>Three layers, all fail-closed. This is deliberately the opposite of
 * {@code AuthService.consumeAccountLoginAttempt}, which fails open when the limiter cannot
 * answer: there, the filter has already answered 503 on every credential path, so an extra
 * gate would convert "limiter down" into "nobody can log in at all". Here the send path is a
 * single unauthenticated endpoint that is otherwise unthrottled, and a limiter that cannot
 * answer means the bound on outbound mail is unknown — so it sends nothing and says 503.
 *
 * <ol>
 *   <li>per recipient, keyed on a SHA-256 of the normalised address, so no client header can
 *       produce this key and the limit survives a wrong client-IP derivation;
 *   <li>per Google identity, keyed on a SHA-256 of {@code sub}, which is the one thing an
 *       attacker rotating addresses still cannot change without also rotating Google accounts;
 *   <li>a global per-minute budget, which is the only layer that bounds true blast radius when
 *       an attacker rotates both addresses and identities.
 * </ol>
 *
 * <p>None of the three is ever refunded. A send that was refused still spent its budget,
 * because the cost that matters is the attempt.
 *
 * <p>The global budget ignores {@code rate-limit.enabled}, unlike the other two. That switch
 * exists so an operator can turn politeness limits off; this one is not politeness, it is the
 * ceiling on how much mail this service can be made to emit, and a limiter that can be
 * disabled is not a ceiling.
 */
@Service
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /**
     * Concurrent SMTP sends this process will attempt at once.
     *
     * <p>Smaller than {@code application-prod.yml}'s pool of ten on purpose, though the send
     * holds no connection. SMTP at one to five seconds a send, across enough concurrent
     * unauthenticated requests, parks every worker this application has; the bound is what
     * turns that flood into a queue instead of an outage of login, orders and payments. A
     * request that cannot get a slot within the timeout gives up rather than waiting behind the
     * flood.
     */
    private static final int MAX_CONCURRENT_SENDS = 4;

    private static final long SEND_SLOT_TIMEOUT_SECONDS = 2L;

    private static final String GLOBAL_BUDGET_KEY = "google-email-verification:global";

    private final EmailVerificationTokenService tokenService;
    private final TokenService hashing;
    private final EmailVerificationMailSender mailSender;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimitProperties;
    private final GoogleProperties properties;
    private final Semaphore sendSlots = new Semaphore(MAX_CONCURRENT_SENDS);

    public EmailVerificationService(
            EmailVerificationTokenService tokenService,
            TokenService hashing,
            EmailVerificationMailSender mailSender,
            RateLimiter rateLimiter,
            RateLimitProperties rateLimitProperties,
            GoogleProperties properties) {
        this.tokenService = tokenService;
        this.hashing = hashing;
        this.mailSender = mailSender;
        this.rateLimiter = rateLimiter;
        this.rateLimitProperties = rateLimitProperties;
        this.properties = properties;
    }

    /**
     * Mints a token and mails it, subject to the send budget.
     *
     * <p>Not transactional, and must not be: the row is committed by
     * {@link EmailVerificationTokenService#mint} before the mail is attempted, so an SMTP relay
     * that takes five seconds never holds a database connection for five seconds.
     *
     * @return the raw token when delivery is simulated and nothing is null when it is not, so
     *         the caller can only ever hand out a token in the mode that is refused on prod.
     */
    public String requestVerification(String normalizedEmail, String subject) {
        if (!acquireSendBudget(normalizedEmail, subject)) {
            return null;
        }
        EmailVerificationTokenService.IssuedToken issued = tokenService.mint(normalizedEmail);
        if (properties.simulateDelivery()) {
            return issued.rawToken();
        }
        deliver(issued);
        return null;
    }

    private void deliver(EmailVerificationTokenService.IssuedToken issued) {
        boolean acquired;
        try {
            acquired = sendSlots.tryAcquire(SEND_SLOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            tokenService.markUndeliverable(issued.id());
            return;
        }
        if (!acquired) {
            // Queued behind a flood and gave up. No send, and the token is invalidated so a
            // credential nobody received is not left alive.
            log.warn("Verification mail for one recipient was abandoned after waiting {}s for a send slot: "
                    + "delivery is saturated", SEND_SLOT_TIMEOUT_SECONDS);
            tokenService.markUndeliverable(issued.id());
            return;
        }
        try {
            mailSender.sendVerificationLink(issued.email(), issued.rawToken());
        } catch (RuntimeException exception) {
            // No throwable argument: Spring's MailSendException message carries the SMTP host
            // and the authenticated username, and the stack trace would print both.
            log.warn("Verification mail delivery failed, exceptionType={}; the verification token has "
                    + "been invalidated", exception.getClass().getName());
            tokenService.markUndeliverable(issued.id());
        } finally {
            sendSlots.release();
        }
    }

    /**
     * @return true when the mail may be sent. A limiter that cannot answer throws instead,
     *         because proceeding with an unknown budget is the failure this exists to stop.
     */
    private boolean acquireSendBudget(String normalizedEmail, String subject) {
        Duration cooldown = Duration.ofSeconds(properties.sendCooldownSeconds());

        if (rateLimitProperties.enabled()) {
            if (!consumeOne("email-verification-send:" + hashing.hash(normalizedEmail), cooldown)) {
                return false;
            }
            if (subject != null && !consumeOne("google-identity:" + hashing.hash(subject), cooldown)) {
                return false;
            }
        }

        RateLimitResult global = rateLimiter.tryConsume(
                GLOBAL_BUDGET_KEY, properties.globalSendsPerMinute(), Duration.ofMinutes(1));
        if (global.status() == RateLimitResult.Status.UNAVAILABLE) {
            throw new ServiceUnavailableException(
                    "Email verification is temporarily unavailable. Please retry shortly.");
        }
        return global.status() == RateLimitResult.Status.ALLOWED;
    }

    private boolean consumeOne(String key, Duration window) {
        RateLimitResult result = rateLimiter.tryConsume(key, 1, window);
        if (result.status() == RateLimitResult.Status.UNAVAILABLE) {
            throw new ServiceUnavailableException(
                    "Email verification is temporarily unavailable. Please retry shortly.");
        }
        return result.status() == RateLimitResult.Status.ALLOWED;
    }
}