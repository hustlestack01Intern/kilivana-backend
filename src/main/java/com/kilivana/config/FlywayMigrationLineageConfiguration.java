package com.kilivana.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs {@link FlywayMigrationLineageGuard} before Flyway migrates, not after.
 *
 * <p>Ordering is the whole reason this is a {@link FlywayMigrationStrategy} rather than an
 * {@code ApplicationRunner}, a {@code @PostConstruct}, or another argument to
 * {@link DeploymentEnvironmentValidator}. {@code FlywayMigrationInitializer} calls the strategy
 * in place of {@code flyway.migrate()}, so a strategy bean is the last code that runs before
 * validation and the check therefore gets to speak first. An {@code ApplicationRunner} would run
 * after the context had already failed to refresh, which means its message would never appear and
 * the operator would get Flyway's checksum error alone — the message this class exists to replace.
 *
 * <p>Not conditional on the {@code prod} profile, unlike
 * {@link DeploymentEnvironmentValidatorConfiguration}. That check guards against a
 * misconfiguration that only a deployed environment can have; this one guards against a wrong
 * database, and a developer running the suite locally against a restored copy of production data
 * is exactly the case where silently migrating the wrong lineage would do damage. It costs one
 * indexed lookup per boot on a table Flyway is about to read anyway.
 */
@Configuration(proxyBeanMethods = false)
public class FlywayMigrationLineageConfiguration {

    @Bean
    public FlywayMigrationStrategy flywayMigrationLineageGuardStrategy(DataSource dataSource) {
        FlywayMigrationLineageGuard guard = new FlywayMigrationLineageGuard();
        return flyway -> {
            guard.verify(dataSource);
            flyway.migrate();
        };
    }
}