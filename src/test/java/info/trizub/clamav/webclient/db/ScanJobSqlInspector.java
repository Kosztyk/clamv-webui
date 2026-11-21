package info.trizub.clamav.webclient.db;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/** Test-only SQL observer to assert the ORM does not fetch TEXT diagnostics. */
public final class ScanJobSqlInspector implements StatementInspector {
  private static final CopyOnWriteArrayList<String> SQL = new CopyOnWriteArrayList<>();

  public ScanJobSqlInspector() {}

  @Override
  public String inspect(String sql) {
    SQL.add(sql.toLowerCase(java.util.Locale.ROOT));
    return sql;
  }

  public static void reset() {
    SQL.clear();
  }

  public static List<String> queries() {
    return List.copyOf(SQL);
  }
}
