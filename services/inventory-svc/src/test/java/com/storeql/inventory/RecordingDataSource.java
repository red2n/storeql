package com.storeql.inventory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;

/**
 * A data source that hands out the real connections and writes down every SQL string prepared on
 * them, so a test reads the statements a repository really runs instead of keeping a copy of them
 * that could drift from the code.
 */
public final class RecordingDataSource {

  private RecordingDataSource() {}

  /**
   * Wraps {@code real}: every connection it gives out records the SQL of each statement prepared on
   * it into {@code prepared}.
   *
   * @param real the data source to take connections from
   * @param prepared where each prepared statement's SQL is appended, in the order it is prepared
   *     (give it a thread-safe list)
   * @return a data source whose connections are the real ones, watched
   */
  public static DataSource of(DataSource real, List<String> prepared) {
    return (DataSource)
        Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              Object result = invoke(method, real, args);
              return "getConnection".equals(method.getName())
                  ? watched((Connection) result, prepared)
                  : result;
            });
  }

  private static Connection watched(Connection real, List<String> prepared) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              if ("prepareStatement".equals(method.getName())
                  && args != null
                  && args.length > 0
                  && args[0] instanceof String sql) {
                prepared.add(sql);
              }
              return invoke(method, real, args);
            });
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }
}
