package com.kilivana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.LoginRequest;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.domain.UserRole;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the per-account login limit through the whole filter chain and service stack.
 *
 * <p>This is the limit that holds regardless of what the address resolver believes. Every other
 * credential limit in this service is keyed on a client address derived from a proxy header, so
 * every one of them resets the moment a caller forges or rotates that address. This one is keyed
 * on a SHA-256 of the submitted email, which no header produces, and that is the entire reason it
 * exists — so the behaviour worth proving here is not the 429, it is the 429 arriving no matter
 * which address the caller claims to be.
 *
 * <p>Requests are made with no {@code X-Forwarded-For} from a single socket peer, which is the
 * shape the deployed service actually sees behind an edge.
 */
class AccountLoginLimitIntegrationTest extends IntegrationTestSupport {

    /** The shipped default from {@code RateLimitProperties}: 10 attempts per 15 minutes. */
    private static final int ACCOUNT_LIMIT = 10;

    private static final String REGISTERED = "victim@example.com";
    private static final String UNREGISTERED = "nobody-at-all@example.com";

    /**
     * "victim" with both i's written as U+0131 DOTLESS I. Not a registered address, and not
     * obtainable by case-folding or trimming — but the same account to Postgres, whose
     * {@code upper()} maps U+0131 to ASCII I under the container's {@code en_US.utf8} collation.
     */
    private static final String DOTLESS_I_SPELLING = "vıctım@example.com";

    @Test
    @DisplayName("the budget is spent on the account, so forging an address does not reset it")
    void forgingAnAddressDoesNotResetTheAccountBudget() throws Exception {
        signup(registeredBuyer());

        // Every attempt claims a different source address, which is exactly the trick that walks
        // out of the filter's per-address bucket. The account key is derived from the email body
        // alone, so all twenty of these land in the same bucket and the eleventh is refused.
        List<Integer> statuses = new ArrayList<>();
        for (int attempt = 0; attempt < ACCOUNT_LIMIT + 1; attempt++) {
            statuses.add(statusOfLogin(REGISTERED, "wrong-password", "198.51.100." + attempt));
        }

        assertThat(statuses.subList(0, ACCOUNT_LIMIT))
                .as("the first ten are ordinary failed logins, not throttles")
                .containsOnly(401);
        assertThat(statuses.get(ACCOUNT_LIMIT))
                .as("the eleventh attempt is refused on the account, after ten rotated addresses")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("a registered and an unregistered account answer 429 identically")
    void theThrottleCannotBeUsedToEnumerateAccounts() throws Exception {
        signup(registeredBuyer());

        // Both accounts are driven past the limit, then compared byte for byte on every field a
        // caller can read except the request id and the timestamp, which vary per request by
        // design and are generated after the fact. A single differing character here would let an
        // attacker enumerate the platform's customers from a status code nobody rate-limits.
        loginUntilThrottled(REGISTERED);
        loginUntilThrottled(UNREGISTERED);

        // Fresh addresses for the comparison, so neither body can be a per-address 429 and the
        // two responses differ only in what the account lookup (never performed) would have said.
        MvcResult registered = loginResult(REGISTERED, "198.51.100.240");
        MvcResult unregistered = loginResult(UNREGISTERED, "198.51.100.241");

        assertThat(registered.getResponse().getStatus())
                .as("a spent budget is a 429 for both")
                .isEqualTo(429);
        assertThat(unregistered.getResponse().getStatus()).isEqualTo(429);
        assertThat(bodyWithoutVolatileFields(registered))
                .as("the two bodies must be indistinguishable")
                .isEqualTo(bodyWithoutVolatileFields(unregistered));
        // Retry-After is NOT asserted equal, and cannot be. It is the residual of each account's
        // own window, and the two windows necessarily began a few seconds apart because the
        // attacker drove them a few seconds apart. It leaks nothing an attacker does not already
        // know from the timing of their own requests, which is why that is asserted instead: both
        // are real, both are inside the configured window, and neither reveals existence.
        assertThat(registered.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(unregistered.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(Long.parseLong(registered.getResponse().getHeader("Retry-After")))
                .isBetween(1L, 15L * 60L);
        assertThat(Long.parseLong(unregistered.getResponse().getHeader("Retry-After")))
                .isBetween(1L, 15L * 60L);
        assertThat(registered.getResponse().getContentAsString())
                .as("neither response may echo the submitted address")
                .doesNotContain(REGISTERED)
                .doesNotContain(UNREGISTERED);
    }

    @Test
    @DisplayName("an account that has not spent its budget is untouched by another account's")
    void oneAccountsBudgetIsNotAnothers() throws Exception {
        signup(registeredBuyer());

        loginUntilThrottled(UNREGISTERED);

        // A shared bucket here would let an attacker lock every customer out by guessing one
        // address nobody owns — a denial of service built out of the very control meant to stop
        // credential stuffing.
        assertThat(statusOfLogin(REGISTERED, "wrong-password", "198.51.100.240"))
                .as("a different account is still served normally")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("the budget is consumed before the password is checked, and by successful logins too")
    void theBudgetIsConsumedWhetherOrNotTheAttemptSucceeds() throws Exception {
        signup(registeredBuyer());

        // Successful attempts count. A limit that only counted failures would be free to a
        // credential-stuffing run against accounts whose password is in a leaked list, which is
        // precisely the run this limit exists to slow.
        for (int attempt = 0; attempt < ACCOUNT_LIMIT; attempt++) {
            assertThat(statusOfLogin(REGISTERED, "secretPass1", "198.51.100." + attempt))
                    .as("correct credentials are served while the budget lasts")
                    .isEqualTo(200);
        }

        assertThat(statusOfLogin(REGISTERED, "secretPass1", "198.51.100.200"))
                .as("the eleventh attempt is refused even though its password is correct")
                .isEqualTo(429);
    }

    /**
     * Fix 2, end to end, against a real Postgres collation.
     *
     * <p>This cannot be written as a unit test, and the reason is the whole point. The account
     * lookup resolves with {@code upper(email) = upper(:typed)}, and the container's
     * {@code en_US.utf8} collation folds U+0131 DOTLESS I to ASCII I. Java's
     * {@code trim().toLowerCase(Locale.ROOT)} does not, so "vıctım@example.com" and
     * "victim@example.com" are the same account to the database and two different SHA-256s to the
     * key derivation. A Mockito test stubs the repository, which stubs away the very comparison
     * that creates the collision, so it can only ever assert what the code was told — not what the
     * database does.
     *
     * <p>Measured on this image: {@code upper('vıctım@example.com') = upper('victim@example.com')}
     * is true, while {@code 'vıctım@example.com' = 'victim@example.com'} and ILIKE are both false.
     * The fold is specifically in {@code upper()}, which is what {@code …IgnoreCase} compiles to.
     *
     * <p>"victim" carries two dotless-i positions, so it has 2² = 4 spellings. Under the old key
     * derivation that is four independent budgets of ten attempts each, reachable from any source
     * address — forty guesses against one account, which is what the limit exists to prevent.
     */
    @Test
    @DisplayName("a dotless-i spelling authenticates the same account the collation folds it to")
    void aDotlessISpellingAuthenticatesTheSameAccount() throws Exception {
        signup(registeredBuyer());

        // The fold, demonstrated rather than assumed: this address contains no ASCII 'i' at all and
        // was never registered, yet it authenticates as the registered account.
        AuthResponse response = readBody(
                mockMvc.perform(peer(post("/api/v1/auth/login"), "198.51.100.10")
                                .contentType(MediaType.APPLICATION_JSON)
                                .characterEncoding(StandardCharsets.UTF_8)
                                .content(objectMapper.writeValueAsBytes(
                                        new LoginRequest(DOTLESS_I_SPELLING, "secretPass1"))))
                        .andReturn(),
                AuthResponse.class);

        assertThat(response.email())
                .as("the database folded the dotless i; the caller got the one stored account")
                .isEqualTo(REGISTERED);
    }

    @Test
    @DisplayName("attempts made through a dotless-i spelling throttle the canonical address too")
    void everyCollationFoldedSpellingSharesOneAccountBudget() throws Exception {
        signup(registeredBuyer());

        // Drive ONE spelling of the address past the account limit. Wrong passwords only, so the
        // arithmetic is exactly ACCOUNT_LIMIT and nothing is consumed by a success. The socket peer
        // rotates every attempt so the filter's own per-address bucket is never the one answering.
        int throttledAt = -1;
        for (int attempt = 0; attempt < ACCOUNT_LIMIT + 5; attempt++) {
            if (statusOfLogin(DOTLESS_I_SPELLING, "wrong-password", "198.51.100." + (100 + attempt)) == 429) {
                throttledAt = attempt;
                break;
            }
        }

        assertThat(throttledAt)
                .as("the account limit is reached on the account, at its configured value")
                .isEqualTo(ACCOUNT_LIMIT);

        // The assertion that decides this test. The canonical spelling produces a DIFFERENT
        // SHA-256 under Java's normalisation, so the old derivation gave it a fresh, untouched
        // budget and answered 401 here — one fresh budget per dotless i, per spelling, from any
        // source address. Keying on the stored address is what collapses them back to one bucket.
        assertThat(statusOfLogin(REGISTERED, "wrong-password", "198.51.100.200"))
                .as("the canonical spelling is throttled by attempts made with a dotless-i spelling")
                .isEqualTo(429);
    }

    /**
     * Rotates the socket peer every attempt. Without this the filter's own per-address bucket
     * (login:5/min) is exhausted first and the account limit is never reached — the two limits
     * stack, and the address one is the outer shell this suite is explicitly not trying to test.
     */
    private void loginUntilThrottled(String email) throws Exception {
        for (int attempt = 0; attempt < ACCOUNT_LIMIT + 5; attempt++) {
            if (statusOfLogin(email, "wrong-password", "198.51.100." + attempt) == 429) {
                return;
            }
        }
    }

    private SignupRequest registeredBuyer() {
        return new SignupRequest(
                REGISTERED,
                "secretPass1",
                "Victim",
                "+254700000001",
                UserRole.BUYER,
                null, null, null, null, null, null, null, null);
    }

    private MvcResult loginResult(String email, String remoteAddress) throws Exception {
        return mockMvc.perform(peer(post("/api/v1/auth/login"), remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new LoginRequest(email, "wrong-password"))))
                .andReturn();
    }

    private int statusOfLogin(String email, String password, String remoteAddress) throws Exception {
        return mockMvc.perform(peer(post("/api/v1/auth/login"), remoteAddress)
                        .contentType(MediaType.APPLICATION_JSON)
                        // Stated rather than assumed: the dotless-i spellings below are non-ASCII,
                        // and a body decoded as anything other than UTF-8 would arrive as a
                        // different address entirely — which would make this suite pass for the
                        // wrong reason.
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content(objectMapper.writeValueAsBytes(new LoginRequest(email, password))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    /**
     * The response with the two fields that legitimately vary per request removed. The request id
     * is a UUID minted for the request and the timestamp is {@code OffsetDateTime.now()}, both
     * produced by the shared error builder after the message is decided, so they cannot carry any
     * information about the account.
     */
    private String bodyWithoutVolatileFields(MvcResult result) throws Exception {
        var node = objectMapper.readTree(result.getResponse().getContentAsString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("timestamp");
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("requestId");
        return node.toString();
    }

    /** Rotates the socket peer only. Everything else about the request is fixed. */
    private static MockHttpServletRequestBuilder peer(MockHttpServletRequestBuilder builder, String remoteAddress) {
        return builder.with(request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        });
    }
}