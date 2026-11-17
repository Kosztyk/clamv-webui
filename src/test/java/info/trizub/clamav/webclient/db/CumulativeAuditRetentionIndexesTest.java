package info.trizub.clamav.webclient.db;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V7 retention indexes must exist on fresh and Flyway-baselined H2 databases. */
class CumulativeAuditRetentionIndexesTest {
  private String url() {
    return "jdbc:h2:mem:retention_"
        + UUID.randomUUID().toString().replace("-", "")
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  }

  @Test
  void freshInstallIncludesRetentionIndexPrefixes() throws Exception {
    String jdbc = url();
    Phase2MigrationFixture.fresh(jdbc, "sa", "", "h2");
    try (var connection = DriverManager.getConnection(jdbc, "sa", "")) {
      Phase2MigrationFixture.assertIndexPrefix(
          connection, "scan_jobs", "status", "finished_at", "id");
      Phase2MigrationFixture.assertIndexPrefix(connection, "audit_events", "at", "id");
    }
  }

  @Test
  void legacyBaselineAlsoAppliesV7WithoutRewritingRows() throws Exception {
    String jdbc = url();
    Phase2MigrationFixture.legacy(jdbc, "sa", "", "h2");
    try (var connection = DriverManager.getConnection(jdbc, "sa", "")) {
      Phase2MigrationFixture.assertIndexPrefix(
          connection, "scan_jobs", "status", "finished_at", "id");
      Phase2MigrationFixture.assertIndexPrefix(connection, "audit_events", "at", "id");
      Phase2MigrationFixture.verifyRetained(connection);
    }
  }
}
