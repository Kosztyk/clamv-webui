package info.trizub.clamav.webclient.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Issue #34: fresh and Flyway-baselined databases retain all rows and indexes. */
class Issue34IndexMigrationTest {
  private String url() {
    return "jdbc:h2:mem:issue34_"
        + UUID.randomUUID().toString().replace("-", "")
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  }

  @Test
  void freshInstallHasAllRequiredAccessPaths() throws Exception {
    String jdbc = url();
    Phase2MigrationFixture.fresh(jdbc, "sa", "", "h2");
    try (Connection c = DriverManager.getConnection(jdbc, "sa", "")) {
      Phase2MigrationFixture.verifyIssue34Indexes(c);
    }
  }

  @Test
  void legacyUpgradeAndRepeatMigrationKeepIndexesAndData() throws Exception {
    String jdbc = url();
    Phase2MigrationFixture.legacy(jdbc, "sa", "", "h2");
    try (Connection c = DriverManager.getConnection(jdbc, "sa", "")) {
      Phase2MigrationFixture.verifyIssue34Indexes(c);
      Phase2MigrationFixture.verifyRetained(c);
    }
  }
}
