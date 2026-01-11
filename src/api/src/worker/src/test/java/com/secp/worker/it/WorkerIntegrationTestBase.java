package com.secp.worker.it;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class WorkerIntegrationTestBase {

  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
      .withDatabaseName("secp")
      .withUsername("postgres")
      .withPassword("postgres");

  static {
    logDockerAvailabilityOnce();
    postgres.start();
  }

  @BeforeAll
  static void migrate() {
    // Reuse api module migrations via filesystem location.
    // Path is relative to worker module root: ../main/resources/db/migration
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations("filesystem:../main/resources/db/migration")
        .load()
        .migrate();
  }

  private static void logDockerAvailabilityOnce() {
    try {
      System.out.println("[IT] Testcontainers Docker available: " + DockerClientFactory.instance().isDockerAvailable());
    } catch (Exception e) {
      System.out.println("[IT] Testcontainers Docker availability check failed: " + e.getMessage());
    }
  }
}
