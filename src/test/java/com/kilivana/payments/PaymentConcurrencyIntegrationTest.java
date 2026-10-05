package com.kilivana.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.kilivana.auth.api.AuthResponse;
import com.kilivana.auth.api.SignupRequest;
import com.kilivana.orders.api.CreateOrderRequest;
import com.kilivana.orders.api.OrderResponse;
import com.kilivana.payments.api.CreatePaymentRequest;
import com.kilivana.payments.api.PaymentResponse;
import com.kilivana.payments.api.VerifyPaymentRequest;
import com.kilivana.payments.domain.PaymentStatus;
import com.kilivana.products.api.CreateProductRequest;
import com.kilivana.support.IntegrationTestSupport;
import com.kilivana.users.domain.UserRole;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fires genuinely concurrent transitions at one payment and asserts that exactly one commits.
 *
 * <p>The mechanism being pinned is optimistic locking, not a pessimistic row lock.
 * {@code BaseEntity} declares {@code @Version} and {@code V1__initial_schema.sql} gave every
 * table a {@code version} column, so two transactions that read the same {@code PENDING} row
 * and both try to write it collide on the version predicate, and the loser is rolled back as an
 * {@code OptimisticLockingFailureException} that {@code GlobalExceptionHandler} renders as 409.
 * The deleted webhook used to be the only caller of the now-deleted
 * {@code findByExternalIdForUpdate}, so this is the only place the money path's concurrency
 * behaviour is exercised at all — and an earlier revision of this suite never created a real
 * race, which meant a regression that dropped the version check, loaded the entity outside a
 * transaction, or wrote the status field directly would have kept every test green while
 * letting two concurrent calls both take effect.
 *
 * <p>The <em>arrival</em> is made deterministic rather than probabilistic: all N threads park
 * on a latch immediately before issuing their request, so they enter the filter chain together
 * instead of trickling out one at a time. A test that merely loops "fast enough" is precisely
 * the kind that cannot fail.
 *
 * <p>The <em>outcome</em> is deliberately not made deterministic, and the assertions are written
 * to be independent of it. Which of the eight racers commits is a genuine timing result, and
 * pinning it would mean serialising the racers — which is the opposite of the test. The
 * quantity that must be invariant is the number of <em>commits</em>, and that is read from the
 * {@code version} column rather than inferred from HTTP status codes: an idempotent replay of
 * the winning state legitimately answers 200. See the comment on the mixed race for the two
 * orderings this class has to tolerate.
 */
class PaymentConcurrencyIntegrationTest extends IntegrationTestSupport {

    /**
     * Eight is enough that a lost version check is overwhelmingly likely to be observed, and
     * small enough that eight concurrent sessions do not exhaust the pool the rest of the suite
     * shares.
     */
    private static final int CONCURRENCY = 8;

    /**
     * How many of those racers attempt {@code verify}. Counted from {@link #attemptsVerify}
     * rather than written as {@code CONCURRENCY / 2} so the bound asserted below cannot drift
     * away from the split the race actually uses.
     */
    private static final int VERIFY_RACERS = (int) IntStream.range(0, CONCURRENCY)
            .filter(PaymentConcurrencyIntegrationTest::attemptsVerify)
            .count();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Creates the listing, and is the only party allowed to verify or fail its payment. */
    private AuthResponse seller;

    /** Places the order. A different account: an order on your own listing is a 409. */
    private AuthResponse buyer;

    @BeforeEach
    void signUpBothParties() throws Exception {
        seller = signup(new SignupRequest(
                "concurrency-seller@example.com",
                "secretPass1",
                "Concurrency Seller",
                "+254700000041",
                UserRole.FARMER,
                null,
                "Concurrency Farm",
                "Nakuru",
                null,
                null,
                null,
                null,
                null));
        buyer = signup(new SignupRequest(
                "concurrency-buyer@example.com",
                "secretPass1",
                "Concurrency Buyer",
                "+254700000042",
                UserRole.SUPPLIER,
                "Concurrency Foods",
                null,
                null,
                null,
                null,
                null,
                null,
                null));
    }

    @Test
    @DisplayName("concurrent verifies of one payment commit exactly once")
    void concurrentVerifiesLeaveExactlyOneWinner() throws Exception {
        PaymentResponse payment = pendingPayment("concurrency-verify");
        long versionBefore = versionOf(payment.paymentId());

        Outcome outcome = race(index -> verify(payment.idempotencyKey()));

        // Deliberately NOT "exactly one 200". `PaymentService.verify` short-circuits when the
        // payment is already VERIFIED, so a racer that arrives after the winner committed gets
        // 200 with the state it would have written anyway. That is idempotency, and it is
        // correct. Asserting one 200 would be asserting a transport detail that the design is
        // entitled to vary, and would have made this test red for a legitimate reason.
        //
        // What must never happen: two commits. And the status must be the one the winner chose,
        // with no 500 from a lost version check escaping as an unhandled error.
        assertThat(outcome.otherStatuses)
                .as("responses that are neither a write nor an idempotent replay or a 409")
                .isEmpty();
        assertThat(outcome.succeeded)
                .as("at least one racer must have committed")
                .isNotEmpty();
        assertThat(outcome.succeeded.size() + outcome.conflicts.size())
                .isEqualTo(CONCURRENCY);
        assertThat(paymentRepositoryStatus(payment.paymentId())).isEqualTo(PaymentStatus.VERIFIED);
        // The discriminator. One version increment, not eight. Remove the @Version check, load
        // the entity outside its transaction, or write the status field directly and every
        // racer flushes, the version lands at before+N, and this fails.
        assertThat(versionOf(payment.paymentId())).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("concurrent verify and fail of one payment cannot both take effect")
    void concurrentVerifyAndFailCannotBothTakeEffect() throws Exception {
        PaymentResponse payment = pendingPayment("concurrency-verify-fail");
        long versionBefore = versionOf(payment.paymentId());

        // Half the racers verify, half mark the payment failed. Both read PENDING, so without
        // the version check both would commit and the last writer would silently decide
        // whether the buyer paid. `markFailed` has no idempotency short-circuit, so this case
        // is strictly harder than the verify-only case above.
        Outcome outcome = race(index -> attemptsVerify(index)
                ? verify(payment.idempotencyKey())
                : fail(payment.paymentId()));

        PaymentStatus finalStatus = paymentRepositoryStatus(payment.paymentId());
        assertThat(finalStatus)
                .as("a payment cannot end up both VERIFIED and FAILED")
                .isIn(PaymentStatus.VERIFIED, PaymentStatus.FAILED);
        assertThat(outcome.otherStatuses)
                .as("a 500 here would mean an OptimisticLockingFailureException escaped as unhandled")
                .isEmpty();

        // What is being measured here is COMMITS, not 200s. How many racers answer 200 depends
        // on the interleaving, and that is not a defect:
        //
        //   fail won  -> the one committer answers 200; the other 3 fails read FAILED and
        //                 markFailed() throws; all 4 verifies read FAILED and markVerified()
        //                 throws. Exactly 1x 200 and 7x 409.
        //   verify won-> the one committer answers 200; the other 3 verifies read VERIFIED and
        //                 PaymentService.verify short-circuits at PaymentService.java:106 to a
        //                 200 without writing anything — but only if they read the row AFTER
        //                 that commit. A racer that read PENDING before it loses on the version
        //                 predicate and answers 409 instead. So 200s can be 1, 2, 3 or 4 of the
        //                 4 verify racers, and the other 4 are always 409.
        //
        // The old assertion was `winners hasSize(1)`, i.e. "exactly one 200". That made the
        // test red whenever a verify won the race, which is correct production behaviour: an
        // idempotent replay legitimately answers 200. HTTP status was being used as a proxy
        // for the number of writes, and it is not one. Both branches are now asserted exactly
        // as they occur, which is tighter than the old check in the FAILED branch — it still
        // pins the single 200 that markFailed's lack of a replay path guarantees.
        if (finalStatus == PaymentStatus.VERIFIED) {
            assertThat(outcome.succeeded)
                    .as("only a verify racer can answer 200 once the row is VERIFIED: one committed, "
                            + "the rest either replayed it or lost the version check")
                    .isNotEmpty()
                    .hasSizeLessThanOrEqualTo(VERIFY_RACERS);
        } else {
            // markFailed has no short-circuit, so nothing can replay the committed state: the
            // 200s are exactly the commits, and there was one.
            assertThat(outcome.succeeded).as("transitions that took effect").hasSize(1);
        }
        assertThat(outcome.succeeded.size() + outcome.conflicts.size()).isEqualTo(CONCURRENCY);

        // The discriminator, and the assertion with the teeth. One version increment, not four.
        // Remove the @Version check, load the entity outside its transaction, or write the
        // status field directly and every racer flushes, the version lands at before+4, and
        // this is the line that goes red. Nothing above it can fail without a version
        // increment also being wrong.
        assertThat(versionOf(payment.paymentId())).isEqualTo(versionBefore + 1);
    }

    @FunctionalInterface
    private interface Transition {
        int perform(int index) throws Exception;
    }

    private Outcome race(Transition transition) throws Exception {
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < CONCURRENCY; index++) {
                final int slot = index;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    // Every thread parks here until the last one has arrived, so the requests
                    // leave the latch together instead of trickling out one at a time.
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the racers were never released");
                    }
                    return transition.perform(slot);
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not every racer reached the start line");
            }
            start.countDown();

            Outcome outcome = new Outcome();
            for (Future<Integer> future : futures) {
                outcome.record(future.get(60, TimeUnit.SECONDS));
            }
            return outcome;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Caller is the seller: only the seller of the order may verify or fail its payment. */
    private int verify(String idempotencyKey) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/payments/verify"), seller.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new VerifyPaymentRequest(idempotencyKey))))
                .andReturn().getResponse().getStatus();
    }

    private int fail(UUID paymentId) throws Exception {
        return mockMvc.perform(
                        authorized(post("/api/v1/payments/{paymentId}/fail", paymentId), seller.accessToken()))
                .andReturn().getResponse().getStatus();
    }

    private static final class Outcome {
        /**
         * Racers that answered 200. Deliberately NOT called "winners": a 200 can be an
         * idempotent replay that committed nothing, and an earlier revision of this class named
         * the list "winners", which invited an assertion that 200s and commits are the same
         * count. They are not.
         */
        private final List<Integer> succeeded = new ArrayList<>();

        private final List<Integer> conflicts = new ArrayList<>();
        private final List<Integer> otherStatuses = new ArrayList<>();

        void record(int status) {
            if (status == HttpStatus.OK.value()) {
                succeeded.add(status);
            } else if (status == HttpStatus.CONFLICT.value()) {
                conflicts.add(status);
            } else {
                otherStatuses.add(status);
            }
        }
    }

    /** The mixed race's split: alternating verify and fail across the eight racer slots. */
    private static boolean attemptsVerify(int index) {
        return index % 2 == 0;
    }

    private PaymentResponse pendingPayment(String idempotencyKey) throws Exception {
        var product = createProduct(seller.accessToken(), new CreateProductRequest(
                "Concurrency widget",
                "A widget used to race payment transitions",
                "kg",
                new BigDecimal("100.00"),
                new BigDecimal("10.00"),
                null,
                null,
                new BigDecimal("1.00")));
        OrderResponse order = createOrder(buyer.accessToken(),
                new CreateOrderRequest(product.productId(), new BigDecimal("10.00"), "KES"));
        PaymentResponse payment = createPayment(buyer.accessToken(),
                new CreatePaymentRequest(order.orderId(), "MPESA", idempotencyKey));
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        return payment;
    }

    private PaymentStatus paymentRepositoryStatus(UUID paymentId) {
        String value = jdbcTemplate.queryForObject(
                "select status from payments where id = ?", String.class, paymentId);
        return PaymentStatus.valueOf(value);
    }

    private long versionOf(UUID paymentId) {
        Long value = jdbcTemplate.queryForObject(
                "select version from payments where id = ?", Long.class, paymentId);
        return value == null ? -1L : value;
    }
}
