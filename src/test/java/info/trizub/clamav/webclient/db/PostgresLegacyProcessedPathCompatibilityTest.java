package info.trizub.clamav.webclient.db;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;

class PostgresLegacyProcessedPathCompatibilityTest {
  private final PostgresLegacyProcessedPathCompatibility callback =
      new PostgresLegacyProcessedPathCompatibility();

  @Test
  void ignoresNonPostgresqlDatabases() throws Exception {
    Context context = mock(Context.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    when(context.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("H2");

    callback.handle(Event.BEFORE_MIGRATE, context);

    verify(connection, never()).prepareStatement(anyString());
  }

  @Test
  void leavesNormalPostgresqlSchemaUntouched() throws Exception {
    Fixture fixture = postgresFixture(false, null);

    callback.handle(Event.BEFORE_MIGRATE, fixture.context());

    verify(fixture.connection(), never()).createStatement();
  }

  @Test
  void repairsExactLegacyUniqueConstraintBeforeV9() throws Exception {
    Fixture fixture = postgresFixture(true, "UNIQUE (path)");
    Statement ddl = mock(Statement.class);
    when(fixture.connection().createStatement()).thenReturn(ddl);

    callback.handle(Event.BEFORE_MIGRATE, fixture.context());

    verify(ddl).execute(contains("ALTER TABLE \"public\".\"processed_files\" DROP CONSTRAINT"));
    verify(ddl).execute(contains("CREATE UNIQUE INDEX \"idx_processed_path\""));
  }

  @Test
  void failsClosedForUnexpectedConstraintDefinition() throws Exception {
    Fixture fixture = postgresFixture(true, "UNIQUE (path, size_bytes)");

    assertThrows(
        FlywayException.class, () -> callback.handle(Event.BEFORE_MIGRATE, fixture.context()));

    verify(fixture.connection(), never()).createStatement();
  }

  private static Fixture postgresFixture(boolean constraintPresent, String definition)
      throws Exception {
    Context context = mock(Context.class);
    Connection connection = mock(Connection.class);
    DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    PreparedStatement query = mock(PreparedStatement.class);
    ResultSet rows = mock(ResultSet.class);

    when(context.getConnection()).thenReturn(connection);
    when(connection.getMetaData()).thenReturn(metadata);
    when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(metadata.getIdentifierQuoteString()).thenReturn("\"");
    when(connection.prepareStatement(anyString())).thenReturn(query);
    when(query.executeQuery()).thenReturn(rows);
    if (constraintPresent) {
      when(rows.next()).thenReturn(true, false);
      when(rows.getString(1)).thenReturn("public");
      when(rows.getString(2)).thenReturn(definition);
    } else {
      when(rows.next()).thenReturn(false);
    }
    return new Fixture(context, connection);
  }

  private record Fixture(Context context, Connection connection) {}
}
