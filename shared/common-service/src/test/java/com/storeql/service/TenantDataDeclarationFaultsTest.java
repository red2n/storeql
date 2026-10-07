package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.TenantDataCatalog.RawColumn;
import com.storeql.web.ApiException;
import jakarta.enterprise.inject.Instance;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a service that cannot say what tenant data it holds does when asked for it: it serves none.
 * A service that ships the endpoints without declaring a {@code TenantDataSpec} is refused as not
 * declared; one whose schema holds a table the spec does not account for is refused as incomplete,
 * for every table and not only the odd one, so a file is never served as the whole of a tenant's
 * data while a table of it is missing. Both are faults of the deployment, not of the caller, and
 * both are refused before the database is asked anything: the stubbed connection here is never
 * opened.
 */
class TenantDataDeclarationFaultsTest {

  private static final UUID TENANT = Ids.newId();

  /** A shop's schema, declaring nothing beyond its name. */
  private static final class Shop extends TenantDataSpec {
    @Override
    public String schema() {
      return "shop";
    }
  }

  private final AtomicInteger connections = new AtomicInteger();
  private TenantDataRepository repo;

  @BeforeEach
  void aRepositoryWhoseDatabaseMustNotBeAsked() throws Exception {
    repo = new TenantDataRepository();
    Field f = BaseJdbcRepository.class.getDeclaredField("dataSource");
    f.setAccessible(true);
    f.set(
        repo,
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
              if ("getConnection".equals(method.getName())) {
                connections.incrementAndGet();
                throw new SQLException("the database must not be asked");
              }
              return null;
            }));
  }

  private static Object defaultOf(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    return null;
  }

  /** An {@code Instance} with nothing behind it: no spec bean in the application. */
  @SuppressWarnings("unchecked")
  private static Instance<TenantDataSpec> noSpec() {
    return (Instance<TenantDataSpec>)
        Proxy.newProxyInstance(
            Thread.currentThread().getContextClassLoader(),
            new Class<?>[] {Instance.class},
            (proxy, method, args) ->
                "isUnsatisfied".equals(method.getName())
                    ? Boolean.TRUE
                    : defaultOf(method.getReturnType()));
  }

  private void catalogIs(TenantDataCatalog catalog) throws Exception {
    Field f = TenantDataRepository.class.getDeclaredField("catalog");
    f.setAccessible(true);
    f.set(repo, catalog);
  }

  private static ApiException refused(org.junit.jupiter.api.function.Executable call) {
    return assertThrows(ApiException.class, call);
  }

  @Test
  @DisplayName("A service with no declaration serves no tenant data, whichever way it is asked")
  void aServiceThatDeclaresNoSpecServesNone() {
    repo.specs = noSpec();
    JsonArray none = Json.createArrayBuilder().build();
    List<org.junit.jupiter.api.function.Executable> asks =
        List.of(
            () -> repo.catalog(),
            () -> repo.manifest(TENANT),
            () -> repo.page(TENANT, "orders", null, 10),
            () -> repo.importPage(TENANT, "orders", none),
            () -> repo.erase(TENANT, counts -> null));
    for (var ask : asks) {
      ApiException e = refused(ask);
      assertEquals(501, e.status());
      assertEquals("TENANT_DATA_NOT_DECLARED", e.code());
    }
    assertEquals(0, connections.get(), "no statement was prepared, nothing was read or erased");
    // Still refused the next time: the absence is not remembered as an empty catalog.
    assertEquals("TENANT_DATA_NOT_DECLARED", refused(() -> repo.manifest(TENANT)).code());
  }

  @Test
  @DisplayName("A schema with a table nothing accounts for serves no tenant data, the whole of it")
  void aSchemaWithATableNothingAccountsForServesNone() throws Exception {
    List<RawColumn> columns = new ArrayList<>();
    for (String c : List.of("id", "tenant_id", "total")) {
      columns.add(new RawColumn("orders", c, "text", false));
    }
    // No tenant_id, no predicate that ties it to one, and not left out: whose rows are these?
    for (String c : List.of("id", "name")) {
      columns.add(new RawColumn("things", c, "text", false));
    }
    TenantDataCatalog catalog =
        TenantDataCatalog.build(
            new Shop(),
            columns,
            Map.of("orders", List.of("id"), "things", List.of("id")),
            List.of());
    assertFalse(catalog.problems().isEmpty(), "the fixture schema has a problem");
    catalogIs(catalog);
    assertSame(catalog, repo.catalog(), "the catalog itself is readable, problems and all");

    JsonArray none = Json.createArrayBuilder().build();
    List<org.junit.jupiter.api.function.Executable> asks =
        List.of(
            () -> repo.manifest(TENANT),
            // Not only the odd table: the complete one is refused too, so nothing is served as
            // whole.
            () -> repo.page(TENANT, "orders", null, 10),
            () -> repo.page(TENANT, "things", null, 10),
            () -> repo.importPage(TENANT, "orders", none),
            () -> repo.erase(TENANT, counts -> null));
    for (var ask : asks) {
      ApiException e = refused(ask);
      assertEquals(500, e.status());
      assertEquals("TENANT_DATA_CATALOG_INCOMPLETE", e.code());
      assertTrue(
          e.details().stream()
              .anyMatch(
                  d ->
                      d.equals(
                          "things: has no tenant_id and no tenant predicate: exclude it or tie it"
                              + " to a tenant")),
          "names the table and why: " + e.details());
    }
    assertEquals(0, connections.get(), "no statement was prepared, nothing was read or erased");
  }
}
