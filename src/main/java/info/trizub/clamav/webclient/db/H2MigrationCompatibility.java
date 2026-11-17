package info.trizub.clamav.webclient.db;

import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

/** H2's PostgreSQL mode cannot parse VARCHAR_IGNORECASE during ALTER TABLE's internal copy. */
@Component
public final class H2MigrationCompatibility implements Callback {
  private final ThreadLocal<Boolean> restorePostgresqlMode = new ThreadLocal<>();

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.BEFORE_EACH_MIGRATE
        || event == Event.AFTER_EACH_MIGRATE
        || event == Event.AFTER_EACH_MIGRATE_ERROR;
  }

  @Override
  public boolean canHandleInTransaction(Event event, Context context) {
    return true;
  }

  @Override
  public void handle(Event event, Context context) {
    Connection connection = context.getConnection();
    try {
      if (event == Event.BEFORE_EACH_MIGRATE) {
        if ("H2".equals(connection.getMetaData().getDatabaseProductName())
            && usesCaseInsensitivePostgresqlMode(connection)) {
          setMode(connection, "REGULAR");
          restorePostgresqlMode.set(true);
        }
      } else if (Boolean.TRUE.equals(restorePostgresqlMode.get())) {
        try {
          setMode(connection, "PostgreSQL");
        } finally {
          restorePostgresqlMode.remove();
        }
      }
    } catch (SQLException failure) {
      throw new FlywayException(
          "Could not manage H2 compatibility mode for schema migration", failure);
    }
  }

  private static boolean usesCaseInsensitivePostgresqlMode(Connection connection)
      throws SQLException {
    boolean postgresql = false;
    boolean ignoreCase = false;
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                """
                        SELECT SETTING_NAME, SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS
                        WHERE SETTING_NAME IN ('MODE', 'IGNORECASE')
                        """)) {
      while (rows.next()) {
        if ("MODE".equals(rows.getString(1))) {
          postgresql = "PostgreSQL".equalsIgnoreCase(rows.getString(2));
        } else {
          String value = rows.getString(2);
          ignoreCase = "1".equals(value) || Boolean.parseBoolean(value);
        }
      }
    }
    return postgresql && ignoreCase;
  }

  private static void setMode(Connection connection, String mode) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute("SET MODE " + mode);
    }
  }

  @Override
  public String getCallbackName() {
    return "H2 compatibility mode for case-insensitive migrations";
  }
}
