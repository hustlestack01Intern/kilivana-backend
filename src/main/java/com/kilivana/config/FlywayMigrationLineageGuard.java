package com.kilivana.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses to start on a database whose Flyway history belongs to a different branch lineage.
 *
 * <p>This repository was forked from {@code 9379853}. That lineage's {@code V11} is
 * {@code V11__one_live_payment_per_order.sql}, and this lineage's {@code V11} is the Java
 * migration {@code V11__account_identity} — different contents, same version number. A database
 * migrated by the fork therefore records version 11 as "one live payment per order" and has no
 * V17, while a database migrated by this branch records version 11 as "account identity" and does
 * have V17. Both look superficially healthy to Flyway, which keys on version numbers alone.
 *
 * <p>What Flyway does with that is a checksum failure on V11 (the two scripts hash differently),
 * phrased as "Detected resolved migration not applied to database: 11. one live payment per
 * order". That is accurate and close to useless: it names a migration that has in fact been
 * applied, and it invites the two commands an operator reaches for — {@code flyway repair}, which
 * rewrites the checksum to match the file and lets a branch that never ran here proceed as though
 * it had, or {@code flyway clean}, which drops the schema. This guard exists to say what is
 * actually wrong, before Flyway's message does, and to say what to do instead.
 *
 * <p>The detection is by content, not by absence: a row claiming version 11 is the one migration
 * the two lineages spell differently. Checking that the other lineage's V17 is missing as well
 * would be redundant confirmation of the same fact, and would misfire on a database mid-adoption
 * where an operator has deliberately copied a migration in by hand.
 *
 * <p>Deliberately a startup check that runs <em>before</em> {@code Flyway.validate}, so the
 * actionable message wins over the checksum error rather than replacing it. See
 * {@link FlywayMigrationLineageConfiguration}.
 */
public class FlywayMigrationLineageGuard {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationLineageGuard.class);

    /**
     * The version both lineages claim, as Flyway stores it: the {@code version} column is a
     * varchar, so this is compared as text and never as an integer.
     */
    private static final String FORKED_VERSION = "11";

    /**
     * Flyway stores the description from the filename with underscores turned into spaces, which
     * is why the expected text is the file's name in prose and not its name.
     */
    private static final String FORKED_DESCRIPTION = "one live payment per order";

    /**
     * The version this lineage's own V11 records. Checked so the guard only ever fires on the
     * fork's spelling, and never on a correctly migrated database whose V11 description differs
     * from {@link #FORKED_DESCRIPTION} for some future reason.
     */
    private static final String THIS_LINEAGE_DESCRIPTION = "account identity";

    private static final String QUERY = """
            select description
            from flyway_schema_history
            where version = ?
            order by installed_rank desc
            limit 1
            """;

    /**
     * @throws IllegalStateException with the operator-facing remediation, when the history table
     *     records the fork's V11 under version 11
     */
    public void verify(DataSource dataSource) {
        String description = findRecordedDescription(dataSource);
        if (FORKED_DESCRIPTION.equals(description)) {
            throw new IllegalStateException(refusalMessage());
        }
        log.debug("Flyway lineage check passed; version {} is recorded as '{}'", FORKED_VERSION, description);
    }

    /**
     * @return the description recorded for version 11, or {@code null} when there is no history
     *     table, no version 11, or the query cannot be answered
     */
    private String findRecordedDescription(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(QUERY)) {
            statement.setString(1, FORKED_VERSION);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("description") : null;
            }
        } catch (SQLException exception) {
            // A database that has never been migrated has no history table, and that is the
            // overwhelmingly common case on a first boot. Refusing here would make the guard
            // break exactly the startup it is supposed to be invisible to. A genuine permission
            // or connectivity problem is left to Flyway, which will fail on its own with a
            // message about the thing it was actually trying to do.
            log.debug("No readable flyway_schema_history; skipping the lineage check", exception);
            return null;
        }
    }

    private String refusalMessage() {
        return """
                This database was migrated by a different branch of KILIVANA and cannot be migrated by this one.

                Flyway's history records version 11 as '%s'. That is the other lineage's
                V11__one_live_payment_per_order.sql. This lineage's version 11 is the unrelated
                migration V11__account_identity, so the two share a version number with different
                contents, and Flyway's checksum comparison cannot tell you which is correct.

                Do NOT run `flyway repair`. Repair rewrites the recorded checksum to match the
                file present on disk, which would let this branch start on a database where its own
                V11 never ran, leaving the accounts.username column missing and every later
                migration failing in a less obvious way.
                Do NOT run `flyway clean`. It drops the schema and every row in it.

                Migrate a database that this lineage created, or point DB_URL at one of them. If the
                existing data must be kept, export it, migrate an empty database to this lineage,
                and import it — treating that as a data migration to be planned, not a repair to
                be applied. Expected on a database this lineage migrated: version 11 is recorded
                as '%s'."""
                .formatted(FORKED_DESCRIPTION, THIS_LINEAGE_DESCRIPTION);
    }
}