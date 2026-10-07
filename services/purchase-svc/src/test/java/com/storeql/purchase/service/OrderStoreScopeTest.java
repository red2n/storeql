package com.storeql.purchase.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.LineAllocation;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceLine;
import com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceRequest;
import com.storeql.purchase.repo.CrossDockRepository;
import com.storeql.purchase.repo.DropshipRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A write on a purchase order is for the order's own store: cross-docking a line, marking a
 * dropship order delivered and capturing the supplier's invoice refuse a caller held to another
 * store with {@code 403 STORE_ACCESS_DENIED} before anything is read past the order or written, as
 * every other write on an order does. No database: the order is a stand-in, and every write is
 * counted.
 */
class OrderStoreScopeTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID OTHER_TENANT = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();
  private static final UUID LINE = Ids.newId();

  /** A caller of a business, of a role, held to the stores named (none: the whole business). */
  private static TenantContext caller(UUID tenant, String role, Set<UUID> heldTo) {
    UUID user = Ids.newId();
    return new TenantContext() {
      @Override
      public UUID tenantId() {
        return tenant;
      }

      @Override
      public UUID requireTenantId() {
        return tenant;
      }

      @Override
      public UUID userId() {
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
      public Set<UUID> storeIds() {
        return heldTo;
      }

      @Override
      public boolean hasStoreAccess(UUID storeId) {
        return heldTo.isEmpty() || heldTo.contains(storeId);
      }
    };
  }

  /** An order of ours at store B. */
  private static PurchaseOrder orderAtB(String status, String source, String ownership) {
    Instant now = Instant.now();
    return new PurchaseOrder(
        Ids.newId(),
        TENANT,
        Ids.newId(),
        STORE_B,
        status,
        "GBP",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        now,
        now,
        null,
        null,
        null,
        null,
        Ids.newId(),
        null,
        null,
        source,
        null,
        null,
        null,
        ownership,
        null,
        null,
        Domain.PO_DUTY_PAID);
  }

  /** The one order there is, found only by its own business; every read past it counted. */
  private static final class OneOrder extends PurchaseRepository {
    private final PurchaseOrder po;
    int linesRead;
    int matchesRead;

    OneOrder(PurchaseOrder po) {
      this.po = po;
    }

    @Override
    public Optional<PurchaseOrder> findPurchaseOrder(UUID tenantId, UUID id) {
      return po.tenantId().equals(tenantId) && po.id().equals(id)
          ? Optional.of(po)
          : Optional.empty();
    }

    @Override
    public List<PurchaseOrderLine> findPurchaseOrderLines(UUID tenantId, UUID poId) {
      linesRead++;
      return List.of(
          new PurchaseOrderLine(
              LINE,
              tenantId,
              poId,
              Ids.newId(),
              BigDecimal.TEN,
              BigDecimal.ONE,
              "T1",
              Instant.now(),
              null));
    }

    @Override
    public List<com.storeql.purchase.domain.ThreeWayMatch.OrderPosition> findMatchPositions(
        UUID tenantId, UUID poId) {
      matchesRead++;
      return List.of();
    }
  }

  private static final class CountedAllocations extends CrossDockRepository {
    int writes;

    @Override
    public List<LineAllocation> replace(
        UUID tenantId, UUID poId, UUID lineId, List<LineAllocation> allocations) {
      writes++;
      return allocations;
    }
  }

  private static final class CountedDeliveries extends DropshipRepository {
    int writes;

    @Override
    public void markDelivered(UUID tenantId, UUID poId, List<NominalLedgerEntry> posting) {
      writes++;
    }
  }

  private static CrossDockService crossDock(OneOrder orders, CountedAllocations allocations) {
    PurchaseService purchases = new PurchaseService();
    purchases.repo = orders;
    CrossDockService s = new CrossDockService();
    s.purchases = purchases;
    s.orders = orders;
    s.repo = allocations;
    return s;
  }

  // ── cross-docking a line ────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Allocating or filling a line of store B's order: a buyer held to store A is refused"
          + " STORE_ACCESS_DENIED, and nothing is read past the order or written")
  void crossDockIsForTheOrdersStore() {
    PurchaseOrder po = orderAtB(Domain.PO_SUBMITTED, Domain.PO_SOURCE_MANUAL, null);
    OneOrder orders = new OneOrder(po);
    CountedAllocations allocations = new CountedAllocations();
    CrossDockService svc = crossDock(orders, allocations);
    List<CrossDockService.Allocation> ask =
        List.of(new CrossDockService.Allocation(Ids.newId(), BigDecimal.ONE));

    for (String role : new String[] {"MANAGER", "STOREKEEPER"}) {
      TenantContext heldToA = caller(TENANT, role, Set.of(STORE_A));
      ApiException allocate =
          assertThrows(ApiException.class, () -> svc.allocate(heldToA, po.id(), LINE, ask));
      assertThat(role, allocate.status(), is(403));
      assertThat(role, allocate.code(), is("STORE_ACCESS_DENIED"));
      ApiException fill =
          assertThrows(ApiException.class, () -> svc.fillFromNeeds(heldToA, po.id(), LINE));
      assertThat(role, fill.status(), is(403));
      assertThat(role, fill.code(), is("STORE_ACCESS_DENIED"));
    }
    assertThat("no line was read", orders.linesRead, is(0));
    assertThat("nothing was allocated", allocations.writes, is(0));

    // Another business's buyer, held to no store, finds no such order.
    ApiException theirs =
        assertThrows(
            ApiException.class,
            () -> svc.allocate(caller(OTHER_TENANT, "OWNER", Set.of()), po.id(), LINE, ask));
    assertThat(theirs.status(), is(404));
    assertThat(theirs.code(), is("PURCHASE_PO_NOT_FOUND"));
    assertThat(allocations.writes, is(0));

    // Held to store B, or to none, the same buyer is past the check: this order is no draft.
    for (Set<UUID> heldTo : List.of(Set.of(STORE_B), Set.<UUID>of())) {
      ApiException past =
          assertThrows(
              ApiException.class,
              () -> svc.allocate(caller(TENANT, "MANAGER", heldTo), po.id(), LINE, ask));
      assertThat(past.code(), is("PURCHASE_ALLOCATION_ORDER_NOT_DRAFT"));
    }
  }

  // ── a dropship order delivered ──────────────────────────────────────────────

  @Test
  @DisplayName(
      "Marking store B's dropship order delivered: a caller held to store A is refused"
          + " STORE_ACCESS_DENIED and nothing is posted; held to B, it is delivered")
  void dropshipDeliveryIsForTheOrdersStore() {
    PurchaseOrder po = orderAtB(Domain.PO_SUBMITTED, Domain.PO_SOURCE_DROPSHIP, null);
    CountedDeliveries deliveries = new CountedDeliveries();
    DropshipService svc = new DropshipService();
    svc.purchases = new OneOrder(po);
    svc.repo = deliveries;

    for (String role : new String[] {"MANAGER", "STOREKEEPER"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.deliver(caller(TENANT, role, Set.of(STORE_A)), po.id()));
      assertThat(role, e.status(), is(403));
      assertThat(role, e.code(), is("STORE_ACCESS_DENIED"));
    }
    ApiException theirs =
        assertThrows(
            ApiException.class,
            () -> svc.deliver(caller(OTHER_TENANT, "OWNER", Set.of()), po.id()));
    assertThat(theirs.status(), is(404));
    assertThat(theirs.code(), is("PURCHASE_PO_NOT_FOUND"));
    assertThat("nothing was posted", deliveries.writes, is(0));

    svc.deliver(caller(TENANT, "STOREKEEPER", Set.of(STORE_B)), po.id());
    assertThat("held to the order's store, it is delivered", deliveries.writes, is(1));
  }

  // ── the supplier's invoice ──────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Capturing an invoice against store B's order: a caller held to store A is refused"
          + " STORE_ACCESS_DENIED before anything is matched")
  void invoiceCaptureIsForTheOrdersStore() {
    // A consignment order, so a caller past the store check gets the order's own answer without
    // the matching rules this test does not stand up.
    PurchaseOrder po =
        orderAtB(Domain.PO_SUBMITTED, Domain.PO_SOURCE_MANUAL, Domain.PO_OWNERSHIP_CONSIGNMENT);
    OneOrder orders = new OneOrder(po);
    PurchaseService svc = new PurchaseService();
    svc.repo = orders;
    CaptureSupplierInvoiceRequest req =
        new CaptureSupplierInvoiceRequest(
            po.id(),
            "INV-1",
            "2026-09-01",
            null,
            BigDecimal.ZERO,
            null,
            List.of(
                new CaptureSupplierInvoiceLine(Ids.newId(), BigDecimal.ONE, BigDecimal.ONE, null)));

    for (String role : new String[] {"MANAGER", "STOREKEEPER"}) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.captureSupplierInvoice(caller(TENANT, role, Set.of(STORE_A)), req));
      assertThat(role, e.status(), is(403));
      assertThat(role, e.code(), is("STORE_ACCESS_DENIED"));
    }
    ApiException theirs =
        assertThrows(
            ApiException.class,
            () -> svc.captureSupplierInvoice(caller(OTHER_TENANT, "OWNER", Set.of()), req));
    assertThat(theirs.status(), is(404));
    assertThat(theirs.code(), is("PURCHASE_PO_NOT_FOUND"));
    assertThat("nothing was matched", orders.matchesRead, is(0));

    ApiException past =
        assertThrows(
            ApiException.class,
            () -> svc.captureSupplierInvoice(caller(TENANT, "MANAGER", Set.of(STORE_B)), req));
    assertThat(past.code(), is("PURCHASE_CONSIGNMENT_NOT_INVOICED"));
  }
}
