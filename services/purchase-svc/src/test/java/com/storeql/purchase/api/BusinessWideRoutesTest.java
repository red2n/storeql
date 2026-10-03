package com.storeql.purchase.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The routes of purchase-svc that act on the business as a whole — its accounting connection and
 * every journal's push, its e-invoice inbox settings, readiness and fetch, its payment runs and the
 * accounts they pay from — refuse a caller held to stores {@code 403 BUSINESS_WIDE_ONLY} before the
 * service is reached (every service here is absent, so reaching one would fail otherwise), and let
 * a caller held to none through. The package catalogue is not the business's and stays readable.
 */
class BusinessWideRoutesTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE = Ids.newId();

  /** A caller of the business, of a role, held to the stores named (none: the whole business). */
  private static TenantContext caller(String role, Set<UUID> heldTo) {
    return caller(role, heldTo, Permissions.effective(Set.of(role)));
  }

  /** As above, with a custom role's permissions: narrower than its tier's defaults. */
  private static TenantContext caller(String role, Set<UUID> heldTo, Set<String> held) {
    UUID user = Ids.newId();
    return new TenantContext() {
      @Override
      public UUID tenantId() {
        return TENANT;
      }

      @Override
      public UUID requireTenantId() {
        return TENANT;
      }

      @Override
      public UUID userId() {
        return user;
      }

      @Override
      public UUID requireUserId() {
        return user;
      }

      @Override
      public Set<String> roles() {
        return Set.of(role);
      }

      @Override
      public boolean hasRole(String r) {
        return role.equals(r);
      }

      @Override
      public void requireAnyRole(String... required) {
        for (String r : required) {
          if (role.equals(r)) return;
        }
        throw ApiException.forbidden("FORBIDDEN", "Insufficient role for this operation");
      }

      @Override
      public Set<String> permissions() {
        return held;
      }

      @Override
      public Set<UUID> storeIds() {
        return heldTo;
      }
    };
  }

  /** Every business-wide route, as a call on resources whose services are absent. */
  private static Map<String, Executable> routes(TenantContext ctx) {
    AccountingResource accounting = new AccountingResource();
    accounting.ctx = ctx;
    EInvoiceInboxResource inbox = new EInvoiceInboxResource();
    inbox.ctx = ctx;
    PaymentRunResource runs = new PaymentRunResource();
    runs.ctx = ctx;
    UUID id = Ids.newId();
    Map<String, Executable> r = new LinkedHashMap<>();
    r.put("GET /accounting/connection", accounting::connection);
    r.put("PUT /accounting/connection", () -> accounting.connect(null));
    r.put("DELETE /accounting/connection", accounting::disconnect);
    r.put("POST /accounting/connection/disable", accounting::disable);
    r.put("POST /accounting/connection/enable", accounting::enable);
    r.put("GET /accounting/connection/accounts", accounting::accounts);
    r.put("GET /accounting/connection/mappings", accounting::mappings);
    r.put("PUT /accounting/connection/mappings", () -> accounting.replaceMappings(null));
    r.put("POST /accounting/connection/sync", accounting::sync);
    r.put("GET /accounting/syncs", () -> accounting.syncs(null, null, 20));
    r.put("GET /accounting/syncs/{id}", () -> accounting.sync(id));
    r.put("POST /accounting/syncs/{id}/retry", () -> accounting.retry(id));
    r.put("POST /accounting/syncs/{id}/resolve", () -> accounting.resolve(id, null));
    r.put("POST /accounting/syncs/{id}/skip", () -> accounting.skip(id, null));
    r.put("GET /admin/e-invoices/inbox/settings", inbox::settings);
    r.put("PUT /admin/e-invoices/inbox/settings", () -> inbox.setSettings(null));
    r.put("GET /admin/e-invoices/inbox/readiness", inbox::readiness);
    r.put("POST /admin/e-invoices/inbox/fetch", () -> inbox.fetch(null, null));
    r.put("POST /payment-runs", () -> runs.propose(null));
    r.put("GET /payment-runs", () -> runs.list(null, 20));
    r.put("GET /payment-runs/{id}", () -> runs.get(id));
    r.put("POST /payment-runs/{id}/approve", () -> runs.approve(id));
    r.put("POST /payment-runs/{id}/pay", () -> runs.pay(id));
    r.put("POST /payment-runs/{id}/cancel", () -> runs.cancel(id, null));
    r.put("GET /payment-runs/{id}/bank-file", () -> runs.bankFile(id, null));
    r.put("GET /payment-runs/paying-accounts", runs::payingAccounts);
    r.put("PUT /payment-runs/paying-accounts/{currency}", () -> runs.setPayingAccount("GBP", null));
    r.put("POST /payment-runs/{id}/status-report", () -> runs.statusReport(id, "<x/>"));
    r.put(
        "POST /payment-runs/{id}/payments/{supplierId}/release",
        () -> runs.release(id, Ids.newId(), null));
    return r;
  }

  @Test
  @DisplayName(
      "A manager or owner held to a store is refused BUSINESS_WIDE_ONLY on every business-wide"
          + " route, before its service is reached")
  void aCallerHeldToStoresIsRefused() {
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      for (Map.Entry<String, Executable> route : routes(caller(role, Set.of(STORE))).entrySet()) {
        ApiException e = assertThrows(ApiException.class, route.getValue(), route.getKey());
        assertThat(role + " " + route.getKey(), e.status(), is(403));
        // The connection itself is the owner's alone: a manager is refused that first.
        String expected =
            "MANAGER".equals(role) && OWNER_ONLY.contains(route.getKey())
                ? "FORBIDDEN"
                : "BUSINESS_WIDE_ONLY";
        assertThat(role + " " + route.getKey(), e.code(), is(expected));
      }
    }
  }

  @Test
  @DisplayName(
      "A storekeeper or a cashier held to a store is refused for the role (FORBIDDEN) on every"
          + " business-wide route — the role first, the scope after — never told it is the"
          + " business's")
  void theRoleIsCheckedBeforeTheScope() {
    for (String role : new String[] {"STOREKEEPER", "CASHIER"}) {
      for (Map.Entry<String, Executable> route : routes(caller(role, Set.of(STORE))).entrySet()) {
        ApiException e = assertThrows(ApiException.class, route.getValue(), route.getKey());
        assertThat(role + " " + route.getKey(), e.status(), is(403));
        assertThat(role + " " + route.getKey(), e.code(), is("FORBIDDEN"));
      }
    }
  }

  @Test
  @DisplayName(
      "A manager held to a store without finance.journal is refused PERMISSION_DENIED on every"
          + " accounting route that needs it — the permission before the scope, as on the payment"
          + " runs — and BUSINESS_WIDE_ONLY on the reads that do not")
  void thePermissionIsCheckedBeforeTheScope() {
    Set<String> noJournal = new java.util.HashSet<>(Permissions.effective(Set.of("MANAGER")));
    noJournal.remove(Permissions.FINANCE_JOURNAL);
    Map<String, Executable> all = routes(caller("MANAGER", Set.of(STORE), Set.copyOf(noJournal)));
    for (Map.Entry<String, Executable> route : all.entrySet()) {
      if (!route.getKey().contains("/accounting/") || OWNER_ONLY.contains(route.getKey())) {
        continue;
      }
      ApiException e = assertThrows(ApiException.class, route.getValue(), route.getKey());
      assertThat(route.getKey(), e.status(), is(403));
      assertThat(
          route.getKey(),
          e.code(),
          is(JOURNAL_ROUTES.contains(route.getKey()) ? "PERMISSION_DENIED" : "BUSINESS_WIDE_ONLY"));
    }
  }

  @Test
  @DisplayName(
      "The owner-only connection routes refuse a manager held to no store FORBIDDEN — the owner's"
          + " alone, whatever the scope")
  void theConnectionIsTheOwnersAlone() {
    Map<String, Executable> all = routes(caller("MANAGER", Set.of()));
    for (String route : OWNER_ONLY) {
      ApiException e = assertThrows(ApiException.class, all.get(route), route);
      assertThat(route, e.code(), is("FORBIDDEN"));
    }
  }

  private static final Set<String> JOURNAL_ROUTES =
      Set.of(
          "PUT /accounting/connection/mappings",
          "POST /accounting/connection/sync",
          "POST /accounting/syncs/{id}/retry",
          "POST /accounting/syncs/{id}/resolve",
          "POST /accounting/syncs/{id}/skip");

  private static final Set<String> OWNER_ONLY =
      Set.of(
          "PUT /accounting/connection",
          "DELETE /accounting/connection",
          "POST /accounting/connection/disable",
          "POST /accounting/connection/enable");

  @Test
  @DisplayName("A caller held to no store is past the gate on every one of them")
  void aCallerHeldToNoneIsPastTheGate() {
    for (Map.Entry<String, Executable> route : routes(caller("OWNER", Set.of())).entrySet()) {
      try {
        route.getValue().execute();
      } catch (ApiException e) {
        assertThat(route.getKey(), e.code().equals("BUSINESS_WIDE_ONLY"), is(false));
      } catch (Throwable reachedTheAbsentService) {
        // past the gate
      }
    }
  }

  @Test
  @DisplayName(
      "The catalogue of packages is nobody's in particular: a manager held to a store reads it")
  void theCatalogueStaysReadable() {
    AccountingResource accounting = new AccountingResource();
    accounting.ctx = caller("MANAGER", Set.of(STORE));
    accounting.service = new com.storeql.purchase.service.AccountingService();
    assertThat(accounting.providers().data().isEmpty(), is(false));
  }
}
