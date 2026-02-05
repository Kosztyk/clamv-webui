package info.trizub.clamav.webclient.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Locale;

/**
 * One-time safety migration for older Postgres schemas where String @Lob columns
 * were created as OID Large Objects (LO). Those fail at runtime with:
 * "Large Objects may not be used in auto-commit mode."
 *
 * This migration converts OID columns into TEXT columns for scan_jobs:
 *  - found_viruses_json
 *  - error_message
 *
 * If the columns are already TEXT (or the database is not Postgres), it does nothing.
 *
 * NOTE: For very large tables this can take time; it runs once at startup.
 */
@Component
public class PostgresLobMigration {

    private static final Logger log = LoggerFactory.getLogger(PostgresLobMigration.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public PostgresLobMigration(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateIfNeeded() {
        if (!isPostgres()) {
            return;
        }

        try {
            boolean foundOid = isColumnOid("scan_jobs", "found_viruses_json");
            boolean errorOid = isColumnOid("scan_jobs", "error_message");

            if (!foundOid && !errorOid) {
                return;
            }

            log.warn("Detected Postgres OID/LO columns in scan_jobs. Running in-place migration to TEXT...");

            // Run everything in a single transaction (autocommit OFF) to keep PG LO operations happy.
            try (Connection c = dataSource.getConnection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    // Create temp TEXT columns (only if needed)
                    if (foundOid) {
                        exec(c, "ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS found_viruses_json_text TEXT");
                        exec(c,
                            "UPDATE scan_jobs SET found_viruses_json_text = " +
                            "CASE WHEN found_viruses_json IS NULL THEN NULL " +
                            "ELSE convert_from(lo_get(found_viruses_json), 'UTF8') END"
                        );
                    }
                    if (errorOid) {
                        exec(c, "ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS error_message_text TEXT");
                        exec(c,
                            "UPDATE scan_jobs SET error_message_text = " +
                            "CASE WHEN error_message IS NULL THEN NULL " +
                            "ELSE convert_from(lo_get(error_message), 'UTF8') END"
                        );
                    }

                    // Drop/rename
                    if (foundOid) {
                        exec(c, "ALTER TABLE scan_jobs DROP COLUMN found_viruses_json");
                        exec(c, "ALTER TABLE scan_jobs RENAME COLUMN found_viruses_json_text TO found_viruses_json");
                    }
                    if (errorOid) {
                        exec(c, "ALTER TABLE scan_jobs DROP COLUMN error_message");
                        exec(c, "ALTER TABLE scan_jobs RENAME COLUMN error_message_text TO error_message");
                    }

                    c.commit();
                    log.warn("Postgres LOB migration completed successfully.");
                } catch (Exception e) {
                    try { c.rollback(); } catch (Exception ignore) {}
                    throw e;
                } finally {
                    try { c.setAutoCommit(oldAuto); } catch (Exception ignore) {}
                }
            }
        } catch (Exception e) {
            // Don't crash the whole app; log clearly so the operator can run the SQL manually.
            log.error("Postgres LOB migration failed. If you have an existing DB created by older versions, " +
                      "run the migration SQL manually (see README). Error: {}", e.getMessage(), e);
        }
    }

    private void exec(Connection c, String sql) throws Exception {
        try (var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private boolean isPostgres() {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String name = md.getDatabaseProductName();
            return name != null && name.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            log.warn("Unable to determine database type: {}", e.getMessage());
            return false;
        }
    }

    private boolean isColumnOid(String table, String column) {
        try {
            String sql =
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?";
            String dt = jdbc.query(sql, rs -> rs.next() ? rs.getString(1) : null, table, column);
            return dt != null && dt.equalsIgnoreCase("oid");
        } catch (Exception e) {
            log.warn("Unable to inspect column type for {}.{}: {}", table, column, e.getMessage());
            return false;
        }
    }
}
