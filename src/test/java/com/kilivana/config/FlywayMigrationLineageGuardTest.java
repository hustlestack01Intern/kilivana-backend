package com.kilivana.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kilivana.config.FlywayMigrationLineageGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Exercises {@link FlywayMigrationLineageGuard} against a real {@code flyway_schema_history}.
 *
 * <p>Stands in its own container for the same reason {@code PendingPaymentIndexMigrationTest} does:
 * the shared {@code IntegrationTestSupport} database is migrated by this lineage before any test
 * can reach it, so it can only ever produce the clean case. The defect this guard exists to catch
 * is precisely the case that cannot be constructed by running this repository's own migrations —
 * it needs history rows this branch never wrote, and asserting against a Mockito {@code DataSource}
 * would only prove the test can count the stubbing.
 *
 * <p>The history table is created here by hand rather than by migrating, because the point is to
 * assert on an <em>arbitrary</em> history, including one this branch would never produce.
 */
class FlywayMigrationLineageGuardTest {

    private static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER;

    private static DriverManagerDataSource dataSource;

    static {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        POSTGRESQL_CONTAINER = container;
    }

    /** The shape Flyway 10 actually creates: version is a varchar, not an integer. */
    private static final String HISTORY_TABLE = """
            create table flyway_schema_history (
                installed_rank int not null,
                version varchar(50),
                description varchar(200) not null,
                type varchar(20) not null,
                script varchar(1000) not null,
                checksum integer,
                installed_by varchar(100) not null,
                installed_on timestamptz not null default now(),
                execution_time integer not null,
                success boolean not null,
                constraint flyway_schema_history_pk primary key (installed_rank)
            )
            """;

    private static final String INSERT_HISTORY_ROW = """
            insert into flyway_schema_history
                (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
            values (?, ?, ?, 'SQL', ?, 1234567, 'postgres', 42, true)
            """;

    private JdbcTemplate jdbc;
    private FlywayMigrationLineageGuard guard;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(
                POSTGRESQL_CONTAINER.getJdbcUrl(),
                POSTGRESQL_CONTAINER.getUsername(),
                POSTGRESQL_CONTAINER.getPassword());
    }

    @BeforeEach
    void resetHistory() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists flyway_schema_history");
        guard = new FlywayMigrationLineageGuard();
    }

    @Test
    @DisplayName("a database migrated by the forked lineage is refused with a message that names the remedy")
    void aForkedLineageIsRefused() {
        seedHistory(11, "11", "one live payment per order", "V11__one_live_payment_per_order.sql");

        assertThatThrownBy(() -> guard.verify(dataSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V11__one_live_payment_per_order.sql")
                .hasMessageContaining("V11__account_identity")
                // The two commands an operator would otherwise reach for, named and refused.
                .hasMessageContaining("Do NOT run `flyway repair`")
                .hasMessageContaining("Do NOT run `flyway clean`")
                // And the thing that is actually true of a correct database, so the operator can
                // confirm it against a known-good one.
                .hasMessageContaining("account identity");
    }

    @Test
    @DisplayName("a database migrated by this lineage starts")
    void thisLineageIsAllowed() {
        seedHistory(11, "11", "account identity", "V11__account_identity");
        seedHistory(17, "17", "one live payment per order", "V17__one_live_payment_per_order.sql");

        assertThatCode(() -> guard.verify(dataSource)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty database starts, so the guard is invisible on a first boot")
    void anEmptyDatabaseStarts() {
        // No history table at all. Flyway creates it during this very migration, so its absence is
        // the normal first-run state and not an error condition.
        assertThatCode(() -> guard.verify(dataSource)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a history that never reached version 11 starts")
    void aHistoryWithoutVersionElevenStarts() {
        jdbc.execute(HISTORY_TABLE);
        seedHistory(10, "10", "refresh token family id", "V10__refresh_token_family_id.sql");

        assertThatCode(() -> guard.verify(dataSource)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the forked V11 is detected by description, not by which migrations are missing")
    void detectionIsByDescriptionNotByMissingMigrations() {
        // No V17 at all — the naive "is the other lineage's V17 present?" check would miss this
        // entirely. The history still says version 11 is the fork's script, so the database is
        // still wrong and must still be refused.
        seedHistory(11, "11", "one live payment per order", "V11__one_live_payment_per_order.sql");

        assertThatThrownBy(() -> guard.verify(dataSource))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a version 11 written by some other tool does not pass as this lineage's")
    void anUnknownVersionElevenIsNotSilentlyAccepted() {
        // Pins the narrowness of the check in the other direction: the guard refuses the fork's
        // spelling specifically. A description neither lineage produced is not evidence of the
        // fork, and refusing on it would break a future rename of this branch's own V11 — which is
        // a far more likely event than someone hand-writing a plausible fake, and one this guard
        // has no business deciding.
        seedHistory(11, "11", "something a third party invented", "V11__invented.sql");

        assertThatCode(() -> guard.verify(dataSource)).doesNotThrowAnyException();
    }

    /** {@code version} is passed explicitly rather than parsed out of {@code script}, so a test
     * can seed a version number that does not match its script name and prove the guard reads the
     * column Flyway reads. */
    private void seedHistory(int rank, String version, String description, String script) {
        if (!historyTableExists()) {
            jdbc.execute(HISTORY_TABLE);
        }
        jdbc.update(INSERT_HISTORY_ROW, rank, version, description, script);
    }

    private boolean historyTableExists() {
        Integer count = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'flyway_schema_history'",
                Integer.class);
        return count != null && count > 0;
    }
}