package info.trizub.clamav.webclient.db;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V10 persists original scan scope; legacy in-flight jobs remain NULL and fail closed. */
class ScanScopeMigrationTest {
  private String jdbc() {
    return "jdbc:h2:mem:scope_"
        + UUID.randomUUID().toString().replace("-", "")
        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
  }

  @Test
  void freshAndBaselinedSchemasHaveNullableScope() throws Exception {
    for (boolean legacy : new boolean[] {false, true}) {
      String url = jdbc();
      if (legacy) Phase2MigrationFixture.legacy(url, "sa", "", "h2");
      else Phase2MigrationFixture.fresh(url, "sa", "", "h2");
      try (var connection = DriverManager.getConnection(url, "sa", "")) {
        var columns =
            connection
                .getMetaData()
                .getColumns(null, null, "SCAN_JOBS", "SCAN_TARGET_WAS_DIRECTORY");
        assertThat(columns.next()).isTrue();
        assertThat(columns.getInt("NULLABLE")).isEqualTo(java.sql.DatabaseMetaData.columnNullable);
      }
    }
  }
}
