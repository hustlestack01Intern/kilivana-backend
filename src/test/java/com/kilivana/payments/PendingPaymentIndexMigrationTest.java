package com.kilivana.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@code V11} against a database that actually holds the duplicates the index forbids.
 *
 * <p>The rest of the suite migrates a clean database, where no order can ever have two PENDING
 * payments, so the reconciliation this test covers is invisible to every other test in the
 * repository. A migration whose failure mode is "the platform does not boot on the databases
 * that need it" needs its dirty path executed against a real Postgres: hence this class's own
 * container, no Spring, no Flyway, and no dependence on any other migration having run.
 *
 * <p>The script under test is read off the classpath from the very file that ships —
 * {@code src/main/resources/db/migration} lands in {@code target/classes} before tests run — and
 * handed to Postgres as one script. Only the file's <em>name</em> appears in this class; its
 * contents are restated nowhere, so the test cannot drift from the migration it pins.
 *
 * <p>It deliberately does not extend {@code IntegrationTestSupport}, contrary to every other
 * integration test here. That support class's container is shared and, by the time any test can
 * reach it, Flyway has already migrated it — so reusing it would mean running a reconciliation
 * test against a schema that V11 has already been applied to, which is precisely the state this
 * test must not be in. The container setup below copies its conventions (same image, same static
 * start, same JUnit lifecycle) rather than its inheritance.
 */
class PendingPaymentIndexMigrationTest {

    /**
     * The migration's identity, not its content. Resolved as a classpath resource so the
     * assertions run against the shipped file rather than a copy of it.
     */
    private static final String MIGRATION_RESOURCE = "/db/migration/V11__one_live_payment_per_order.sql";

    private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER;

    private static DriverManagerDataSource dataSource;

    static {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        POSTGRESQL_CONTAINER = container;
    }

    /**
     * {@code payments} exactly as {@code V1__initial_schema.sql} creates it, minus the foreign key
     * to {@code orders}.
     *
     * <p>The status check constraint is copied verbatim because it is one of the two things this
     * test must be able to fail on: a reconciliation that wrote a status outside
     * {@code chk_payments_status} would be rejected by the database instead of quietly
     * corrupting history. The FK is omitted only because honouring it would mean creating an
     * {@code orders} table plus an order row per seed, for a constraint neither the
     * reconciliation nor the index reads.
     */
    private static final String PAYMENTS_TABLE = """
            create table payments (
                id uuid primary key,
                version bigint not null default 0,
                created_at timestamptz not null,
                updated_at timestamptz not null,
                order_id uuid not null,
                status varchar(20) not null,
                provider varchar(40) not null,
                amount numeric(12, 2) not null,
                idempotency_key varchar(100) not null,
                constraint uq_payments_idempotency_key unique (idempotency_key),
                constraint chk_payments_status check (status in ('PENDING', 'VERIFIED', 'FAILED', 'REFUNDED')),
                constraint chk_payments_amount_positive check (amount > 0)
            )
            """;

    private static final UUID ORDER_WITH_TWO_PENDING = uuid("00000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_WITH_THREE_PENDING = uuid("00000000-0000-0000-0000-000000000002");
    private static final UUID ORDER_WITH_FIVE_PENDING = uuid("00000000-0000-0000-0000-000000000003");
    private static final UUID ORDER_WITH_ONE_PENDING = uuid("00000000-0000-0000-0000-000000000004");
    private static final UUID ORDER_PENDING_BESIDE_HISTORY = uuid("00000000-0000-0000-0000-000000000005");
    private static final UUID ORDER_WITH_ONLY_TERMINAL = uuid("00000000-0000-0000-0000-000000000006");
    private static final UUID ORDER_WITH_NO_PAYMENTS = uuid("00000000-0000-0000-0000-000000000007");

    /**
     * A fixed instant, not {@code now()}. The survivor rule orders by {@code created_at}, and a
     * seed of "whatever time it happened to be" could not tell correct ordering from a
     * coincidental one.
     */
    private static final Instant T0 = Instant.parse("2024-03-01T09:00:00Z");

    private JdbcTemplate jdbc;

    /** Every payment row this test seeds, in insertion order. */
    private List<Seeded> seeded;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(
                POSTGRESQL_CONTAINER.getJdbcUrl(),
                POSTGRESQL_CONTAINER.getUsername(),
                POSTGRESQL_CONTAINER.getPassword());
    }

    @AfterAll
    static void disconnect() {
        dataSource = null;
    }

    @BeforeEach
    void createSchema() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists payments");
        jdbc.execute(PAYMENTS_TABLE);
        seeded = new ArrayList<>();
    }

    private void seedDirtyDatabase() {
        // Two PENDING: the newer survives, the older is transitioned.
        seed(ORDER_WITH_TWO_PENDING, "PENDING", 1);
        seed(ORDER_WITH_TWO_PENDING, "PENDING", 2);

        // Three PENDING where the two newest share a created_at, so the tiebreaker rather than
        // physical row order decides between them. They are inserted oldest-id-first among the
        // tied pair, so "last inserted wins" and "highest id wins" are distinguishable outcomes
        // and a row-order survivor rule fails here.
        seed(ORDER_WITH_THREE_PENDING, "PENDING", 1, uuid("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"));
        seed(ORDER_WITH_THREE_PENDING, "PENDING", 3, uuid("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3"));
        seed(ORDER_WITH_THREE_PENDING, "PENDING", 3, uuid("cccccccc-cccc-cccc-cccc-ccccccccccc3"));

        // Five PENDING, the widest fan-out.
        seed(ORDER_WITH_FIVE_PENDING, "PENDING", 1);
        seed(ORDER_WITH_FIVE_PENDING, "PENDING", 2);
        seed(ORDER_WITH_FIVE_PENDING, "PENDING", 3);
        seed(ORDER_WITH_FIVE_PENDING, "PENDING", 4);
        seed(ORDER_WITH_FIVE_PENDING, "PENDING", 5);

        // Exactly one PENDING, and an order whose only PENDING is OLDER than its VERIFIED
        // sibling. Nothing may be reconciled in either case, and specifically the PENDING must
        // not be sacrificed to the newer VERIFIED: the index counts PENDING rows only, so a
        // reconciliation ranking every row per order instead of only the live ones fails here.
        seed(ORDER_WITH_ONE_PENDING, "PENDING", 1);
        seed(ORDER_PENDING_BESIDE_HISTORY, "PENDING", 1);
        seed(ORDER_PENDING_BESIDE_HISTORY, "VERIFIED", 2);
        seed(ORDER_PENDING_BESIDE_HISTORY, "FAILED", 3);

        // Terminal-only history, plus an order with no payments at all (no rows seeded, and the
        // assertions below assert it stayed that way).
        seed(ORDER_WITH_ONLY_TERMINAL, "VERIFIED", 1);
        seed(ORDER_WITH_ONLY_TERMINAL, "REFUNDED", 2);
        seed(ORDER_WITH_ONLY_TERMINAL, "FAILED", 3);
        seed(ORDER_WITH_ONLY_TERMINAL, "VERIFIED", 4);

        insertSeededRows();

        // The precondition this whole class exists for: the data has to be dirty before the
        // migration runs, or the assertions after it prove nothing.
        Integer dirtyOrders = jdbc.queryForObject("""
                select count(*) from (
                    select order_id from payments where status = 'PENDING'
                    group by order_id having count(*) > 1
                ) duplicates
                """, Integer.class);
        assertThat(dirtyOrders).as("the seed must contain orders with several PENDING payments").isEqualTo(3);
    }

    /** The same shapes without the duplicates: what a database that never raced looks like. */
    private void seedCleanDatabase() {
        seed(ORDER_WITH_ONE_PENDING, "PENDING", 1);
        seed(ORDER_PENDING_BESIDE_HISTORY, "PENDING", 1);
        seed(ORDER_PENDING_BESIDE_HISTORY, "VERIFIED", 2);
        seed(ORDER_PENDING_BESIDE_HISTORY, "FAILED", 3);
        seed(ORDER_WITH_ONLY_TERMINAL, "VERIFIED", 1);
        seed(ORDER_WITH_ONLY_TERMINAL, "REFUNDED", 2);
        seed(ORDER_WITH_ONLY_TERMINAL, "FAILED", 3);
        seed(ORDER_WITH_ONLY_TERMINAL, "VERIFIED", 4);

        insertSeededRows();

        Integer dirtyOrders = jdbc.queryForObject("""
                select count(*) from (
                    select order_id from payments where status = 'PENDING'
                    group by order_id having count(*) > 1
                ) duplicates
                """, Integer.class);
        assertThat(dirtyOrders).as("this seed must hold no duplicates at all").isZero();
    }

    private void insertSeededRows() {
        for (Seeded payment : seeded) {
            jdbc.update("""
                    insert into payments (id, version, created_at, updated_at, order_id, status, provider,
                                          amount, idempotency_key)
                    values (?, 0, ?, ?, ?, ?, 'MPESA', 100.00, ?)
                    """,
                    payment.id(),
                    Timestamp.from(payment.createdAt()),
                    Timestamp.from(payment.createdAt()),
                    payment.orderId(),
                    payment.status(),
                    "idem-" + payment.label());
        }
    }

    @Test
    @DisplayName("duplicates are reconciled, then the index exists and refuses the next one")
    void reconciliationLeavesOnePendingPerOrderAndTheIndexHolds() throws Exception {
        seedDirtyDatabase();
        Map<UUID, List<Seeded>> before = groupedByOrder();

        runMigration();

        assertIndexExistsAndIsPartialOnPending();

        // Exactly one PENDING per order, counted by the database rather than by this test.
        Integer offendingOrders = jdbc.queryForObject("""
                select count(*) from (
                    select order_id from payments where status = 'PENDING'
                    group by order_id having count(*) > 1
                ) duplicates
                """, Integer.class);
        assertThat(offendingOrders).as("orders still holding more than one PENDING payment").isZero();
        assertThat(pendingCount(ORDER_WITH_NO_PAYMENTS))
                .as("an order with no payments stays that way").isZero();
        assertThat(pendingCount(ORDER_WITH_ONLY_TERMINAL))
                .as("terminal-only history must not gain a PENDING row").isZero();

        // The survivor is the row the documented rule selects and the rest are FAILED. Both
        // expectations are derived in Java from the seed, independently of the SQL that ran.
        // Asserting every seeded PENDING's final status is also what pins the number the
        // migration reports: this seed transitions exactly seven rows (1 + 2 + 4), so the count
        // the NOTICE prints at boot cannot drift from what actually happened.
        for (Map.Entry<UUID, List<Seeded>> order : before.entrySet()) {
            List<Seeded> pendings = order.getValue().stream()
                    .filter(payment -> payment.status().equals("PENDING"))
                    .sorted(survivorOrder())
                    .toList();
            if (pendings.isEmpty()) {
                continue;
            }
            Seeded expectedSurvivor = pendings.get(pendings.size() - 1);
            assertThat(pendingId(order.getKey()))
                    .as("newest PENDING survives for order %s", order.getKey())
                    .isEqualTo(expectedSurvivor.id());
            for (Seeded payment : pendings) {
                assertThat(statusOf(payment.id()))
                        .as("payment %s on order %s", payment.id(), order.getKey())
                        .isEqualTo(payment.id().equals(expectedSurvivor.id()) ? "PENDING" : "FAILED");
            }
        }

        // Already-terminal rows are untouched in every column. A reconciliation that rewrote a
        // VERIFIED payment's timestamps, or bumped its version, would be a second and quieter
        // corruption of exactly the history this migration is supposed to preserve.
        for (Seeded payment : seeded) {
            if (!payment.status().equals("PENDING")) {
                assertThat(paymentColumns(payment.id()))
                        .as("terminal payment %s must not be modified", payment.id())
                        .isEqualTo(Map.of(
                                "status", payment.status(),
                                "created_at", Timestamp.from(payment.createdAt()),
                                "updated_at", Timestamp.from(payment.createdAt()),
                                "version", 0L));
            }
        }

        // The index is the guard the service-layer read-then-write check cannot be, so it has to
        // refuse the duplicate that check would have let through.
        assertThatThrownBy(() -> jdbc.update("""
                insert into payments (id, version, created_at, updated_at, order_id, status, provider,
                                      amount, idempotency_key)
                values (?, 0, now(), now(), ?, 'PENDING', 'MPESA', 100.00, 'idem-after-migration')
                """, UUID.randomUUID(), ORDER_WITH_ONE_PENDING))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_payments_one_pending_per_order");
    }

    @Test
    @DisplayName("re-running the script changes nothing and does not error")
    void reRunningTheScriptIsIdempotent() throws Exception {
        seedDirtyDatabase();
        runMigration();
        List<Map<String, Object>> afterFirstRun = paymentColumns();
        Map<UUID, List<Seeded>> seedBefore = groupedByOrder();

        runMigration();
        runMigration();

        assertThat(paymentColumns())
                .as("a second and third run must be no-ops, not a second reconciliation pass")
                .isEqualTo(afterFirstRun);
        for (Seeded payment : seedBefore.get(ORDER_WITH_TWO_PENDING)) {
            assertThat(statusOf(payment.id()))
                    .as("the survivor rule is a max, not an increment: re-running must not fail another row")
                    .isIn("PENDING", "FAILED");
        }
    }

    @Test
    @DisplayName("already-clean data is left exactly as it was")
    void cleanDatabaseIsUntouched() throws Exception {
        seedCleanDatabase();
        List<Map<String, Object>> before = paymentColumns();

        runMigration();

        assertThat(paymentColumns()).isEqualTo(before);
        assertThat(pendingId(ORDER_WITH_ONE_PENDING))
                .as("an order with a single PENDING keeps it")
                .isNotNull();
        assertThat(pendingId(ORDER_PENDING_BESIDE_HISTORY))
                .as("a PENDING older than its VERIFIED sibling is still live and must be kept")
                .isNotNull();
    }

    private void assertIndexExistsAndIsPartialOnPending() {
        List<Map<String, Object>> index = jdbc.queryForList("""
                select i.indisunique as is_unique,
                       pg_get_expr(i.indpred, i.indrelid) as predicate
                from pg_index i
                join pg_class c on c.oid = i.indexrelid
                where c.relname = 'uq_payments_one_pending_per_order'
                """);
        assertThat(index).as("V11 must leave the partial unique index behind").hasSize(1);
        assertThat(index.get(0).get("is_unique")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(index.get(0).get("predicate")))
                .as("a full unique index on order_id would forbid legitimate payment history")
                .contains("status")
                .contains("PENDING");
    }

    /** Sorts ascending, so the last element of a PENDING group is the row that must survive. */
    private static Comparator<Seeded> survivorOrder() {
        return Comparator.comparing(Seeded::createdAt).thenComparing(Seeded::id);
    }

    private void seed(UUID orderId, String status, int minutesAfterT0) {
        seed(orderId, status, minutesAfterT0, UUID.randomUUID());
    }

    private void seed(UUID orderId, String status, int minutesAfterT0, UUID explicitId) {
        Instant createdAt = T0.plus(minutesAfterT0, ChronoUnit.MINUTES);
        seeded.add(new Seeded(explicitId, orderId, status, createdAt, explicitId.toString()));
    }

    private Map<UUID, List<Seeded>> groupedByOrder() {
        Map<UUID, List<Seeded>> grouped = new LinkedHashMap<>();
        for (Seeded payment : seeded) {
            grouped.computeIfAbsent(payment.orderId(), key -> new ArrayList<>()).add(payment);
        }
        return grouped;
    }

    /** Every column the reconciliation must never alter, for the whole table, id-ordered. */
    private List<Map<String, Object>> paymentColumns() {
        return jdbc.queryForList("""
                select id, status, created_at, updated_at, version
                from payments order by id
                """);
    }

    private Map<String, Object> paymentColumns(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select status, created_at, updated_at, version
                from payments where id = ?
                """, id);
        assertThat(rows).as("payment %s exists", id).hasSize(1);
        return rows.get(0);
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject(
                "select status from payments where id = ?", String.class, id);
    }

    private int pendingCount(UUID orderId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from payments where order_id = ? and status = 'PENDING'",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private UUID pendingId(UUID orderId) {
        List<UUID> pending = jdbc.query(
                "select id from payments where order_id = ? and status = 'PENDING'",
                (resultSet, row) -> uuid(resultSet.getString("id")),
                orderId);
        assertThat(pending)
                .as("order %s must end with at most one PENDING payment", orderId)
                .hasSizeLessThanOrEqualTo(1);
        return pending.isEmpty() ? null : pending.get(0);
    }

    /**
     * Runs the shipped migration file, verbatim, as one script.
     *
     * <p>Sent as a single statement so Postgres parses it — dollar quoting and all — instead of a
     * client-side {@code ;} splitter. That is what makes this a test of the file rather than of
     * a reconstruction of it.
     */
    private void runMigration() throws SQLException, IOException {
        try (Connection connection = DriverManager.getConnection(
                dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute(readMigration());
        }
    }

    private static String readMigration() throws IOException {
        try (InputStream stream = PendingPaymentIndexMigrationTest.class.getResourceAsStream(MIGRATION_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(MIGRATION_RESOURCE + " is not on the test classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    private record Seeded(UUID id, UUID orderId, String status, Instant createdAt, String label) {
    }
}
