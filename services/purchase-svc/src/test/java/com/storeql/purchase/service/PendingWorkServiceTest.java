package com.storeql.purchase.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.config.ServiceConfig;
import com.storeql.purchase.domain.PendingWork;
import com.storeql.purchase.repo.PendingWorkRepository;
import com.storeql.web.ApiException;
import com.storeql.web.PendingWorkCount;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pending-work read: it asks who is calling before it reads anything, counts for the caller's
 * business only, and says whether approvals are routed exactly as submission decides it. No
 * database: the repository is a stand-in that records what it is asked. The shape of the four
 * counting statements is held here too: the cap on each is what keeps a poll's cost from growing
 * with a queue, and the integration test proves the figures it gives.
 */
class PendingWorkServiceTest {

  /** Answers fixed counts and remembers every business it was asked about. */
  private static final class Counts extends PendingWorkRepository {
    final List<UUID> asked = new ArrayList<>();
    long orders = 3;

    private long answer(UUID tenantId, long n) {
      asked.add(tenantId);
      return n;
    }

    @Override
    public long purchaseOrdersPendingApproval(UUID tenantId) {
      return answer(tenantId, orders);
    }

    @Override
    public long paymentRunsProposed(UUID tenantId) {
      return answer(tenantId, 4);
    }

    @Override
    public long supplierInvoicesFlagged(UUID tenantId) {
      return answer(tenantId, 5);
    }

    @Override
    public long accountingSyncsUncertain(UUID tenantId) {
      return answer(tenantId, 6);
    }
  }

  private static PendingWorkService service(Counts counts, boolean routed) {
    PendingWorkService svc = new PendingWorkService();
    svc.repo = counts;
    svc.config =
        new ServiceConfig() {
          @Override
          public boolean approvalEnabled() {
            return routed;
          }
        };
    return svc;
  }

  /**
   * A caller of a business ({@code null}: none), of roles, held to stores, with a claim or none.
   */
  private static TenantContext caller(
      UUID tenant, Set<String> roles, Set<UUID> stores, Set<String> claim) {
    return new TenantContext() {
      @Override
      public UUID requireTenantId() {
        if (tenant == null) throw ApiException.unauthorized("NO_TENANT", "No tenant in context");
        return tenant;
      }

      @Override
      public Set<String> roles() {
        return roles;
      }

      @Override
      public Set<UUID> storeIds() {
        return stores;
      }

      @Override
      public Set<String> permissions() {
        if (Permissions.unrestricted(roles)) return Permissions.ALL;
        return claim != null ? claim : Permissions.effective(roles);
      }
    };
  }

  @Test
  @DisplayName(
      "It counts the caller's business, and whether approvals are routed follows the config")
  void countsTheCallersBusinessAndSaysWhetherApprovalsAreRouted() {
    UUID tenant = Ids.newId();
    TenantContext ctx = caller(tenant, Set.of("MANAGER"), Set.of(), null);

    Counts on = new Counts();
    assertThat(service(on, true).read(ctx), is(new PendingWork(3, 4, 5, 6, true)));
    assertThat(on.asked.stream().distinct().toList(), is(List.of(tenant)));

    assertThat(service(new Counts(), false).read(ctx), is(new PendingWork(3, 4, 5, 6, false)));
  }

  @Test
  @DisplayName(
      "A caller without the permission, or held to stores, is refused before anything is read")
  void refusesBeforeReading() {
    Counts counts = new Counts();
    PendingWorkService svc = service(counts, true);
    UUID tenant = Ids.newId();

    ApiException narrowed =
        assertThrows(
            ApiException.class,
            () ->
                svc.read(
                    caller(tenant, Set.of("MANAGER"), Set.of(), Set.of(Permissions.STAFF_MANAGE))));
    assertThat(narrowed.code(), is("SYSTEM_HEALTH_NOT_PERMITTED"));

    ApiException held =
        assertThrows(
            ApiException.class,
            () -> svc.read(caller(tenant, Set.of("MANAGER"), Set.of(Ids.newId()), null)));
    assertThat(held.code(), is("BUSINESS_WIDE_ONLY"));

    assertThat(counts.asked.isEmpty(), is(true));
  }

  @Test
  @DisplayName("A request with no business is refused, not counted for nobody")
  void noBusinessIsRefused() {
    Counts counts = new Counts();
    ApiException e =
        assertThrows(
            ApiException.class,
            () -> service(counts, true).read(caller(null, Set.of("OWNER"), Set.of(), null)));
    assertThat(e.status(), is(401));
    assertThat(counts.asked.isEmpty(), is(true));
  }

  @Test
  @DisplayName("A count that stopped at the cap is reported as it was read, never added to")
  void aCappedCountIsPassedOnAsRead() {
    UUID tenant = Ids.newId();
    long cap = PendingWorkCount.CAP;
    Counts atCap = new Counts();
    atCap.orders = cap;
    assertThat(
        service(atCap, true).read(caller(tenant, Set.of("OWNER"), Set.of(), null)),
        is(new PendingWork(cap, 4, 5, 6, true)));
  }

  @Test
  @DisplayName(
      "Each count reads at most the cap of its queue: tenant first, the status written in, a bound"
          + " limit, and no column list to widen")
  void everyCountingStatementIsBounded() {
    Map<String, String> statements =
        Map.of(
            "PURCHASE_ORDERS_PENDING_APPROVAL",
            PendingWorkRepository.PURCHASE_ORDERS_PENDING_APPROVAL,
            "PAYMENT_RUNS_PROPOSED",
            PendingWorkRepository.PAYMENT_RUNS_PROPOSED,
            "SUPPLIER_INVOICES_FLAGGED",
            PendingWorkRepository.SUPPLIER_INVOICES_FLAGGED,
            "ACCOUNTING_SYNCS_UNCERTAIN",
            PendingWorkRepository.ACCOUNTING_SYNCS_UNCERTAIN);
    for (Map.Entry<String, String> e : statements.entrySet()) {
      String sql = e.getValue();
      String name = e.getKey();
      assertThat(name, sql.startsWith("SELECT count(*) AS n FROM (SELECT 1 FROM "), is(true));
      assertThat(name, sql.endsWith(" LIMIT ?) AS waiting"), is(true));
      // Two parameters, the business and the cap; the status is a literal so a partial index on it
      // can be used.
      assertThat(name, sql.chars().filter(ch -> ch == '?').count(), is(2L));
      assertThat(name, sql.contains("WHERE tenant_id = ? AND status = '"), is(true));
      assertThat(name, sql.contains("SELECT *"), is(false));
    }
  }
}
