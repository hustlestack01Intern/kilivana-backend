package com.kilivana.auth.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kilivana.common.exception.InvalidTokenException;
import com.kilivana.common.exception.ServiceUnavailableException;
import com.kilivana.security.TokenService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Holds the short-lived ticket that carries a proven mailbox into onboarding.
 *
 * <p>The ticket carries an address and nothing else — no user id, no role, no authority.
 * Redeeming it still runs every rule ordinary signup runs, so a ticket is an interruption in
 * a flow rather than a credential for one.
 *
 * <p><strong>Read-and-delete is one script, not two commands.</strong> {@code GETDEL} would be
 * the obvious spelling and it is Redis 6.2+, which this repository's own {@code RedisRateLimiter}
 * explicitly avoids. Two commands would leave a window in which two concurrent redemptions both
 * read the ticket before either deletes it, which is the same single-use property the flow
 * depends on. The script works on every Redis version this service is deployed against.
 *
 * <p>The key is the token's hash, so a Redis dump yields no live tickets.
 */
@Component
@ConditionalOnProperty(prefix = "kilivana.security.google", name = "enabled", havingValue = "true")
public class OnboardingTicketStore {

    private static final String KEY_PREFIX = "kilivana:google-onboarding:";

    private static final DefaultRedisScript<String> GET_AND_DELETE = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); "
                    + "if v then redis.call('DEL', KEYS[1]); end; "
                    + "return v;",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final GoogleProperties properties;
    private final Clock clock;

    public OnboardingTicketStore(
            StringRedisTemplate redisTemplate,
            TokenService tokenService,
            GoogleProperties properties) {
        this.redisTemplate = redisTemplate;
        this.tokenService = tokenService;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /**
     * What a ticket carries: an address, the Google identity it was issued to, and whether the
     * address has been proven.
     *
     * <p>{@code emailProven} is the field the whole flow turns on, and it is deliberately not
     * the same as {@code providerEmailVerified}. The latter is a record of what Google's token
     * claimed, kept so onboarding can record what was asserted without asking Google again. The
     * former is the question onboarding asks: has anyone demonstrated control of this mailbox,
     * either because Google vouched for it or because they answered the mail.
     *
     * <p>No user id and no role. A ticket is an interruption in a flow, not a credential for
     * one, and anything that made it more than that would be a way to create an account without
     * choosing a role under the ordinary signup rules.
     */
    public record Ticket(
            String email,
            String subject,
            String hostedDomain,
            boolean providerEmailVerified,
            boolean emailProven) {
    }

    /**
     * Mints a ticket whose mailbox proof is whatever Google managed to establish.
     */
    public IssuedTicket mint(String normalizedEmail, GoogleIdentityClaims claims) {
        return mint(normalizedEmail, claims, claims.authoritative());
    }

    /**
     * Mints a ticket with an explicitly stated proof state.
     *
     * <p>The third argument is not a convenience. {@link #mint(String, GoogleIdentityClaims)}
     * derives it from Google's claims and is correct only for the handshake; the verification
     * exchange must state {@code true} on the strength of the mailbox reply, with Google's
     * claims deliberately marked unverified so the two sources of proof stay distinguishable.
     */
    public IssuedTicket mint(String normalizedEmail, GoogleIdentityClaims claims, boolean emailProven) {
        Ticket ticket = new Ticket(
                normalizedEmail,
                claims.subject(),
                claims.hostedDomain(),
                claims.emailVerified(),
                emailProven);
        String raw = tokenService.generate();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC))
                .plus(properties.onboardingTicketTtl());
        try {
            redisTemplate.opsForValue().set(
                    key(raw),
                    objectMapper.writeValueAsString(ticket),
                    properties.onboardingTicketTtl());
        } catch (DataAccessException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            // Fail closed. A ticket that was written but not acknowledged must not be treated
            // as issued, or the caller would be told to retry a credential that already exists.
            throw new ServiceUnavailableException("Onboarding could not be started. Please retry shortly.");
        }
        return new IssuedTicket(raw, expiresAt);
    }

    /**
     * Redeems a ticket exactly once.
     *
     * @throws InvalidTokenException when the ticket is unknown, expired or already spent —
     *         all three are the same answer, so a caller cannot probe for which.
     * @throws ServiceUnavailableException when the store cannot answer, because single use
     *         cannot then be enforced.
     */
    public Ticket consume(String rawTicket) {
        String key = key(rawTicket);
        String payload;
        try {
            payload = redisTemplate.execute(GET_AND_DELETE, List.of(key));
        } catch (DataAccessException exception) {
            throw new ServiceUnavailableException("Onboarding could not be completed. Please retry shortly.");
        }
        if (payload == null) {
            throw new InvalidTokenException("Invalid or expired onboarding ticket");
        }
        try {
            Ticket ticket = objectMapper.readValue(payload, Ticket.class);
            if (ticket.email() == null || ticket.email().isBlank() || ticket.subject() == null) {
                throw new InvalidTokenException("Invalid or expired onboarding ticket");
            }
            return ticket;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            // The entry existed but could not be read. The GETDEL above already removed it,
            // so this fails closed and the caller must start over.
            throw new InvalidTokenException("Invalid or expired onboarding ticket");
        }
    }

    private String key(String rawTicket) {
        return KEY_PREFIX + tokenService.hash(rawTicket);
    }

    public record IssuedTicket(String ticket, OffsetDateTime expiresAt) {
    }
}