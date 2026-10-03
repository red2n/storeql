package com.storeql.purchase.service;

import static com.storeql.purchase.service.StoreHeldCallersTest.caller;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Invoice;
import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.EInvoiceIntake;
import com.storeql.purchase.domain.EInvoiceIntake.ItemCode;
import com.storeql.purchase.domain.EInvoiceIntake.LineMatch;
import com.storeql.purchase.domain.EInvoiceIntake.SupplierRef;
import com.storeql.purchase.domain.SupplierEInvoices.Document;
import com.storeql.purchase.domain.SupplierEInvoices.Line;
import com.storeql.purchase.domain.SupplierEInvoices.Original;
import com.storeql.purchase.dto.EInvoiceDtos.MatchSupplierEInvoiceRequest;
import com.storeql.purchase.dto.EInvoiceDtos.RefuseSupplierEInvoiceRequest;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.purchase.repo.SupplierEInvoiceRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * A supplier e-invoice is the business's inbox until it names an order; from then on it is the
 * order's store's, as the order's invoice is. A caller held to other stores is refused {@code 403
 * STORE_ACCESS_DENIED} reading it, its original, refusing or matching it — before the document is
 * claimed, a rule learnt or the ledger touched — and lists hold to the documents with no order yet
 * and those billing an order at one of the caller's stores. Matching a document to an order at
 * another store is refused before anything about the order is decided. Another business finds none
 * of it ({@code 404}). No database: every repository is a stand-in that counts what it is asked.
 */
class SupplierEInvoiceStoreScopeTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID OTHER_TENANT = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();
  private static final UUID SUPPLIER = Ids.newId();
  private static final String SUPPLIER_VAT = "GB999999973";

  // ── callers ──────────────────────────────────────────────────────────────────

  private static TenantContext heldToA() {
    return caller(TENANT, "MANAGER", Set.of(STORE_A));
  }

  private static TenantContext heldToB() {
    return caller(TENANT, "MANAGER", Set.of(STORE_B));
  }

  private static TenantContext wholeBusiness() {
    return caller(TENANT, "MANAGER", Set.of());
  }

  /** Another business's staff, held to none or naming our store A's id. */
  private static List<TenantContext> theirs() {
    return List.of(
        caller(OTHER_TENANT, "OWNER", Set.of()),
        caller(OTHER_TENANT, "MANAGER", Set.of()),
        caller(OTHER_TENANT, "MANAGER", Set.of(STORE_A)),
        caller(OTHER_TENANT, "MANAGER", Set.of(STORE_B)));
  }

  private static void refused(int status, String code, Executable call) {
    ApiException e = assertThrows(ApiException.class, call);
    assertThat(e.getMessage(), e.status(), is(status));
    assertThat(e.getMessage(), e.code(), is(code));
  }

  // ── stand-ins ────────────────────────────────────────────────────────────────

  private static PurchaseOrder order(UUID store, String status) {
    Instant now = Instant.now();
    return new PurchaseOrder(
        Ids.newId(),
        TENANT,
        SUPPLIER,
        store,
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

  /** The business's orders, and every read past one of them counted. */
  private static class Orders extends PurchaseRepository {
    final List<PurchaseOrder> orders;
    int pastTheOrder;

    Orders(PurchaseOrder... orders) {
      this.orders = List.of(orders);
    }

    @Override
    public Optional<PurchaseOrder> findPurchaseOrder(UUID tenantId, UUID id) {
      return orders.stream()
          .filter(o -> o.tenantId().equals(tenantId) && o.id().equals(id))
          .findFirst();
    }

    @Override
    public List<PurchaseOrderLine> findPurchaseOrderLines(UUID tenantId, UUID poId) {
      pastTheOrder++;
      return List.of();
    }

    @Override
    public List<Domain.VendorReturn> findVendorReturns(UUID tenantId, UUID poId) {
      pastTheOrder++;
      return List.of();
    }

    @Override
    public Optional<Domain.Supplier> findSupplier(UUID tenantId, UUID id) {
      return Optional.empty();
    }
  }

  /** One document of ours, its bytes, and every claim, settle and rule learnt counted. */
  private static class Inbox extends SupplierEInvoiceRepository {
    Document doc;
    final byte[] bytes;
    int originalsRead;
    int claims;
    int settled;
    int learnt;
    Set<UUID> storesAsked = Set.of();
    boolean listed;

    Inbox(Document doc, byte[] bytes) {
      this.doc = doc;
      this.bytes = bytes;
    }

    @Override
    public Optional<Document> find(UUID tenantId, UUID id) {
      return doc.tenantId().equals(tenantId) && doc.id().equals(id)
          ? Optional.of(doc)
          : Optional.empty();
    }

    @Override
    public List<Document> list(UUID tenantId, String status, Set<UUID> stores, int limit) {
      listed = true;
      storesAsked = stores;
      return List.of();
    }

    @Override
    public Optional<UUID> findIdBySha(UUID tenantId, String sha256) {
      return doc.tenantId().equals(tenantId) ? Optional.of(doc.id()) : Optional.empty();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID einvoiceId) {
      return List.of();
    }

    @Override
    public Optional<Original> original(UUID tenantId, UUID id) {
      if (!doc.tenantId().equals(tenantId) || !doc.id().equals(id)) return Optional.empty();
      originalsRead++;
      return Optional.of(new Original("application/xml", "XML", doc.invoiceNumber(), bytes));
    }

    @Override
    public boolean claim(UUID tenantId, UUID id, UUID token, Collection<String> fromStatuses) {
      claims++;
      return true;
    }

    @Override
    public void release(UUID tenantId, UUID id, UUID token) {}

    @Override
    public boolean settle(
        UUID tenantId,
        UUID id,
        UUID token,
        String status,
        String problem,
        UUID supplierId,
        UUID poId,
        UUID supplierInvoiceId,
        UUID vendorReturnId,
        List<LineMatch> matches,
        Instant decidedAt,
        UUID decidedBy,
        String decisionReason) {
      settled++;
      return true;
    }

    @Override
    public List<ItemCode> itemCodes(UUID tenantId, UUID supplierId) {
      return List.of();
    }

    @Override
    public void learn(
        UUID id,
        UUID tenantId,
        UUID supplierId,
        String kind,
        String code,
        UUID variantId,
        UUID einvoiceId,
        UUID learntBy) {
      learnt++;
    }

    @Override
    public List<SupplierRef> supplierRefs(UUID tenantId) {
      return List.of(new SupplierRef(SUPPLIER, "Acme Wholesale", SUPPLIER_VAT, null, null));
    }

    @Override
    public Optional<UUID> orderOfInvoice(UUID tenantId, UUID supplierId, String invoiceNumber) {
      return Optional.empty();
    }
  }

  /** No identity on file: nothing is misdirected. */
  private static final class NoIdentity extends TenantProfiles {
    @Override
    public Optional<Identity> identity(UUID tenantId) {
      return Optional.empty();
    }
  }

  private static Document document(UUID poId, String status) {
    Instant now = Instant.now();
    return new Document(
        Ids.newId(),
        TENANT,
        now,
        null,
        "UPLOAD",
        null,
        "application/xml",
        "XML",
        "UBL",
        null,
        "sha",
        Invoice.PEPPOL_BIS_3,
        "380",
        "INV-1",
        LocalDate.of(2026, 9, 10),
        "GBP",
        "Supplier Ltd",
        SUPPLIER_VAT,
        null,
        null,
        null,
        poId == null ? null : poId.toString(),
        null,
        BigDecimal.TEN,
        BigDecimal.ZERO,
        BigDecimal.TEN,
        BigDecimal.TEN,
        "[]",
        status,
        "waiting",
        poId == null ? null : SUPPLIER,
        poId,
        null,
        null,
        null,
        null,
        null,
        now);
  }

  private static SupplierEInvoiceService service(Orders orders, Inbox inbox) {
    PurchaseService purchasing = new PurchaseService();
    purchasing.repo = orders;
    SupplierEInvoiceService s = new SupplierEInvoiceService();
    s.repo = inbox;
    s.purchases = orders;
    s.purchasing = purchasing;
    s.tenants = new NoIdentity();
    return s;
  }

  private static final RefuseSupplierEInvoiceRequest REFUSE =
      new RefuseSupplierEInvoiceRequest("not ours");

  private static MatchSupplierEInvoiceRequest choose(UUID poId) {
    return new MatchSupplierEInvoiceRequest(null, poId, null, List.of(), false);
  }

  // ── a document that bills an order ──────────────────────────────────────────

  @Test
  @DisplayName(
      "A document billing store B's order: a manager held to store A is refused"
          + " STORE_ACCESS_DENIED reading it, its original, refusing or matching it, before the"
          + " original is read or the document claimed; another business finds none; held to B, it"
          + " is read and refused")
  void aDocumentBillingAnOrderIsItsOrdersStores() {
    PurchaseOrder atB = order(STORE_B, Domain.PO_RECEIVED);
    Orders orders = new Orders(atB);
    Inbox inbox =
        new Inbox(document(atB.id(), EInvoiceIntake.STATUS_NEEDS_LINES), ubl(atB.id().toString()));
    SupplierEInvoiceService svc = service(orders, inbox);
    UUID id = inbox.doc.id();

    TenantContext a = heldToA();
    refused(403, "STORE_ACCESS_DENIED", () -> svc.get(a, id));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.original(a, id));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.refuse(a, id, REFUSE));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.match(a, id, choose(null)));
    for (TenantContext them : theirs()) {
      refused(404, "PURCHASE_EINVOICE_NOT_FOUND", () -> svc.get(them, id));
      refused(404, "PURCHASE_EINVOICE_NOT_FOUND", () -> svc.original(them, id));
      refused(404, "PURCHASE_EINVOICE_NOT_FOUND", () -> svc.refuse(them, id, REFUSE));
      refused(404, "PURCHASE_EINVOICE_NOT_FOUND", () -> svc.match(them, id, choose(null)));
    }
    assertThat("the original was never read", inbox.originalsRead, is(0));
    assertThat("nothing was claimed", inbox.claims, is(0));
    assertThat("nothing was settled", inbox.settled, is(0));
    assertThat("nothing about the order was read", orders.pastTheOrder, is(0));

    TenantContext b = heldToB();
    assertThat(svc.get(b, id).document().id(), is(id));
    assertThat(svc.original(b, id).bytes().length > 0, is(true));
    svc.refuse(b, id, REFUSE);
    assertThat("held to the order's store, it is refused", inbox.settled, is(1));
  }

  @Test
  @DisplayName(
      "The same bytes sent again by a manager held to store A, when the document already bills"
          + " store B's order, are refused STORE_ACCESS_DENIED rather than shown")
  void sendingTheSameBytesAgainShowsOnlyWhatIsTheirs() {
    PurchaseOrder atB = order(STORE_B, Domain.PO_RECEIVED);
    Inbox inbox =
        new Inbox(document(atB.id(), EInvoiceIntake.STATUS_CAPTURED), ubl(atB.id().toString()));
    SupplierEInvoiceService svc = service(new Orders(atB), inbox);
    refused(
        403, "STORE_ACCESS_DENIED", () -> svc.receive(heldToA(), inbox.bytes, "application/xml"));
    assertThat(svc.receive(heldToB(), inbox.bytes, "application/xml").alreadyReceived(), is(true));
  }

  /** An inbox that has never seen the bytes: it keeps what is inserted and reads it back. */
  private static final class EmptyInbox extends Inbox {
    Document stored;
    List<Line> storedLines = List.of();

    EmptyInbox() {
      super(null, new byte[0]);
    }

    @Override
    public Optional<UUID> findIdBySha(UUID tenantId, String sha256) {
      return Optional.empty();
    }

    @Override
    public long documentBytes(UUID tenantId) {
      return 0;
    }

    @Override
    public boolean insert(Document d, byte[] bytes, List<Line> lines) {
      stored = d;
      storedLines = lines;
      return true;
    }

    @Override
    public Optional<Document> find(UUID tenantId, UUID id) {
      return stored != null && stored.tenantId().equals(tenantId) && stored.id().equals(id)
          ? Optional.of(stored)
          : Optional.empty();
    }

    @Override
    public List<Line> lines(UUID tenantId, UUID einvoiceId) {
      return storedLines;
    }
  }

  /** No plan cap on documents. */
  private static final com.storeql.service.Entitlements NO_CAP =
      new com.storeql.service.Entitlements() {
        @Override
        public void requireBytesWithin(
            UUID tenantId, String key, String what, java.util.function.LongSupplier bytesAfter) {}
      };

  @Test
  @DisplayName(
      "A manager held to store A uploads a document naming store B's order: it is kept"
          + " NEEDS_DECISION against that order for a person at B, never captured, nothing about"
          + " the order read — and the sender is answered without the order's id, what it became"
          + " or anything of its status; held to B, the sender sees the order")
  void anUploadBillingAnotherStoresOrderIsAnsweredWithoutIt() {
    PurchaseOrder atB = order(STORE_B, Domain.PO_DRAFT);
    Orders orders = new Orders(atB);
    byte[] bytes = ubl(atB.id().toString());

    EmptyInbox inbox = new EmptyInbox();
    SupplierEInvoiceService svc = service(orders, inbox);
    svc.entitlements = NO_CAP;
    SupplierEInvoiceService.Receipt r = svc.receive(heldToA(), bytes, "application/xml");

    assertThat(r.alreadyReceived(), is(false));
    assertThat("the answer leaves the order out", r.document().poId(), is(nullValue()));
    assertThat(r.document().supplierInvoiceId(), is(nullValue()));
    assertThat(r.document().status(), is(EInvoiceIntake.STATUS_NEEDS_DECISION));
    assertThat(
        "nothing of the order's status is said",
        r.document().problem().contains(Domain.PO_DRAFT),
        is(false));
    assertThat(
        r.lines().stream().allMatch(l -> l.poLineId() == null && l.matchedBy() == null), is(true));
    assertThat("kept against the order, for its store", inbox.stored.poId(), is(atB.id()));
    assertThat(inbox.stored.status(), is(EInvoiceIntake.STATUS_NEEDS_DECISION));
    assertThat(inbox.stored.problem(), is(SupplierEInvoiceService.BILLS_ANOTHER_STORE));
    assertThat("nothing about the order was read", orders.pastTheOrder, is(0));
    assertThat("never claimed for a capture", inbox.claims, is(0));

    // Held to B, the same document is the sender's to see — and the draft order waits for it.
    EmptyInbox atTheirStore = new EmptyInbox();
    SupplierEInvoiceService theirs = service(orders, atTheirStore);
    theirs.entitlements = NO_CAP;
    SupplierEInvoiceService.Receipt seen = theirs.receive(heldToB(), bytes, "application/xml");
    assertThat(seen.document().poId(), is(atB.id()));
    assertThat(seen.document().status(), is(EInvoiceIntake.STATUS_NEEDS_ORDER));
  }

  // ── remembering a supplier's address ────────────────────────────────────────

  /** The orders, the one supplier (with no electronic address yet) and every update counted. */
  private static final class Suppliers extends Orders {
    int updated;

    Suppliers(PurchaseOrder... orders) {
      super(orders);
    }

    @Override
    public Optional<Domain.Supplier> findSupplier(UUID tenantId, UUID id) {
      if (!TENANT.equals(tenantId) || !SUPPLIER.equals(id)) return Optional.empty();
      Instant now = Instant.now();
      return Optional.of(
          new Domain.Supplier(
              SUPPLIER,
              TENANT,
              "Acme Wholesale",
              SUPPLIER_VAT,
              true,
              "GB",
              "GBP",
              30,
              now,
              now,
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
              null));
    }

    @Override
    public boolean updateSupplier(Domain.Supplier s) {
      updated++;
      return true;
    }
  }

  @Test
  @DisplayName(
      "A match with remember by a manager held to the order's store goes ahead but leaves the"
          + " supplier's record alone, saying so (notRemembered); held to none, the supplier's"
          + " electronic address is kept")
  void rememberingTheAddressIsTheWholeBusinesss() {
    PurchaseOrder atA = order(STORE_A, Domain.PO_RECEIVED);
    Suppliers suppliers = new Suppliers(atA);
    Inbox inbox = new Inbox(document(null, EInvoiceIntake.STATUS_NEEDS_ORDER), ubl("4500012345"));
    SupplierEInvoiceService svc = service(suppliers, inbox);
    UUID id = inbox.doc.id();
    MatchSupplierEInvoiceRequest remember =
        new MatchSupplierEInvoiceRequest(SUPPLIER, atA.id(), null, List.of(), true);

    SupplierEInvoiceService.Receipt held = svc.match(heldToA(), id, remember);
    assertThat("the supplier was not changed", suppliers.updated, is(0));
    assertThat(held.notRemembered(), is(SupplierEInvoiceService.ADDRESS_NOT_REMEMBERED));
    assertThat("the match itself was kept", inbox.settled, is(1));

    SupplierEInvoiceService.Receipt whole = svc.match(wholeBusiness(), id, remember);
    assertThat("held to none, the address is kept", suppliers.updated, is(1));
    assertThat(whole.notRemembered(), is(nullValue()));

    // Not asked to remember: nothing said, nothing changed.
    SupplierEInvoiceService.Receipt plain =
        svc.match(
            heldToA(),
            id,
            new MatchSupplierEInvoiceRequest(SUPPLIER, atA.id(), null, List.of(), false));
    assertThat(plain.notRemembered(), is(nullValue()));
    assertThat(suppliers.updated, is(1));
  }

  // ── a document that bills no order yet ──────────────────────────────────────

  @Test
  @DisplayName(
      "A document that names no order yet is the business's inbox: a manager held to a store"
          + " reads and refuses it; another business finds none")
  void aDocumentWithNoOrderIsTheInboxs() {
    Inbox inbox = new Inbox(document(null, EInvoiceIntake.STATUS_NEEDS_ORDER), ubl("4500012345"));
    SupplierEInvoiceService svc = service(new Orders(), inbox);
    UUID id = inbox.doc.id();
    for (TenantContext them : theirs()) {
      refused(404, "PURCHASE_EINVOICE_NOT_FOUND", () -> svc.get(them, id));
      refused(404, "PURCHASE_EINVOICE_NOT_FOUND", () -> svc.refuse(them, id, REFUSE));
    }
    assertThat(svc.get(heldToA(), id).document().id(), is(id));
    assertThat(svc.original(heldToA(), id).bytes().length > 0, is(true));
    svc.refuse(heldToA(), id, REFUSE);
    assertThat(inbox.settled, is(1));
  }

  @Test
  @DisplayName(
      "Matching a document to store B's order, chosen or named by the document itself: a manager"
          + " held to store A is refused STORE_ACCESS_DENIED before the order's lines or returns are"
          + " read, a rule is learnt or the document claimed; held to none, it is decided")
  void matchingToAnotherStoresOrderIsRefusedFirst() {
    PurchaseOrder atB = order(STORE_B, Domain.PO_DRAFT);
    PurchaseOrder alsoAtB = order(STORE_B, Domain.PO_RECEIVED);
    Orders orders = new Orders(atB, alsoAtB);

    // Chosen by the person.
    Inbox chosen = new Inbox(document(null, EInvoiceIntake.STATUS_NEEDS_ORDER), ubl("4500012345"));
    SupplierEInvoiceService svc = service(orders, chosen);
    UUID id = chosen.doc.id();
    MatchSupplierEInvoiceRequest remember =
        new MatchSupplierEInvoiceRequest(null, alsoAtB.id(), null, List.of(), true);
    refused(403, "STORE_ACCESS_DENIED", () -> svc.match(heldToA(), id, choose(alsoAtB.id())));
    refused(403, "STORE_ACCESS_DENIED", () -> svc.match(heldToA(), id, remember));
    assertThat("nothing about the order was read", orders.pastTheOrder, is(0));
    assertThat("nothing was learnt", chosen.learnt, is(0));
    assertThat("nothing was claimed", chosen.claims, is(0));
    assertThat(chosen.settled, is(0));

    // Named by the document's own order reference.
    Inbox named =
        new Inbox(document(null, EInvoiceIntake.STATUS_NEEDS_ORDER), ubl(atB.id().toString()));
    SupplierEInvoiceService byReference = service(orders, named);
    refused(
        403,
        "STORE_ACCESS_DENIED",
        () -> byReference.match(heldToA(), named.doc.id(), choose(null)));
    assertThat(named.claims, is(0));

    // Held to none: the draft order cannot be billed yet, and the document waits for one.
    var decided = byReference.match(wholeBusiness(), named.doc.id(), choose(null));
    assertThat(decided.document().id(), is(named.doc.id()));
    assertThat("it was claimed and settled as waiting", named.settled, is(1));
  }

  @Test
  @DisplayName(
      "The list holds to the caller's stores: held to some, those stores' documents and the ones"
          + " with no order; held to none, every one")
  void theListHoldsToTheCallersStores() {
    Inbox inbox = new Inbox(document(null, EInvoiceIntake.STATUS_NEEDS_ORDER), ubl("x"));
    SupplierEInvoiceService svc = service(new Orders(), inbox);
    svc.list(heldToA(), null, 20);
    assertThat(inbox.storesAsked, is(Set.of(STORE_A)));
    svc.list(caller(TENANT, "MANAGER", Set.of(STORE_A, STORE_B)), "NEEDS_ORDER", 20);
    assertThat(inbox.storesAsked, is(Set.of(STORE_A, STORE_B)));
    svc.list(wholeBusiness(), null, 20);
    assertThat(inbox.storesAsked, is(nullValue()));
  }

  // ── a document a supplier sends ─────────────────────────────────────────────

  /** Ten apples at 2.50 from {@link #SUPPLIER_VAT}, naming {@code orderRef} as its order. */
  private static byte[] ubl(String orderRef) {
    BigDecimal qty = BigDecimal.TEN;
    BigDecimal price = new BigDecimal("2.50");
    BigDecimal net = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    BigDecimal vat = net.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
    BigDecimal gross = net.add(vat);
    Invoice.Address london =
        new Invoice.Address("1 High Street", null, null, "London", "E1 6AN", null, "GB");
    Invoice.Party seller =
        new Invoice.Party(
            "Supplier Ltd",
            null,
            List.of(),
            null,
            SUPPLIER_VAT,
            null,
            null,
            new Invoice.Identifier("5790000435975", "0088"),
            london,
            null);
    Invoice.Party buyer =
        new Invoice.Party(
            "Corner Shop Ltd",
            null,
            List.of(),
            null,
            "GB123456789",
            null,
            null,
            new Invoice.Identifier("GB123456789", "9932"),
            london,
            null);
    Invoice.Line line =
        new Invoice.Line(
            "1",
            null,
            null,
            qty,
            "C62",
            net,
            "1",
            null,
            null,
            List.of(),
            new Invoice.Price(price, null, null, null, null),
            "S",
            new BigDecimal("20"),
            new Invoice.Item("Apples", null, null, null, null, List.of(), null, List.of()));
    Invoice invoice =
        new Invoice(
            Invoice.PEPPOL_BIS_3,
            Invoice.PEPPOL_BILLING_PROFILE,
            "INV-1",
            LocalDate.of(2026, 9, 10),
            "380",
            "GBP",
            null,
            null,
            null,
            LocalDate.of(2026, 10, 10),
            "SHOP-1",
            null,
            null,
            orderRef,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            seller,
            buyer,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            new Invoice.Totals(net, null, null, net, vat, null, gross, null, null, gross),
            List.of(new Invoice.VatBreakdown(net, vat, "S", new BigDecimal("20"), null, null)),
            List.of(),
            List.of(line));
    return EInvoices.toUbl(invoice).getBytes(StandardCharsets.UTF_8);
  }
}
