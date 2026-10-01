package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.TenantDataCatalog.RawColumn;
import com.storeql.service.TenantDataCatalog.Reference;
import com.storeql.web.ApiException;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The import's guard for a table with no {@code tenant_id} of its own, whose rows are the tenant's
 * only through a row they refer to (iam-svc's {@code user_roles}, tied to the tenant's logins).
 * There the importer cannot stamp the tenant on the row, so a row naming another business's login
 * would otherwise be written, its foreign key satisfied; the page is refused whole and the
 * transaction rolled back.
 *
 * <p>The database's answer is stubbed, so what is held here is the branch and its consequences: the
 * refusal and its code, that the transaction is rolled back and never committed, and that the
 * strangers query is the one the catalog's tenant predicate builds. A table with a {@code
 * tenant_id} never needs the guard, because the importer writes its own tenant on every row.
 */
class TenantDataImportGuardTest {

  private static final String OWN_USERS =
      "user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = ?)";

  /** What the stubbed connection saw, and what its queries answer. */
  private static final class Recorder {
    final List<String> statements = new ArrayList<>();
    int commits;
    int rollbacks;
    int inserted;
    long strangers;
  }

  private static final class Iam extends TenantDataSpec {
    @Override
    public String schema() {
      return "iam";
    }

    @Override
    public Map<String, String> tenantPredicates() {
      return Map.of("user_roles", OWN_USERS);
    }
  }

  private Recorder recorder;
  private TenantDataRepository repo;
  private final UUID tenant = Ids.newId();

  @BeforeEach
  void aRepositoryOverAStubbedDatabase() throws Exception {
    recorder = new Recorder();
    List<RawColumn> columns = new ArrayList<>();
    for (String c : List.of("id", "tenant_id", "email")) {
      columns.add(new RawColumn("users", c, "text", false));
    }
    for (String c : List.of("id", "user_id", "role_id")) {
      columns.add(new RawColumn("user_roles", c, "text", false));
    }
    TenantDataCatalog catalog =
        TenantDataCatalog.build(
            new Iam(),
            columns,
            Map.of("users", List.of("id"), "user_roles", List.of("id")),
            List.of(new Reference("user_roles", "users")));
    assertEquals(List.of(), catalog.problems(), "the fixture catalog is complete");

    repo = new TenantDataRepository();
    set(TenantDataRepository.class, "catalog", catalog);
    set(BaseJdbcRepository.class, "dataSource", dataSource(recorder));
  }

  private void set(Class<?> owner, String field, Object value) throws Exception {
    Field f = owner.getDeclaredField(field);
    f.setAccessible(true);
    f.set(repo, value);
  }

  private static Object defaultOf(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    return null;
  }

  private static DataSource dataSource(Recorder r) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    ResultSet resultSet =
        (ResultSet)
            Proxy.newProxyInstance(
                loader,
                new Class<?>[] {ResultSet.class},
                (proxy, method, args) ->
                    switch (method.getName()) {
                      case "next" -> true;
                      case "getLong" -> r.strangers;
                      default -> defaultOf(method.getReturnType());
                    });
    Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                loader,
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                  switch (method.getName()) {
                    case "prepareStatement" -> {
                      r.statements.add((String) args[0]);
                      return Proxy.newProxyInstance(
                          loader,
                          new Class<?>[] {PreparedStatement.class},
                          (p, m, a) ->
                              switch (m.getName()) {
                                case "executeUpdate" -> r.inserted;
                                case "executeQuery" -> resultSet;
                                default -> defaultOf(m.getReturnType());
                              });
                    }
                    case "commit" -> r.commits++;
                    case "rollback" -> r.rollbacks++;
                    default -> {
                      return defaultOf(method.getReturnType());
                    }
                  }
                  return null;
                });
    return (DataSource)
        Proxy.newProxyInstance(
            loader,
            new Class<?>[] {DataSource.class},
            (proxy, method, args) ->
                "getConnection".equals(method.getName())
                    ? connection
                    : defaultOf(method.getReturnType()));
  }

  private static JsonArray roles(int n) {
    JsonArrayBuilder rows = Json.createArrayBuilder();
    for (int i = 0; i < n; i++) {
      rows.add(
          Json.createObjectBuilder()
              .add("id", Ids.newId().toString())
              .add("user_id", Ids.newId().toString())
              .add("role_id", Ids.newId().toString()));
    }
    return rows.build();
  }

  @Test
  @DisplayName(
      "A row for another business's login is refused whole and the transaction rolled back")
  void aRoleForAnotherBusinesssLoginIsNotImported() {
    recorder.inserted = 1;
    recorder.strangers = 1;

    ApiException refused =
        assertThrows(ApiException.class, () -> repo.importPage(tenant, "user_roles", roles(1)));

    assertEquals(409, refused.status());
    assertEquals("TENANT_DATA_IMPORT_NOT_THIS_TENANT", refused.code());
    assertEquals(0, recorder.commits, "nothing is committed");
    assertEquals(1, recorder.rollbacks, "the insert is undone with the page");
    assertEquals(2, recorder.statements.size(), "the insert, then the strangers query");
    String strangers = recorder.statements.get(1);
    assertTrue(strangers.contains("NOT EXISTS"), strangers);
    assertTrue(strangers.contains(OWN_USERS), "judged by the tenant's own logins: " + strangers);
  }

  @Test
  @DisplayName("A page whose rows all belong to the tenant's own logins is committed")
  void rowsOfTheTenantsOwnLoginsAreImported() {
    recorder.inserted = 2;
    recorder.strangers = 0;

    TenantDataRepository.Imported done = repo.importPage(tenant, "user_roles", roles(2));

    assertEquals("user_roles", done.table());
    assertEquals(2, done.rows());
    assertEquals(1, recorder.commits);
    assertEquals(0, recorder.rollbacks);
  }

  @Test
  @DisplayName("A table with a tenant_id never asks, because the importer stamps its own tenant")
  void aTableWithATenantColumnIsNotChecked() {
    recorder.inserted = 1;
    recorder.strangers = 5;

    TenantDataRepository.Imported done = repo.importPage(tenant, "users", roles(1));

    assertEquals(1, done.rows());
    assertEquals(1, recorder.statements.size(), "the insert alone");
    assertEquals(1, recorder.commits);
  }

  @Test
  @DisplayName("A page that is not a page is refused before the database is touched")
  void aBadPageTouchesNothing() {
    ApiException unknown =
        assertThrows(ApiException.class, () -> repo.importPage(tenant, "nobody", roles(1)));
    assertEquals(404, unknown.status());
    assertEquals("TENANT_DATA_TABLE_UNKNOWN", unknown.code());

    ApiException tooLarge =
        assertThrows(
            ApiException.class,
            () -> repo.importPage(tenant, "user_roles", roles(TenantDataRepository.MAX_PAGE + 1)));
    assertEquals(400, tooLarge.status());
    assertEquals("TENANT_DATA_PAGE_TOO_LARGE", tooLarge.code());

    JsonArray notObjects = Json.createArrayBuilder().add("a row").add(7).build();
    ApiException invalid =
        assertThrows(ApiException.class, () -> repo.importPage(tenant, "user_roles", notObjects));
    assertEquals(400, invalid.status());
    assertEquals("TENANT_DATA_IMPORT_INVALID", invalid.code());

    assertEquals(List.of(), recorder.statements, "no statement was prepared");
    assertEquals(0, recorder.commits);
  }
}
