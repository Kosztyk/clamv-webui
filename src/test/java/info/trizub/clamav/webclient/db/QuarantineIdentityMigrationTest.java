package info.trizub.clamav.webclient.db;

import static org.assertj.core.api.Assertions.*;

import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V11 must apply on both fresh and legacy-upgraded installations. */
class QuarantineIdentityMigrationTest {
  @Test
  void freshAndUpgradedH2SchemasHaveNullableContentEvidence() throws Exception {
    for (boolean legacy : new boolean[] {false, true}) {
      String url =
          "jdbc:h2:mem:quarantine_evidence_"
              + UUID.randomUUID().toString().replace("-", "")
              + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
      if (legacy) Phase2MigrationFixture.legacy(url, "sa", "", "h2");
      else Phase2MigrationFixture.fresh(url, "sa", "", "h2");
      try (var connection = DriverManager.getConnection(url, "sa", "")) {
        var metadata = connection.getMetaData();
        for (String column : new String[] {"SOURCE_SHA256", "SOURCE_SIZE_BYTES", "FAILURE_CODE"}) {
          try (var cols = metadata.getColumns(null, null, "QUARANTINE_ARTIFACTS", column)) {
            assertThat(cols.next()).as("V11 quarantine journal column " + column).isTrue();
            assertThat(cols.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
          }
        }
        try (var cols = metadata.getColumns(null, null, "SCAN_JOBS", "QUARANTINE_REQUESTED")) {
          assertThat(cols.next()).as("V11 persisted quarantine intent").isTrue();
          assertThat(cols.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
        }
      }
    }
  }
}
