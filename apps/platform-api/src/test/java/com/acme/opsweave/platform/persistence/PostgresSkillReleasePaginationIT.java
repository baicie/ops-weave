package com.acme.opsweave.platform.persistence;

import com.acme.opsweave.aicontrol.api.SkillReleaseStore;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Real PostgreSQL parity checks, isolated by a fresh synthetic tenant for every test. */
@EnabledIfEnvironmentVariable(named = "OPSWEAVE_TEST_JDBC_URL", matches = ".+")
class PostgresSkillReleasePaginationIT extends SkillReleasePaginationContract {
    private static DriverManagerDataSource source;

    @BeforeAll
    static void migrate() {
        source = new DriverManagerDataSource(System.getenv("OPSWEAVE_TEST_JDBC_URL"),
            System.getenv("OPSWEAVE_TEST_JDBC_USER"), System.getenv("OPSWEAVE_TEST_JDBC_PASSWORD"));
        for (var migration : List.of("V002__host_sync", "V010__external_problem_incident", "V011__tool_read_evidence",
            "V073__skill_release_registry")) {
            new SchemaMigrator(source).apply("db/migration/" + migration + ".sql", migration);
        }
    }

    @Override SkillReleaseStore store() { return new PostgresSkillReleases(source); }
}
