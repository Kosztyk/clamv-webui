package info.trizub.clamav.webclient.db;

import static org.assertj.core.api.Assertions.*;

import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V12 must support fresh installation and upgrades from pre-Flyway history. */
class WatcherRetryMigrationTest {
  @Test
  void freshAndUpgradedH2HaveDurableWatcherRetryOwnership() throws Exception {
    for (boolean legacy : new boolean[] {false, true}) {
      String url =
          "jdbc:h2:mem:watch_retry_"
              + UUID.randomUUID().toString().replace("-", "")
              + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
      if (legacy) Phase2MigrationFixture.legacy(url, "sa", "", "h2");
      else Phase2MigrationFixture.fresh(url, "sa", "", "h2");
      try (var connection = DriverManager.getConnection(url, "sa", "")) {
        for (String name : new String[] {"LAST_SCAN_JOB_ID", "RETRY_FAILURES", "RETRY_AFTER"}) {
          try (var columns =
              connection.getMetaData().getColumns(null, null, "PROCESSED_FILES", name)) {
            assertThat(columns.next()).as("V12 column " + name).isTrue();
            assertThat(columns.getInt("NULLABLE"))
                .isEqualTo(
                    name.equals("RETRY_FAILURES")
                        ? DatabaseMetaData.columnNoNulls
                        : DatabaseMetaData.columnNullable);
          }
        }
      }
    }
  }
}
