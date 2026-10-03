package com.storeql.purchase.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.ConsignmentSale;
import com.storeql.purchase.domain.Domain.ConsignmentSettlement;
import com.storeql.purchase.domain.Domain.DropshipArrangement;
import com.storeql.purchase.domain.Domain.DutyRelease;
import com.storeql.purchase.domain.Domain.GoodsReceipt;
import com.storeql.purchase.domain.Domain.IntercompanyInvoice;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.LandedCost;
import com.storeql.purchase.domain.LandedCost.Charge;
import com.storeql.purchase.domain.Rfq;
import com.storeql.purchase.domain.Rfq.Header;
import com.storeql.purchase.dto.Dtos.CreateConsignmentSettlementRequest;
import com.storeql.purchase.dto.Dtos.CreateDropshipArrangementRequest;
import com.storeql.purchase.dto.Dtos.RaiseIntercompanyInvoiceRequest;
import com.storeql.purchase.dto.Dtos.RecordCreditNoteRequest;
import com.storeql.purchase.dto.Dtos.ResolveSupplierInvoiceRequest;
import com.storeql.purchase.dto.RfqDtos.RfqAwardRequest;
import com.storeql.purchase.dto.RfqDtos.RfqQuoteRequest;
import com.storeql.purchase.repo.ConsignmentRepository;
import com.storeql.purchase.repo.DropshipRepository;
import com.storeql.purchase.repo.DutyRepository;
import com.storeql.purchase.repo.LandedCostRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.purchase.repo.RfqRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every purchase-svc route that reaches a record bound to a store — an order and what hangs off it
 * (its lines, receipts, invoices, returns, landed costs), a request for quotes, an intercompany
 * invoice — holds a caller held to stores to the record's own store: {@code 403
 * STORE_ACCESS_DENIED} before anything is written or read past the record, and lists hold to the
 * caller's stores. What gathers every store's records into one — a consignment statement, a
 * dropship arrangement — is made only by the whole business ({@code 403 BUSINESS_WIDE_ONLY}); a
 * statement is read by whoever may read every sale in it. Another business finds none of it ({@code
 * 404}). No database: every repository is a stand-in that counts what it is asked.
 */
class StoreHeldCallersTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID OTHER_TENANT = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();

  /** A caller of a business, of a role, held to the stores named (none: the whole business). */
  static TenantContext caller(UUID tenant, String role, Set<UUID> heldTo) {
    UUID user = Ids.newId();
    Set<String> roles = Set.of(role);
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
        return roles;
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
        return Permissions.unrestricted(roles) ? Permissions.ALL : Permissions.effective(roles);
      }

      @Override
      public Set<UUID> storeIds() {
        return heldTo;
      }

      @Override
      public boolean hasStoreAccess(UUID storeId) {
        return heldTo.isEmpty() || heldTo.contains(storeId);
      }

      @Override
      public Set<UUID> reportStores(UUID requested) {
        if (requested != null) {
          requireStoreAccess(requested);
          return Set.of(requested);
        }
        return heldTo.isEmpty() ? null : Set.copyOf(heldTo);
      }
    };
  }

  private static TenantContext heldToA(String role) {
    return caller(TENANT, role, Set.of(STORE_A));
  }

  private static TenantContext heldToB(String role) {
    return caller(TENANT, role, Set.of(STORE_B));
  }

  private static TenantContext wholeBusiness(String role) {
    return caller(TENANT, role, Set.of());
  }

  private static TenantContext theirs(String role) {
    return caller(OTHER_TENANT, role, Set.of());
  }

  private static void refused(int status, String code, Executable call) {
    ApiException e = assertThrows(ApiException.class, call::execute);
    assertThat(e.getMessage(), e.status(), is(status));
    assertThat(e.getMessage(), e.code(), is(code));
  }

  /** {@link java.util.function.Supplier}-shaped, but allowed to throw what a service throws. */
  @FunctionalInterface
  interface Executable {
    void execute();
  }

  // ── stand-ins ───────────────────────────────────────────────────────────────

  /** An order of ours at store B. */
  private static PurchaseOrder orderAtB(String status) {
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
        Domain.PO_SOURCE_MANUAL,
        null,
        null,
        null,
        Domain.PO_OWNERSHIP_OWNED,
        null,
        null,
        Domain.PO_DUTY_PAID);
  }

  private static Domain.SupplierInvoice flaggedInvoiceOn(PurchaseOrder po) {
    Instant now = Instant.now();
    return new Domain.SupplierInvoice(
        Ids.newId(),
        TENANT,
        po.id(),
        po.supplierId(),
        "INV-1",
        LocalDate.of(2026, 9, 1),
        "GBP",
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.TEN,
        Domain.INVOICE_FLAGGED,
        now,
        null,
        now,
        null,
        null,
        null,
        now,
        null,
        null,
        null,
        null,
        null);
  }

  private static Domain.VendorReturn returnAtB(PurchaseOrder po) {
    return new Domain.VendorReturn(
        Ids.newId(),
        TENANT,
        po.id(),
        po.supplierId(),
        STORE_B,
        "RAISED",
        "DAMAGED",
        null,
        "GBP",
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.TEN,
        "DN-1",
        Instant.now(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Every read and write the purchase repository is asked for, counted; one order known. */
  private static final class Orders extends PurchaseRepository {
    final PurchaseOrder po;
    Domain.SupplierInvoice invoice;
    Domain.VendorReturn ret;
    final List<IntercompanyInvoice> intercompany = new ArrayList<>();
    int pastTheRecord;
    int writes;
    Set<UUID> storesAsked;
    boolean listed;

    Orders(PurchaseOrder po) {
      this.po = po;
    }

    @Override
    public Optional<PurchaseOrder> findPurchaseOrder(UUID tenantId, UUID id) {
      return po.tenantId().equals(tenantId) && po.id().equals(id)
          ? Optional.of(po)
          : Optional.empty();
    }

    @Override
    public List<PurchaseOrder> findPurchaseOrders(UUID tenantId, Set<UUID> stores, int limit) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }

    @Override
    public List<PurchaseOrderLine> findPurchaseOrderLines(UUID tenantId, UUID poId) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public List<Domain.PurchaseOrderApproval> findApprovals(UUID tenantId, UUID poId) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public List<GoodsReceipt> findGoodsReceiptsByPo(UUID tenantId, UUID poId) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public List<com.storeql.purchase.domain.ThreeWayMatch.OrderPosition> findMatchPositions(
        UUID tenantId, UUID poId) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public Optional<Domain.SupplierInvoice> findSupplierInvoice(UUID tenantId, UUID id) {
      return invoice != null && invoice.tenantId().equals(tenantId) && invoice.id().equals(id)
          ? Optional.of(invoice)
          : Optional.empty();
    }

    @Override
    public List<Domain.SupplierInvoice> findSupplierInvoices(
        UUID tenantId, UUID poId, String status, Set<UUID> stores, int limit) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }

    @Override
    public List<Domain.SupplierInvoiceLine> findSupplierInvoiceLines(
        UUID tenantId, UUID invoiceId) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public List<NominalLedgerEntry> findPostingFor(
        UUID tenantId, String sourceType, UUID sourceRef) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public boolean resolveSupplierInvoice(
        UUID tenantId,
        UUID id,
        String status,
        UUID decidedBy,
        String reason,
        List<NominalLedgerEntry> posting,
        OutboxRow event) {
      writes++;
      return true;
    }

    @Override
    public Optional<Domain.VendorReturn> findVendorReturn(UUID tenantId, UUID id) {
      return ret != null && ret.tenantId().equals(tenantId) && ret.id().equals(id)
          ? Optional.of(ret)
          : Optional.empty();
    }

    @Override
    public List<Domain.VendorReturn> findVendorReturns(UUID tenantId, UUID poId, Set<UUID> stores) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }

    @Override
    public List<Domain.VendorReturnLine> findVendorReturnLines(UUID tenantId, UUID id) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public boolean creditVendorReturn(
        UUID tenantId,
        UUID id,
        String creditNoteNumber,
        LocalDate creditNoteDate,
        BigDecimal amount,
        UUID creditedBy,
        List<NominalLedgerEntry> posting) {
      writes++;
      return true;
    }

    @Override
    public Optional<IntercompanyInvoice> findIntercompanyInvoice(UUID tenantId, UUID id) {
      return intercompany.stream()
          .filter(i -> i.tenantId().equals(tenantId) && i.id().equals(id))
          .findFirst();
    }

    @Override
    public List<IntercompanyInvoice> findIntercompanyInvoices(
        UUID tenantId, Set<UUID> stores, int limit) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }

    @Override
    public List<IntercompanyInvoice> createIntercompanyInvoicePair(
        IntercompanyInvoice ar,
        List<NominalLedgerEntry> arEntries,
        OutboxRow arEvent,
        IntercompanyInvoice ap,
        List<NominalLedgerEntry> apEntries,
        OutboxRow apEvent) {
      writes++;
      return List.of(ar, ap);
    }

    @Override
    public void settleIntercompanyInvoice(
        UUID tenantId, UUID id, List<NominalLedgerEntry> settlementEntries) {
      writes++;
    }

    final List<NominalLedgerEntry> journal = new ArrayList<>();

    @Override
    public List<NominalLedgerEntry> findJournal(UUID tenantId, UUID journalId) {
      return journal.stream()
          .filter(l -> l.tenantId().equals(tenantId) && l.journalId().equals(journalId))
          .toList();
    }

    @Override
    public List<Domain.TrialBalanceRow> findTrialBalance(
        UUID tenantId, LocalDate from, LocalDate to, Set<UUID> stores) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }

    @Override
    public List<NominalLedgerEntry> findNominalLedger(
        UUID tenantId,
        String nominalCode,
        LocalDate from,
        LocalDate to,
        LocalDate afterEntryDate,
        Instant afterCreatedAt,
        UUID afterId,
        Set<UUID> stores,
        int limit) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }
  }

  private static PurchaseService purchases(Orders orders) {
    PurchaseService s = new PurchaseService();
    s.repo = orders;
    return s;
  }

  // ── U1: deciding a flagged invoice ──────────────────────────────────────────

  @Test
  @DisplayName(
      "Approving or rejecting a flagged invoice on store B's order: a manager held to store A is"
          + " refused STORE_ACCESS_DENIED before anything is read past it or written; another"
          + " business finds no such invoice; held to B, it is decided")
  void decidingAFlaggedInvoiceIsForItsOrdersStore() {
    PurchaseOrder po = orderAtB(Domain.PO_RECEIVED);
    Orders orders = new Orders(po);
    orders.invoice = flaggedInvoiceOn(po);
    PurchaseService svc = purchases(orders);
    UUID id = orders.invoice.id();

    for (String action : new String[] {"APPROVE", "REJECT"}) {
      ResolveSupplierInvoiceRequest req = new ResolveSupplierInvoiceRequest(action, "checked");
      refused(
          403,
          "STORE_ACCESS_DENIED",
          () -> svc.resolveSupplierInvoice(heldToA("MANAGER"), id, req));
      for (String role : new String[] {"OWNER", "MANAGER"}) {
        refused(
            404,
            "PURCHASE_INVOICE_NOT_FOUND",
            () -> svc.resolveSupplierInvoice(theirs(role), id, req));
      }
    }
    assertThat("no posting read, no line read", orders.pastTheRecord, is(0));
    assertThat("nothing decided", orders.writes, is(0));

    svc.resolveSupplierInvoice(
        heldToB("MANAGER"), id, new ResolveSupplierInvoiceRequest("APPROVE", "checked"));
    assertThat("held to the order's store, it is decided", orders.writes, is(1));
  }

  // ── reads of an order, and what hangs off it ────────────────────────────────

  @Test
  @DisplayName(
      "Reading store B's order, its lines, trail, receipts, match or returns: a caller held to"
          + " store A is refused STORE_ACCESS_DENIED and nothing past the order is read")
  void anOrderIsReadAtItsStore() {
    PurchaseOrder po = orderAtB(Domain.PO_SUBMITTED);
    Orders orders = new Orders(po);
    PurchaseService svc = purchases(orders);
    for (String role : new String[] {"MANAGER", "STOREKEEPER", "CASHIER"}) {
      TenantContext a = heldToA(role);
      refused(403, "STORE_ACCESS_DENIED", () -> svc.getPurchaseOrder(a, po.id()));
      refused(403, "STORE_ACCESS_DENIED", () -> svc.listPurchaseOrderLines(a, po.id()));
      refused(403, "STORE_ACCESS_DENIED", () -> svc.purchaseOrderApprovals(a, po.id()));
      refused(403, "STORE_ACCESS_DENIED", () -> svc.listGoodsReceipts(a, po.id()));
      refused(403, "STORE_ACCESS_DENIED", () -> svc.matchPositions(a, po.id()));
      refused(403, "STORE_ACCESS_DENIED", () -> svc.listVendorReturns(a, po.id()));
      refused(404, "PURCHASE_PO_NOT_FOUND", () -> svc.getPurchaseOrder(theirs(role), po.id()));
    }
    assertThat("nothing past the order was read", orders.pastTheRecord, is(0));
    assertThat("no list was asked for", orders.listed, is(false));

    assertThat(svc.getPurchaseOrder(heldToB("STOREKEEPER"), po.id()).id(), is(po.id()));
    svc.listPurchaseOrderLines(wholeBusiness("MANAGER"), po.id());
    assertThat(orders.pastTheRecord, is(1));
  }

  @Test
  @DisplayName(
      "Lists of orders, invoices, returns and intercompany invoices hold to the caller's stores;"
          + " a caller held to none reads the whole business")
  void listsHoldToTheCallersStores() {
    Orders orders = new Orders(orderAtB(Domain.PO_DRAFT));
    PurchaseService svc = purchases(orders);

    svc.listPurchaseOrders(heldToA("MANAGER"), 20);
    assertThat(orders.storesAsked, is(Set.of(STORE_A)));
    svc.listPurchaseOrders(wholeBusiness("OWNER"), 20);
    assertThat(orders.storesAsked, is(nullValue()));

    svc.listSupplierInvoices(heldToA("MANAGER"), null, null, 20);
    assertThat(orders.storesAsked, is(Set.of(STORE_A)));
    svc.listSupplierInvoices(wholeBusiness("MANAGER"), null, null, 20);
    assertThat(orders.storesAsked, is(nullValue()));

    svc.listVendorReturns(heldToA("STOREKEEPER"), null);
    assertThat(orders.storesAsked, is(Set.of(STORE_A)));
    svc.listVendorReturns(wholeBusiness("OWNER"), null);
    assertThat(orders.storesAsked, is(nullValue()));

    svc.listIntercompanyInvoices(caller(TENANT, "MANAGER", Set.of(STORE_A, STORE_B)), 20);
    assertThat(orders.storesAsked, is(Set.of(STORE_A, STORE_B)));
    svc.listIntercompanyInvoices(wholeBusiness("OWNER"), 20);
    assertThat(orders.storesAsked, is(nullValue()));
  }

  // ── supplier invoices and returns to vendor ─────────────────────────────────

  @Test
  @DisplayName(
      "An invoice on store B's order is read at store B: a caller held to A is refused, another"
          + " business finds none, and nothing past the invoice is read")
  void anInvoiceIsReadAtItsOrdersStore() {
    PurchaseOrder po = orderAtB(Domain.PO_RECEIVED);
    Orders orders = new Orders(po);
    orders.invoice = flaggedInvoiceOn(po);
    PurchaseService svc = purchases(orders);
    UUID id = orders.invoice.id();

    refused(403, "STORE_ACCESS_DENIED", () -> svc.getSupplierInvoice(heldToA("MANAGER"), id));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.supplierInvoiceLines(heldToA("MANAGER"), id));
    refused(404, "PURCHASE_INVOICE_NOT_FOUND", () -> svc.getSupplierInvoice(theirs("OWNER"), id));
    assertThat(orders.pastTheRecord, is(0));

    assertThat(svc.getSupplierInvoice(heldToB("MANAGER"), id).id(), is(id));
    assertThat(svc.getSupplierInvoice(wholeBusiness("OWNER"), id).id(), is(id));
  }

  @Test
  @DisplayName(
      "A return from store B is read and credited at store B: a manager held to A is refused"
          + " STORE_ACCESS_DENIED and no credit note is recorded; another business finds none")
  void aReturnIsReadAndCreditedAtItsStore() {
    PurchaseOrder po = orderAtB(Domain.PO_RECEIVED);
    Orders orders = new Orders(po);
    orders.ret = returnAtB(po);
    PurchaseService svc = purchases(orders);
    UUID id = orders.ret.id();
    RecordCreditNoteRequest credit = new RecordCreditNoteRequest("CN-1", "2026-09-02", null);

    refused(403, "STORE_ACCESS_DENIED", () -> svc.getVendorReturn(heldToA("STOREKEEPER"), id));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.vendorReturnLines(heldToA("MANAGER"), id));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.recordCreditNote(heldToA("MANAGER"), id, credit));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      refused(404, "PURCHASE_RTV_NOT_FOUND", () -> svc.recordCreditNote(theirs(role), id, credit));
    }
    assertThat("nothing past the return was read", orders.pastTheRecord, is(0));
    assertThat("no credit note was recorded", orders.writes, is(0));
    assertThat(svc.getVendorReturn(heldToB("MANAGER"), id).id(), is(id));
  }

  // ── intercompany invoices: the two ends of a transfer ───────────────────────

  @Test
  @DisplayName(
      "A body that does not add up is refused for what it is, before the stores are judged: a"
          + " manager held to one end sending a gross that is not net plus VAT is answered 400"
          + " PURCHASE_IC_GROSS_MISMATCH, VAT on a VAT-group supply PURCHASE_IC_VAT_DISREGARDED, an"
          + " amount finer than its currency PURCHASE_AMOUNT_TOO_PRECISE — never 403 — and nothing"
          + " is written; the same body made whole is refused STORE_ACCESS_DENIED")
  void anIntercompanyBodyIsJudgedBeforeTheStores() {
    Orders orders = new Orders(orderAtB(Domain.PO_DRAFT));
    PurchaseService svc = purchases(orders);
    TenantContext atA = heldToA("MANAGER");
    refused(
        400,
        "PURCHASE_IC_GROSS_MISMATCH",
        () -> svc.raiseIntercompanyInvoices(aToB("10.00", "2.00", "11.00", false), atA));
    refused(
        400,
        "PURCHASE_IC_VAT_DISREGARDED",
        () -> svc.raiseIntercompanyInvoices(aToB("10.00", "2.00", "12.00", true), atA));
    refused(
        400,
        "PURCHASE_AMOUNT_TOO_PRECISE",
        () -> svc.raiseIntercompanyInvoices(aToB("10.001", "2.00", "12.001", false), atA));
    assertThat("nothing raised", orders.writes, is(0));
    refused(
        403,
        "STORE_ACCESS_DENIED",
        () -> svc.raiseIntercompanyInvoices(aToB("10.00", "2.00", "12.00", false), atA));
    assertThat("nothing raised", orders.writes, is(0));
  }

  private static RaiseIntercompanyInvoiceRequest aToB(
      String net, String vat, String gross, boolean vatDisregarded) {
    return new RaiseIntercompanyInvoiceRequest(
        STORE_A.toString(),
        STORE_B.toString(),
        null,
        new BigDecimal(net),
        new BigDecimal(vat),
        new BigDecimal(gross),
        null,
        vatDisregarded,
        "GBP");
  }

  @Test
  @DisplayName(
      "An intercompany pair writes both stores' books: a manager held to one end is refused"
          + " STORE_ACCESS_DENIED; either end reads it; each side is settled at its own store")
  void intercompanyInvoicesAreForTheirStores() {
    Orders orders = new Orders(orderAtB(Domain.PO_DRAFT));
    PurchaseService svc = purchases(orders);
    RaiseIntercompanyInvoiceRequest aToB =
        new RaiseIntercompanyInvoiceRequest(
            STORE_A.toString(),
            STORE_B.toString(),
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            BigDecimal.TEN,
            null,
            false,
            "GBP");
    refused(
        403, "STORE_ACCESS_DENIED", () -> svc.raiseIntercompanyInvoices(aToB, heldToA("MANAGER")));
    refused(
        403, "STORE_ACCESS_DENIED", () -> svc.raiseIntercompanyInvoices(aToB, heldToB("MANAGER")));
    assertThat("nothing raised", orders.writes, is(0));
    svc.raiseIntercompanyInvoices(aToB, caller(TENANT, "MANAGER", Set.of(STORE_A, STORE_B)));
    assertThat("held to both ends, it is raised", orders.writes, is(1));

    IntercompanyInvoice ar = intercompany(Domain.INV_AR);
    IntercompanyInvoice ap = intercompany(Domain.INV_AP);
    orders.intercompany.add(ar);
    orders.intercompany.add(ap);
    TenantContext elsewhere = caller(TENANT, "MANAGER", Set.of(Ids.newId()));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.getIntercompanyInvoice(elsewhere, ar.id()));
    assertThat(svc.getIntercompanyInvoice(heldToA("MANAGER"), ap.id()).id(), is(ap.id()));
    assertThat(svc.getIntercompanyInvoice(heldToB("MANAGER"), ar.id()).id(), is(ar.id()));
    refused(
        404,
        "PURCHASE_INVOICE_NOT_FOUND",
        () -> svc.getIntercompanyInvoice(theirs("OWNER"), ar.id()));

    // The receivable is the sending store's (A); the payable the receiving store's (B).
    refused(
        403,
        "STORE_ACCESS_DENIED",
        () -> svc.settleIntercompanyInvoice(heldToB("MANAGER"), ar.id()));
    refused(
        403,
        "STORE_ACCESS_DENIED",
        () -> svc.settleIntercompanyInvoice(heldToA("MANAGER"), ap.id()));
    refused(
        404,
        "PURCHASE_INVOICE_NOT_FOUND",
        () -> svc.settleIntercompanyInvoice(theirs("OWNER"), ar.id()));
    assertThat("nothing settled", orders.writes, is(1));
    svc.settleIntercompanyInvoice(heldToA("MANAGER"), ar.id());
    svc.settleIntercompanyInvoice(heldToB("MANAGER"), ap.id());
    assertThat(orders.writes, is(3));
  }

  private static IntercompanyInvoice intercompany(String side) {
    return new IntercompanyInvoice(
        Ids.newId(),
        TENANT,
        side,
        STORE_A,
        STORE_B,
        null,
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.TEN,
        "T1",
        false,
        Domain.INV_RAISED,
        LocalDate.of(2026, 9, 1),
        LocalDate.of(2026, 10, 1),
        "GBP",
        Instant.now());
  }

  // ── the ledger, read ────────────────────────────────────────────────────────

  private static final class Clearing extends com.storeql.purchase.repo.SalesPostingRepository {
    Set<UUID> storesAsked;
    boolean asked;

    @Override
    public List<Domain.OpenClearing> findOpenClearing(UUID tenantId, Set<UUID> stores, int limit) {
      asked = true;
      storesAsked = stores;
      return List.of();
    }
  }

  @Test
  @DisplayName(
      "The ledger is read at the caller's stores: a trial balance or clearing list naming another"
          + " store is refused, naming none reads theirs; a journal posted elsewhere is refused")
  void theLedgerIsReadAtTheCallersStores() {
    Orders orders = new Orders(orderAtB(Domain.PO_DRAFT));
    PurchaseService svc = purchases(orders);
    TenantContext a = heldToA("MANAGER");

    refused(403, "STORE_ACCESS_DENIED", () -> svc.trialBalance(a, null, null, STORE_B.toString()));
    assertThat("a refused store reads nothing", orders.listed, is(false));
    svc.trialBalance(a, null, null, null);
    assertThat(orders.storesAsked, is(Set.of(STORE_A)));
    svc.trialBalance(wholeBusiness("OWNER"), null, null, STORE_B.toString());
    assertThat(orders.storesAsked, is(Set.of(STORE_B)));
    svc.trialBalance(wholeBusiness("OWNER"), null, null, null);
    assertThat(orders.storesAsked, is(nullValue()));

    svc.getNominalLedger(heldToA("CASHIER"), null, null, null, null, 20);
    assertThat(orders.storesAsked, is(Set.of(STORE_A)));
    svc.getNominalLedger(wholeBusiness("OWNER"), null, null, null, null, 20);
    assertThat(orders.storesAsked, is(nullValue()));

    UUID atB = Ids.newId();
    UUID ofTheBusiness = Ids.newId();
    orders.journal.add(line(atB, STORE_B));
    orders.journal.add(line(ofTheBusiness, null));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.getJournal(a, atB));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.getJournal(a, ofTheBusiness));
    refused(404, "PURCHASE_JOURNAL_NOT_FOUND", () -> svc.getJournal(theirs("OWNER"), atB));
    assertThat(svc.getJournal(heldToB("MANAGER"), atB).journalId(), is(atB));
    assertThat(
        svc.getJournal(wholeBusiness("OWNER"), ofTheBusiness).journalId(), is(ofTheBusiness));

    Clearing clearing = new Clearing();
    SalesPostingService sales = new SalesPostingService();
    sales.repo = clearing;
    refused(403, "STORE_ACCESS_DENIED", () -> sales.openClearing(a, STORE_B.toString(), 50));
    assertThat(clearing.asked, is(false));
    sales.openClearing(a, null, 50);
    assertThat(clearing.storesAsked, is(Set.of(STORE_A)));
    sales.openClearing(wholeBusiness("MANAGER"), null, 50);
    assertThat(clearing.storesAsked, is(nullValue()));
  }

  private static NominalLedgerEntry line(UUID journalId, UUID storeId) {
    return new NominalLedgerEntry(
        Ids.newId(),
        TENANT,
        LocalDate.of(2026, 9, 1),
        "1200",
        "Stock",
        BigDecimal.TEN,
        BigDecimal.ZERO,
        "Stock count",
        null,
        Instant.now(),
        journalId,
        Domain.SOURCE_JOURNAL,
        storeId);
  }

  // ── a supplier's deliveries ─────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A supplier's deliveries are read into the caller's stores; held to none, every store's")
  void aSuppliersDeliveriesHoldToTheCallersStores() {
    UUID supplierId = Ids.newId();
    java.util.concurrent.atomic.AtomicReference<Set<UUID>> asked =
        new java.util.concurrent.atomic.AtomicReference<>();
    boolean[] read = new boolean[1];
    SupplierPerformanceService svc = new SupplierPerformanceService();
    svc.purchases =
        new PurchaseRepository() {
          @Override
          public Optional<Domain.Supplier> findSupplier(UUID tenantId, UUID id) {
            return TENANT.equals(tenantId) && supplierId.equals(id)
                ? Optional.of(
                    new Domain.Supplier(
                        supplierId,
                        TENANT,
                        "Acme",
                        null,
                        false,
                        "GB",
                        "GBP",
                        30,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                : Optional.empty();
          }
        };
    svc.repo =
        new com.storeql.purchase.repo.SupplierPerformanceRepository() {
          @Override
          public List<com.storeql.purchase.domain.SupplierScorecard.Delivery> deliveries(
              UUID tenantId, UUID supplier, LocalDate from, LocalDate to, Set<UUID> stores) {
            read[0] = true;
            asked.set(stores);
            return List.of();
          }
        };
    svc.deliveries(heldToA("MANAGER"), supplierId, null, null);
    assertThat(asked.get(), is(Set.of(STORE_A)));
    svc.deliveries(wholeBusiness("OWNER"), supplierId, null, null);
    assertThat(read[0], is(true));
    assertThat(asked.get(), is(nullValue()));
    refused(
        404,
        "PURCHASE_SUPPLIER_NOT_FOUND",
        () -> svc.deliveries(theirs("OWNER"), supplierId, null, null));
  }

  // ── landed costs ────────────────────────────────────────────────────────────

  private static final class Charges extends LandedCostRepository {
    final GoodsReceipt receipt;
    final Charge charge;
    Set<UUID> storesAsked;
    int pastTheRecord;

    Charges(GoodsReceipt receipt, Charge charge) {
      this.receipt = receipt;
      this.charge = charge;
    }

    @Override
    public Optional<GoodsReceipt> findGoodsReceipt(UUID tenantId, UUID grId) {
      return receipt.tenantId().equals(tenantId) && receipt.id().equals(grId)
          ? Optional.of(receipt)
          : Optional.empty();
    }

    @Override
    public Optional<Charge> find(UUID tenantId, UUID id) {
      return charge.tenantId().equals(tenantId) && charge.id().equals(id)
          ? Optional.of(charge)
          : Optional.empty();
    }

    @Override
    public List<Charge> list(UUID tenantId, UUID grId, UUID poId, Set<UUID> stores, int limit) {
      storesAsked = stores;
      pastTheRecord++;
      return List.of();
    }

    @Override
    public List<LandedCost.Line> lines(UUID tenantId, UUID landedCostId) {
      pastTheRecord++;
      return List.of();
    }
  }

  @Test
  @DisplayName(
      "A landed cost on store B's receipt is read at store B; a list by receipt checks the"
          + " receipt's store, and a list of everything holds to the caller's stores")
  void landedCostsAreReadAtTheirStore() {
    PurchaseOrder po = orderAtB(Domain.PO_RECEIVED);
    GoodsReceipt gr =
        new GoodsReceipt(Ids.newId(), TENANT, po.id(), STORE_B, Instant.now(), Instant.now(), null);
    Charge charge =
        new Charge(
            Ids.newId(),
            TENANT,
            gr.id(),
            po.id(),
            STORE_B,
            "FREIGHT",
            "VALUE",
            "GBP",
            BigDecimal.TEN,
            null,
            null,
            null,
            LandedCost.STATUS_APPLIED,
            Instant.now(),
            null,
            null,
            null,
            null,
            null);
    Charges charges = new Charges(gr, charge);
    LandedCostService svc = new LandedCostService();
    svc.repo = charges;
    svc.purchase = purchases(new Orders(po));

    refused(403, "STORE_ACCESS_DENIED", () -> svc.get(heldToA("MANAGER"), charge.id()));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.lines(heldToA("MANAGER"), charge.id()));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.list(heldToA("MANAGER"), gr.id(), null));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.list(heldToA("MANAGER"), null, po.id()));
    refused(404, "PURCHASE_LANDED_NOT_FOUND", () -> svc.get(theirs("OWNER"), charge.id()));
    refused(404, "PURCHASE_GRN_NOT_FOUND", () -> svc.list(theirs("OWNER"), gr.id(), null));
    assertThat("nothing past the record was read", charges.pastTheRecord, is(0));

    svc.list(heldToA("MANAGER"), null, null);
    assertThat(charges.storesAsked, is(Set.of(STORE_A)));
    svc.list(wholeBusiness("OWNER"), null, null);
    assertThat(charges.storesAsked, is(nullValue()));
    assertThat(svc.get(heldToB("MANAGER"), charge.id()).id(), is(charge.id()));
  }

  // ── requests for quotes ─────────────────────────────────────────────────────

  private static final class Requests extends RfqRepository {
    final Header header;
    int pastTheRecord;
    int writes;
    Set<UUID> storesAsked;

    Requests(Header header) {
      this.header = header;
    }

    @Override
    public Optional<Header> find(UUID tenantId, UUID id) {
      return header.tenantId().equals(tenantId) && header.id().equals(id)
          ? Optional.of(header)
          : Optional.empty();
    }

    @Override
    public List<Summary> list(UUID tenantId, String status, Set<UUID> stores, int limit) {
      storesAsked = stores;
      return List.of();
    }

    @Override
    public List<Rfq.Line> lines(UUID tenantId, UUID rfqId) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public List<Rfq.Bid> bids(UUID tenantId, UUID rfqId) {
      pastTheRecord++;
      return List.of();
    }

    @Override
    public boolean issue(UUID tenantId, UUID id) {
      writes++;
      return true;
    }

    @Override
    public boolean cancel(UUID tenantId, UUID id, String reason) {
      writes++;
      return true;
    }

    @Override
    public boolean decline(UUID tenantId, UUID rfqId, UUID supplierId) {
      writes++;
      return true;
    }
  }

  private static Header requestAtB(String status) {
    return new Header(
        Ids.newId(),
        TENANT,
        "RFQ-1",
        "Shelving",
        STORE_B,
        status,
        null,
        null,
        null,
        null,
        Instant.now(),
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName(
      "A request for quotes raised for store B is read, issued, quoted, declined, awarded and"
          + " cancelled at store B: a buyer held to A is refused STORE_ACCESS_DENIED first")
  void aRequestForQuotesIsForItsStore() {
    for (String status : new String[] {Rfq.DRAFT, Rfq.ISSUED}) {
      Header h = requestAtB(status);
      Requests requests = new Requests(h);
      RfqService svc = new RfqService();
      svc.repo = requests;
      UUID supplier = Ids.newId();
      for (String role : new String[] {"MANAGER", "STOREKEEPER"}) {
        TenantContext a = heldToA(role);
        refused(403, "STORE_ACCESS_DENIED", () -> svc.detail(a, h.id()));
        refused(403, "STORE_ACCESS_DENIED", () -> svc.issue(a, h.id()));
        refused(
            403,
            "STORE_ACCESS_DENIED",
            () ->
                svc.quote(
                    a, h.id(), supplier, new RfqQuoteRequest(null, null, null, null, List.of())));
        refused(403, "STORE_ACCESS_DENIED", () -> svc.decline(a, h.id(), supplier));
        refused(
            403, "STORE_ACCESS_DENIED", () -> svc.award(a, h.id(), new RfqAwardRequest(List.of())));
        refused(403, "STORE_ACCESS_DENIED", () -> svc.cancel(a, h.id(), "not needed"));
        refused(404, "PURCHASE_RFQ_NOT_FOUND", () -> svc.issue(theirs(role), h.id()));
        refused(404, "PURCHASE_RFQ_NOT_FOUND", () -> svc.cancel(theirs(role), h.id(), "x"));
      }
      assertThat(status + ": nothing past the request was read", requests.pastTheRecord, is(0));
      assertThat(status + ": nothing was written", requests.writes, is(0));

      svc.list(heldToA("STOREKEEPER"), null, 20);
      assertThat(requests.storesAsked, is(Set.of(STORE_A)));
      svc.list(wholeBusiness("OWNER"), null, 20);
      assertThat(requests.storesAsked, is(nullValue()));
    }
  }

  // ── what gathers every store's records: made by the whole business ─────────

  /**
   * Statements of ours, each with the sales it gathered at the stores named (null: a sale that
   * named no store); every read and write counted.
   */
  private static final class Consignments extends ConsignmentRepository {
    int written;
    boolean listed;
    Set<UUID> storesAsked;
    final java.util.Map<UUID, ConsignmentSettlement> statements = new java.util.HashMap<>();
    final java.util.Map<UUID, List<ConsignmentSale>> salesOf = new java.util.HashMap<>();

    UUID statementOf(UUID... stores) {
      UUID id = Ids.newId();
      UUID supplier = Ids.newId();
      List<ConsignmentSale> sales = new ArrayList<>();
      for (UUID store : stores) {
        sales.add(
            new ConsignmentSale(
                Ids.newId(),
                TENANT,
                Ids.newId(),
                supplier,
                store,
                Ids.newId(),
                null,
                null,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.TEN,
                "GBP",
                LocalDate.of(2026, 9, 1),
                id,
                Instant.now()));
      }
      statements.put(
          id,
          new ConsignmentSettlement(
              id,
              TENANT,
              supplier,
              "CS-1",
              LocalDate.of(2026, 9, 1),
              LocalDate.of(2026, 9, 30),
              "GBP",
              BigDecimal.TEN.multiply(BigDecimal.valueOf(stores.length)),
              stores.length,
              null,
              Instant.now()));
      salesOf.put(id, sales);
      return id;
    }

    @Override
    public ConsignmentSettlement settle(ConsignmentSettlement s) {
      written++;
      return s;
    }

    @Override
    public List<ConsignmentSettlement> findSettlements(
        UUID tenantId, UUID supplierId, Set<UUID> stores, int limit) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }

    @Override
    public Optional<ConsignmentSettlement> findSettlement(UUID tenantId, UUID id) {
      return TENANT.equals(tenantId) ? Optional.ofNullable(statements.get(id)) : Optional.empty();
    }

    @Override
    public List<ConsignmentSale> findSalesOfSettlement(UUID tenantId, UUID settlementId) {
      return TENANT.equals(tenantId) ? salesOf.getOrDefault(settlementId, List.of()) : List.of();
    }

    @Override
    public List<ConsignmentSale> findSales(
        UUID tenantId, UUID supplierId, Boolean settled, Set<UUID> stores, int limit) {
      storesAsked = stores;
      return List.of();
    }
  }

  @Test
  @DisplayName(
      "A consignment statement gathers every store's sales of a supplier: only the whole business"
          + " makes one (BUSINESS_WIDE_ONLY, nothing written); a manager held to stores lists and"
          + " reads the statements made wholly of their stores' sales, is refused one that holds"
          + " another store's sale (STORE_ACCESS_DENIED), and the sales list holds to theirs")
  void consignmentStatementsAreMadeByTheWholeBusinessAndReadWhereTheirSalesWere() {
    Consignments repo = new Consignments();
    ConsignmentService svc = new ConsignmentService();
    svc.repo = repo;
    TenantContext a = heldToA("MANAGER");
    UUID supplier = Ids.newId();
    refused(
        403,
        "BUSINESS_WIDE_ONLY",
        () ->
            svc.settle(
                a, new CreateConsignmentSettlementRequest(supplier, "2026-01-01", "2026-01-31")));
    assertThat("nothing was written", repo.written, is(0));

    // The list answers a manager held to stores, with the statements of their stores alone.
    svc.listSettlements(a, null, 20);
    assertThat(repo.listed, is(true));
    assertThat(repo.storesAsked, is(Set.of(STORE_A)));
    svc.listSettlements(caller(TENANT, "MANAGER", Set.of(STORE_A, STORE_B)), null, 20);
    assertThat(repo.storesAsked, is(Set.of(STORE_A, STORE_B)));
    svc.listSettlements(wholeBusiness("MANAGER"), null, 20);
    assertThat(repo.storesAsked, is(nullValue()));

    UUID atA = repo.statementOf(STORE_A, STORE_A);
    UUID atAAndB = repo.statementOf(STORE_A, STORE_B);
    UUID unplaced = repo.statementOf(STORE_A, null);

    // Wholly store A's: read at store A, refused at store B.
    assertThat(svc.getSettlement(a, atA).id(), is(atA));
    assertThat(svc.settlementSales(a, atA).size(), is(2));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.getSettlement(heldToB("MANAGER"), atA));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.settlementSales(heldToB("MANAGER"), atA));
    // One that holds another store's sale, or a sale that named no store, is not theirs.
    for (UUID id : List.of(atAAndB, unplaced)) {
      refused(403, "STORE_ACCESS_DENIED", () -> svc.getSettlement(a, id));
      refused(403, "STORE_ACCESS_DENIED", () -> svc.settlementSales(a, id));
    }
    // Held to both stores, the spanning one is theirs; held to none, every one is.
    TenantContext ab = caller(TENANT, "MANAGER", Set.of(STORE_A, STORE_B));
    assertThat(svc.getSettlement(ab, atAAndB).id(), is(atAAndB));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.getSettlement(ab, unplaced));
    for (UUID id : List.of(atA, atAAndB, unplaced)) {
      assertThat(svc.getSettlement(wholeBusiness("MANAGER"), id).id(), is(id));
      assertThat(svc.settlementSales(wholeBusiness("OWNER"), id).size(), is(2));
    }
    // Unknown here, or another business's: not found, whoever asks.
    refused(
        404, "PURCHASE_CONSIGNMENT_SETTLEMENT_NOT_FOUND", () -> svc.getSettlement(a, Ids.newId()));
    for (UUID id : List.of(atA, atAAndB)) {
      refused(
          404,
          "PURCHASE_CONSIGNMENT_SETTLEMENT_NOT_FOUND",
          () -> svc.getSettlement(theirs("OWNER"), id));
      refused(
          404,
          "PURCHASE_CONSIGNMENT_SETTLEMENT_NOT_FOUND",
          () -> svc.getSettlement(caller(OTHER_TENANT, "MANAGER", Set.of(STORE_A)), id));
      assertThat(svc.settlementSales(theirs("OWNER"), id).size(), is(0));
    }

    svc.listSales(a, null, null, 20);
    assertThat(repo.storesAsked, is(Set.of(STORE_A)));
    svc.listSales(wholeBusiness("MANAGER"), null, null, 20);
    assertThat(repo.storesAsked, is(nullValue()));
  }

  private static final class Arrangements extends DropshipRepository {
    int touched;

    @Override
    public DropshipArrangement create(DropshipArrangement a, OutboxRow event) {
      touched++;
      return a;
    }

    @Override
    public Optional<DropshipArrangement> find(UUID tenantId, UUID id) {
      touched++;
      return Optional.empty();
    }

    @Override
    public void end(UUID tenantId, UUID id, OutboxRow event) {
      touched++;
    }
  }

  @Test
  @DisplayName(
      "A dropship arrangement sources a product for every store: a manager held to stores neither"
          + " makes nor ends one (BUSINESS_WIDE_ONLY), and nothing is announced")
  void dropshipArrangementsAreTheWholeBusinesss() {
    Arrangements repo = new Arrangements();
    DropshipService svc = new DropshipService();
    svc.repo = repo;
    TenantContext a = heldToA("MANAGER");
    refused(
        403,
        "BUSINESS_WIDE_ONLY",
        () ->
            svc.create(
                a,
                new CreateDropshipArrangementRequest(
                    Ids.newId(), Ids.newId(), BigDecimal.ONE, null)));
    refused(403, "BUSINESS_WIDE_ONLY", () -> svc.end(a, Ids.newId()));
    assertThat(repo.touched, is(0));
  }

  private static final class Releases extends DutyRepository {
    Set<UUID> storesAsked;
    boolean asked;

    @Override
    public List<DutyRelease> findReleases(
        UUID tenantId, LocalDate from, LocalDate to, Set<UUID> stores) {
      asked = true;
      storesAsked = stores;
      return List.of();
    }
  }

  @Test
  @DisplayName("Duty releases are read for the caller's stores; held to none, the whole business")
  void dutyReleasesHoldToTheCallersStores() {
    Releases repo = new Releases();
    DutyService svc = new DutyService();
    svc.repo = repo;
    svc.releases(heldToA("MANAGER"), "2026-01-01", "2026-01-31");
    assertThat(repo.storesAsked, is(Set.of(STORE_A)));
    svc.releases(wholeBusiness("OWNER"), "2026-01-01", "2026-01-31");
    assertThat(repo.asked, is(true));
    assertThat(repo.storesAsked, is(nullValue()));
  }
}
