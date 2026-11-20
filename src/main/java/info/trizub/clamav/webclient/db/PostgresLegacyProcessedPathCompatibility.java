package info.trizub.clamav.webclient.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Repairs one historical PostgreSQL schema shape before versioned Flyway migrations run.
 *
 * <p>Some pre-Flyway/legacy installations created {@code processed_files.idx_processed_path} as a
 * UNIQUE constraint. PostgreSQL implements that constraint with a backing index of the same name,
 * so V9 cannot remove it with {@code DROP INDEX}. On the first startup that sees this exact legacy
 * shape, convert the constraint to the equivalent standalone unique index expected by V9.
 */
@Component
public final class PostgresLegacyProcessedPathCompatibility implements Callback {
  private static final Logger log =
      LoggerFactory.getLogger(PostgresLegacyProcessedPathCompatibility.class);

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_MIGRATE;
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public void handle(Event event, Context context) {
    Connection connection = context.getConnection();
    try {
      if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
        return;
      }

      LegacyConstraint legacy = findLegacyConstraint(connection);
      if (legacy == null) {
        log.debug("PostgreSQL legacy processed_files index compatibility check: no repair needed.");
        return;
      }

      if (!"UNIQUE (path)".equals(legacy.definition())) {
        throw new FlywayException(
            "Refusing to rewrite unexpected PostgreSQL constraint "
                + legacy.schema()
                + ".processed_files.idx_processed_path: "
                + legacy.definition());
      }

      String schema = quoteIdentifier(connection, legacy.schema());
      String table = schema + "." + quoteIdentifier(connection, "processed_files");
      String constraint = quoteIdentifier(connection, "idx_processed_path");

      log.warn(
          "Detected legacy PostgreSQL UNIQUE constraint {}.processed_files.idx_processed_path; "
              + "converting it to a standalone unique index before Flyway V9.",
          legacy.schema());

      try (Statement statement = connection.createStatement()) {
        statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + constraint);
        statement.execute(
            "CREATE UNIQUE INDEX "
                + constraint
                + " ON "
                + table
                + " ("
                + quoteIdentifier(connection, "path")
                + ")");
      }

      log.info(
          "PostgreSQL legacy processed_files compatibility repair completed; Flyway migrations may continue.");
    } catch (SQLException failure) {
      throw new FlywayException(
          "Could not check or repair legacy PostgreSQL processed_files index compatibility",
          failure);
    }
  }

  private static LegacyConstraint findLegacyConstraint(Connection connection) throws SQLException {
    String sql =
        """
        SELECT n.nspname, pg_get_constraintdef(c.oid)
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = current_schema()
           AND t.relname = 'processed_files'
           AND c.conname = 'idx_processed_path'
           AND c.contype = 'u'
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return null;
      }
      LegacyConstraint result = new LegacyConstraint(rows.getString(1), rows.getString(2));
      if (rows.next()) {
        throw new FlywayException(
            "Multiple legacy PostgreSQL constraints named processed_files.idx_processed_path were found");
      }
      return result;
    }
  }

  private static String quoteIdentifier(Connection connection, String identifier)
      throws SQLException {
    String quote = connection.getMetaData().getIdentifierQuoteString();
    if (quote == null || quote.isBlank()) {
      throw new SQLException("Database does not expose an identifier quote character");
    }
    return quote + identifier.replace(quote, quote + quote) + quote;
  }

  private record LegacyConstraint(String schema, String definition) {}

  @Override
  public String getCallbackName() {
    return "PostgreSQL legacy processed_files index compatibility";
  }
}
