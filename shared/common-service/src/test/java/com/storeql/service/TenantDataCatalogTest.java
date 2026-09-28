package com.storeql.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.service.TenantDataCatalog.Erasable;
import com.storeql.service.TenantDataCatalog.RawColumn;
import com.storeql.service.TenantDataCatalog.Reference;
import com.storeql.service.TenantDataCatalog.Table;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TenantDataCatalogTest {

  private static final String OWN_USERS =
      "user_id IN (SELECT u.id FROM users u WHERE u.tenant_id = ?)";

  /** A shop: staff users, their roles and tokens, orders and lines, a secret, the outbox. */
  private static class Shop extends TenantDataSpec {
    @Override
    public String schema() {
      return "shop";
    }

    @Override
    public Map<String, String> excludedTables() {
      return Map.of("secrets", "credentials", "tokens", "login tokens");
    }

    @Override
    public Map<String, String> excludedColumns() {
      return Map.of("users.password_hash", "a credential");
    }

    @Override
    public Map<String, String> tenantPredicates() {
      return Map.of("user_roles", OWN_USERS);
    }

    @Override
    public Map<String, String> erasurePredicates() {
      return Map.of("tokens", OWN_USERS);
    }

    @Override
    public Map<String, String> importSkipped() {
      return Map.of("users", "logins are made at the destination");
    }

    @Override
    public Set<String> derivedTables() {
      return Set.of("order_items");
    }
  }

  private final List<RawColumn> columns = new ArrayList<>();
  private final Map<String, List<String>> keys = new HashMap<>();
  private final List<Reference> references = new ArrayList<>();

  private void table(String name, String... cols) {
    for (String c : cols) {
      columns.add(new RawColumn(name, c.replace("*", ""), "text", c.endsWith("*")));
    }
    keys.put(name, List.of("id"));
  }

  private void shop() {
    table("users", "id", "tenant_id", "email", "password_hash");
    table("user_roles", "id", "user_id", "role");
    table("tokens", "id", "user_id", "token");
    table("orders", "id", "tenant_id", "user_id", "parent_id", "total");
    table("order_items", "id", "tenant_id", "order_id", "qty", "net*");
    table("secrets", "id", "tenant_id", "value");
    table("outbox", "id", "tenant_id", "payload");
    table("allergens", "code", "name");
    keys.put("allergens", List.of("code"));
    references.add(new Reference("user_roles", "users"));
    references.add(new Reference("tokens", "users"));
    references.add(new Reference("orders", "users"));
    references.add(new Reference("orders", "orders"));
    references.add(new Reference("order_items", "orders"));
  }

  private TenantDataCatalog build(TenantDataSpec spec) {
    return TenantDataCatalog.build(spec, columns, keys, references);
  }

  @Test
  @DisplayName("Tables come after the tables they refer to, and are erased the other way round")
  void order() {
    shop();
    columns.removeIf(c -> c.table().equals("allergens"));
    keys.remove("allergens");
    TenantDataCatalog cat = build(new Shop());
    assertEquals(List.of(), cat.problems());
    assertEquals(
        List.of("users", "orders", "user_roles", "order_items"),
        cat.ordered().stream().map(Table::name).toList());
    assertEquals(Set.of("users"), cat.table("orders").orElseThrow().dependsOn());

    List<String> erased = cat.erasureOrder().stream().map(Erasable::name).toList();
    assertEquals(
        Set.of("users", "orders", "user_roles", "order_items", "secrets", "tokens"),
        Set.copyOf(erased));
    assertTrue(erased.indexOf("users") > erased.indexOf("orders"));
    assertTrue(erased.indexOf("users") > erased.indexOf("user_roles"));
    assertTrue(erased.indexOf("users") > erased.indexOf("tokens"));
    assertTrue(erased.indexOf("orders") > erased.indexOf("order_items"));
    assertFalse(erased.contains("outbox"), "delivery machinery is not the tenant's to erase");
    assertEquals(
        OWN_USERS,
        cat.erasureOrder().stream()
            .filter(e -> e.name().equals("tokens"))
            .findFirst()
            .orElseThrow()
            .predicate());
  }

  /**
   * iam-svc's sandbox pair is exported by the live business alone but erased with either business:
   * the erasure predicate an exported table declares is the one erasure uses. Ignored, it left the
   * pair behind when a sandbox was erased, and the deleted sandbox could be entered again (found by
   * k6 sandbox-flow).
   */
  @Test
  @DisplayName("An exported table's own erasure predicate is the one its erasure uses")
  void exportedTablesEraseByTheirOwnErasurePredicate() {
    shop();
    columns.removeIf(c -> c.table().equals("allergens"));
    keys.remove("allergens");
    table("sandboxes", "id", "live_tenant_id", "sandbox_tenant_id");
    TenantDataCatalog cat =
        build(
            new Shop() {
              @Override
              public Map<String, String> tenantPredicates() {
                return Map.of("user_roles", OWN_USERS, "sandboxes", "live_tenant_id = ?");
              }

              @Override
              public Map<String, String> erasurePredicates() {
                return Map.of(
                    "tokens", OWN_USERS, "sandboxes", "? IN (sandbox_tenant_id, live_tenant_id)");
              }
            });
    assertEquals(List.of(), cat.problems());
    assertEquals("live_tenant_id = ?", cat.table("sandboxes").orElseThrow().tenantPredicate());
    assertEquals(
        "? IN (sandbox_tenant_id, live_tenant_id)",
        cat.erasureOrder().stream()
            .filter(e -> e.name().equals("sandboxes"))
            .findFirst()
            .orElseThrow()
            .predicate());
    // A table with no erasure predicate of its own is erased by its export predicate, as before.
    assertEquals(
        OWN_USERS,
        cat.erasureOrder().stream()
            .filter(e -> e.name().equals("user_roles"))
            .findFirst()
            .orElseThrow()
            .predicate());
  }

  @Test
  @DisplayName("An erasure predicate on an exported table with tenant_id is a problem")
  void anExportedTenantTableNeedsNoErasurePredicate() {
    shop();
    TenantDataCatalog cat =
        build(
            new Shop() {
              @Override
              public Map<String, String> erasurePredicates() {
                return Map.of("tokens", OWN_USERS, "orders", "tenant_id = ?");
              }
            });
    assertTrue(
        cat.problems().stream()
            .anyMatch(p -> p.toString().contains("orders") && p.toString().contains("tenant_id")),
        cat.problems().toString());
  }

  @Test
  @DisplayName("What is left out stays out, with its reason, and every other column goes")
  void exclusions() {
    shop();
    TenantDataCatalog cat =
        build(
            new Shop() {
              @Override
              public Map<String, String> excludedTables() {
                return Map.of(
                    "secrets", "credentials", "tokens", "login tokens", "allergens", "platform");
              }
            });
    assertEquals(List.of(), cat.problems());
    Table users = cat.table("users").orElseThrow();
    assertEquals(
        List.of("id", "tenant_id", "email"),
        users.columns().stream().map(TenantDataCatalog.Column::name).toList());
    assertEquals("tenant_id = ?", users.tenantPredicate());
    assertEquals("logins are made at the destination", users.importSkippedReason());
    assertEquals(OWN_USERS, cat.table("user_roles").orElseThrow().tenantPredicate());
    assertFalse(cat.table("user_roles").orElseThrow().hasTenantColumn());
    assertTrue(cat.table("secrets").isEmpty());
    assertTrue(cat.table("outbox").isEmpty());
    assertEquals("credentials", cat.excludedTables().get("secrets"));
    assertTrue(cat.excludedTables().containsKey("outbox"));
    assertEquals("a credential", cat.excludedColumns().get("users.password_hash"));
    assertFalse(
        cat.erasureOrder().stream().anyMatch(e -> e.name().equals("allergens")),
        "platform reference data has no tenant and is not erased");

    Table items = cat.table("order_items").orElseThrow();
    assertTrue(items.derived());
    assertNull(items.importSkippedReason());
    assertEquals(
        List.of("id", "tenant_id", "order_id", "qty"),
        items.insertable().stream().map(TenantDataCatalog.Column::name).toList(),
        "a generated column is exported but never written");
    assertEquals(5, items.columns().size());
  }

  @Test
  @DisplayName("A table that cannot be exported safely is a problem, never quietly left out")
  void problems() {
    shop();
    table("loose", "id", "note");
    table("keyless", "id", "tenant_id");
    keys.remove("keyless");
    table("Mixed", "id", "tenant_id");
    table("a", "id", "tenant_id", "b_id");
    table("b", "id", "tenant_id", "a_id");
    references.add(new Reference("a", "b"));
    references.add(new Reference("b", "a"));
    TenantDataCatalog cat =
        build(
            new Shop() {
              @Override
              public Map<String, String> excludedColumns() {
                return Map.of(
                    "users.password_hash",
                    "a credential",
                    "orders.id",
                    "no",
                    "orders.gone",
                    "stale");
              }

              @Override
              public Map<String, String> tenantPredicates() {
                return Map.of(
                    "user_roles",
                    "user_id = ? OR user_id = ?",
                    "orders",
                    OWN_USERS,
                    "vanished",
                    OWN_USERS);
              }
            });
    List<String> found = cat.problems().stream().map(p -> p.table() + ": " + p.message()).toList();
    assertTrue(
        found.contains(
            "loose: has no tenant_id and no tenant predicate: exclude it or tie it to a tenant"),
        found.toString());
    assertTrue(
        found.contains("keyless: has no primary key, so its rows cannot be paged"),
        found.toString());
    assertTrue(found.contains("Mixed: not a plain identifier"), found.toString());
    assertTrue(found.contains("a,b: tables refer to each other in a cycle"), found.toString());
    assertTrue(found.contains("orders: key column id cannot be excluded"), found.toString());
    assertTrue(
        found.contains("orders: excluded column orders.gone is not in the schema"),
        found.toString());
    assertTrue(
        found.contains("orders: has tenant_id, so it needs no tenant predicate"), found.toString());
    assertTrue(
        found.contains("user_roles: its tenant predicate must take exactly one parameter"),
        found.toString());
    assertTrue(found.contains("vanished: tenant predicate is not in the schema"), found.toString());
    assertTrue(
        found.contains(
            "allergens: has no tenant_id and no tenant predicate: exclude it or tie it to a tenant"),
        found.toString());
    assertTrue(cat.table("loose").isEmpty());
  }

  @Test
  @DisplayName("A table kept at erasure is exported, stays when the rest goes, and must exist")
  void keptAtErasure() {
    shop();
    columns.removeIf(c -> c.table().equals("allergens"));
    keys.remove("allergens");
    TenantDataCatalog cat =
        build(
            new Shop() {
              @Override
              public Map<String, String> keptAtErasure() {
                return Map.of("orders", "the record of what was sold");
              }
            });
    assertEquals(List.of(), cat.problems());
    assertTrue(cat.table("orders").isPresent(), "kept tables are still exported");
    List<String> erased = cat.erasureOrder().stream().map(Erasable::name).toList();
    assertFalse(erased.contains("orders"));
    assertTrue(erased.contains("order_items"));
    assertEquals("the record of what was sold", cat.keptAtErasure().get("orders"));

    TenantDataCatalog stale =
        build(
            new Shop() {
              @Override
              public Map<String, String> keptAtErasure() {
                return Map.of("gone", "stale");
              }
            });
    assertEquals(
        List.of("gone: table kept at erasure is not in the schema"),
        stale.problems().stream().map(p -> p.table() + ": " + p.message()).toList());
  }
}
