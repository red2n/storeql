package com.storeql.purchase.service;

import static com.storeql.purchase.service.StoreHeldCallersTest.caller;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.GoodsReceipt;
import com.storeql.purchase.domain.Domain.GoodsReceiptLine;
import com.storeql.purchase.domain.Domain.IntercompanyInvoice;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.PurchaseOrder;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.dto.Dtos.AddPurchaseOrderLineRequest;
import com.storeql.purchase.dto.Dtos.AmendPurchaseOrderLineRequest;
import com.storeql.purchase.dto.Dtos.ApplyLandedCostRequest;
import com.storeql.purchase.dto.Dtos.RaiseIntercompanyInvoiceRequest;
import com.storeql.purchase.repo.LandedCostRepository;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.TenantContext;
import com.storeql.web.Validations;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Money a person keys in purchasing is held to its own currency's minor units — whole yen, cents of
 * a pound, thousandths of a dinar — never to two places: an amount finer than its currency is
 * refused {@code 400 PURCHASE_AMOUNT_TOO_PRECISE} before anything is written, a dinar amount below
 * a cent is accepted, and a unit price may carry more precision than any currency. No database.
 */
class MinorUnitsTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID STORE_A = Ids.newId();
  private static final UUID STORE_B = Ids.newId();

  private static TenantContext owner() {
    return caller(TENANT, "OWNER", Set.of());
  }

  private static void tooPrecise(Executable call) {
    ApiException e = assertThrows(ApiException.class, call);
    assertThat(e.getMessage(), e.status(), is(400));
    assertThat(e.getMessage(), e.code(), is("PURCHASE_AMOUNT_TOO_PRECISE"));
  }

  private static PurchaseOrder order(String currency) {
    Instant now = Instant.now();
    return new PurchaseOrder(
        Ids.newId(),
        TENANT,
        Ids.newId(),
        STORE_A,
        Domain.PO_RECEIVED,
        currency,
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

  /** One order, its receipt with no lines, and everything written counted. */
  private static final class Books extends PurchaseRepository {
    final PurchaseOrder po;
    Domain.VendorReturn ret;
    int linesRead;
    int matched;
    int written;

    Books(PurchaseOrder po) {
      this.po = po;
    }

    @Override
    public Optional<PurchaseOrder> findPurchaseOrder(UUID tenantId, UUID id) {
      return po != null && po.id().equals(id) ? Optional.of(po) : Optional.empty();
    }

    @Override
    public List<PurchaseOrderLine> findPurchaseOrderLines(UUID tenantId, UUID poId) {
      linesRead++;
      return List.of();
    }

    @Override
    public List<GoodsReceiptLine> findGoodsReceiptLines(UUID tenantId, UUID grId) {
      linesRead++;
      return List.of();
    }

    /** Reached only once every keyed amount has been accepted. */
    @Override
    public List<com.storeql.purchase.domain.ThreeWayMatch.OrderPosition> findMatchPositions(
        UUID tenantId, UUID poId) {
      matched++;
      throw new PastTheAmounts();
    }

    @Override
    public Optional<Domain.VendorReturn> findVendorReturn(UUID tenantId, UUID id) {
      return ret != null && ret.id().equals(id) ? Optional.of(ret) : Optional.empty();
    }

    @Override
    public List<IntercompanyInvoice> createIntercompanyInvoicePair(
        IntercompanyInvoice ar,
        List<NominalLedgerEntry> arEntries,
        OutboxRow arEvent,
        IntercompanyInvoice ap,
        List<NominalLedgerEntry> apEntries,
        OutboxRow apEvent) {
      written++;
      return List.of(ar, ap);
    }
  }

  private static final class Receipts extends LandedCostRepository {
    final GoodsReceipt gr;

    Receipts(GoodsReceipt gr) {
      this.gr = gr;
    }

    @Override
    public Optional<GoodsReceipt> findGoodsReceipt(UUID tenantId, UUID grId) {
      return gr.id().equals(grId) ? Optional.of(gr) : Optional.empty();
    }
  }

  /** Thrown where a call goes on once its amounts have been accepted. */
  private static final class PastTheAmounts extends RuntimeException {
    PastTheAmounts() {
      super("past the amounts", null, false, false);
    }
  }

  private static LandedCostService landed(Books books, GoodsReceipt gr) {
    LandedCostService s = new LandedCostService();
    s.repo = new Receipts(gr);
    s.purchases = books;
    return s;
  }

  @Test
  @DisplayName(
      "A landed charge is held to the order's currency: half a yen and a thousandth of a pound are"
          + " refused before the receipt's lines are read; five thousandths of a dinar is a charge")
  void aLandedChargeIsInTheOrdersMinorUnits() {
    for (String[] c : new String[][] {{"JPY", "0.5"}, {"JPY", "1500.5"}, {"GBP", "5.005"}}) {
      Books books = new Books(order(c[0]));
      GoodsReceipt gr =
          new GoodsReceipt(
              Ids.newId(), TENANT, books.po.id(), STORE_A, Instant.now(), Instant.now(), null);
      ApplyLandedCostRequest req =
          new ApplyLandedCostRequest(
              gr.id(), "FREIGHT", "BY_VALUE", new BigDecimal(c[1]), null, null, null, null);
      tooPrecise(() -> landed(books, gr).apply(req, owner(), Ids.newId().toString()));
      assertThat(c[0] + " " + c[1], books.linesRead, is(0));
    }

    Books dinar = new Books(order("KWD"));
    GoodsReceipt gr =
        new GoodsReceipt(
            Ids.newId(), TENANT, dinar.po.id(), STORE_A, Instant.now(), Instant.now(), null);
    ApplyLandedCostRequest small =
        new ApplyLandedCostRequest(
            gr.id(), "FREIGHT", "BY_VALUE", new BigDecimal("0.005"), null, null, null, null);
    ApiException past =
        assertThrows(ApiException.class, () -> landed(dinar, gr).apply(small, owner(), null));
    // Past the amount: a receipt with no lines has nothing to spread it over.
    assertThat(past.code(), is("PURCHASE_LANDED_NOTHING_RECEIVED"));
  }

  private static RaiseIntercompanyInvoiceRequest intercompany(
      String net, String vat, String gross, String currency) {
    return new RaiseIntercompanyInvoiceRequest(
        STORE_A.toString(),
        STORE_B.toString(),
        null,
        new BigDecimal(net),
        new BigDecimal(vat),
        new BigDecimal(gross),
        "T1",
        false,
        currency);
  }

  @Test
  @DisplayName(
      "An intercompany pair is held to its currency: a yen amount with decimals and a pound amount"
          + " in thousandths are refused before anything is posted; a dinar's three are not")
  void anIntercompanyPairIsInItsCurrencysMinorUnits() {
    Books books = new Books(null);
    PurchaseService svc = new PurchaseService();
    svc.repo = books;
    tooPrecise(
        () -> svc.raiseIntercompanyInvoices(intercompany("1000.5", "0", "1000.5", "JPY"), owner()));
    tooPrecise(
        () ->
            svc.raiseIntercompanyInvoices(intercompany("1000", "100.5", "1100.5", "JPY"), owner()));
    tooPrecise(
        () -> svc.raiseIntercompanyInvoices(intercompany("10.001", "2", "12.001", "GBP"), owner()));
    assertThat("nothing was posted", books.written, is(0));

    svc.raiseIntercompanyInvoices(intercompany("1.234", "0.123", "1.357", "KWD"), owner());
    svc.raiseIntercompanyInvoices(intercompany("1000", "100", "1100", "JPY"), owner());
    assertThat(books.written, is(2));
  }

  @Test
  @DisplayName(
      "No request in purchasing assumes a cent: a unit price of half a penny, a dinar charge of"
          + " five thousandths and an intercompany net below a cent pass the request's own rules;"
          + " nothing, or less, still does not")
  void noRequestAssumesACent() {
    UUID variant = Ids.newId();
    Validations.validate(
        new AddPurchaseOrderLineRequest(
            variant, new BigDecimal("1000"), new BigDecimal("0.005"), null));
    Validations.validate(
        new AmendPurchaseOrderLineRequest(new BigDecimal("1000"), new BigDecimal("0.0025"), null));
    Validations.validate(
        new ApplyLandedCostRequest(
            Ids.newId(), "FREIGHT", "BY_VALUE", new BigDecimal("0.005"), "KWD", null, null, null));
    Validations.validate(intercompany("0.005", "0", "0.005", "KWD"));

    for (String zero : new String[] {"0", "-1"}) {
      BigDecimal z = new BigDecimal(zero);
      for (Executable refused :
          List.<Executable>of(
              () ->
                  Validations.validate(
                      new AddPurchaseOrderLineRequest(variant, BigDecimal.ONE, z, null)),
              () ->
                  Validations.validate(
                      new ApplyLandedCostRequest(
                          Ids.newId(), "FREIGHT", "BY_VALUE", z, null, null, null, null)),
              () -> Validations.validate(intercompany(zero, "0", "1", "GBP")),
              () -> Validations.validate(intercompany("1", "0", zero, "GBP")))) {
        ApiException e = assertThrows(ApiException.class, refused);
        assertThat(e.status(), is(400));
      }
    }
  }

  private static com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceRequest invoice(
      UUID poId, String vat, String statedGross) {
    return new com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceRequest(
        poId,
        "INV-1",
        "2026-09-30",
        null,
        vat == null ? null : new BigDecimal(vat),
        statedGross == null ? null : new BigDecimal(statedGross),
        List.of(
            new com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceLine(
                Ids.newId(), BigDecimal.TEN, new BigDecimal("0.0125"), null)));
  }

  @Test
  @DisplayName(
      "A keyed supplier invoice is held to its order's currency: VAT of half a yen or a"
          + " thousandth of a pound, or a stated total finer than the currency, is refused"
          + " PURCHASE_AMOUNT_TOO_PRECISE before anything is matched — never rounded; a dinar's"
          + " three decimals and a unit price finer than any currency go on")
  void aKeyedInvoiceIsInItsOrdersMinorUnits() {
    for (String[] c :
        new String[][] {
          {"JPY", "0.5", null},
          {"JPY", "100", "1100.5"},
          {"GBP", "2.005", null},
          {"GBP", "2", "12.125"},
          {"KWD", "1.2345", null}
        }) {
      Books books = new Books(order(c[0]));
      PurchaseService svc = new PurchaseService();
      svc.repo = books;
      tooPrecise(() -> svc.captureSupplierInvoice(owner(), invoice(books.po.id(), c[1], c[2])));
      assertThat(String.join(" ", c[0], c[1], String.valueOf(c[2])), books.matched, is(0));
    }
    for (String[] c :
        new String[][] {{"JPY", "100", "1100"}, {"GBP", "2.50", "14.00"}, {"KWD", "0.005", null}}) {
      Books books = new Books(order(c[0]));
      PurchaseService svc = new PurchaseService();
      svc.repo = books;
      assertThrows(
          PastTheAmounts.class,
          () -> svc.captureSupplierInvoice(owner(), invoice(books.po.id(), c[1], c[2])));
      assertThat(books.matched, is(1));
    }
  }

  private static Domain.VendorReturn vendorReturn(String currency, String gross) {
    return new Domain.VendorReturn(
        Ids.newId(),
        TENANT,
        Ids.newId(),
        Ids.newId(),
        STORE_A,
        "RAISED",
        "DAMAGED",
        null,
        currency,
        new BigDecimal(gross),
        BigDecimal.ZERO,
        new BigDecimal(gross),
        "DN-000001",
        Instant.now(),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  @DisplayName(
      "A credit note's amount is held to the return's currency: half a yen and a thousandth of a"
          + " pound are refused PURCHASE_AMOUNT_TOO_PRECISE before the period is asked or anything"
          + " posted; whole yen and a dinar's three decimals go on")
  void aCreditNoteIsInTheReturnsMinorUnits() {
    for (String[] c : new String[][] {{"JPY", "1000", "999.5"}, {"GBP", "10.00", "9.995"}}) {
      Books books = new Books(null);
      books.ret = vendorReturn(c[0], c[1]);
      PurchaseService svc = new PurchaseService();
      svc.repo = books;
      tooPrecise(
          () ->
              svc.recordCreditNote(
                  owner(),
                  books.ret.id(),
                  new com.storeql.purchase.dto.Dtos.RecordCreditNoteRequest(
                      "CN-1", "2026-09-30", new BigDecimal(c[2]))));
      assertThat(books.written, is(0));
    }
    for (String[] c : new String[][] {{"JPY", "1000", "999"}, {"KWD", "10.000", "9.995"}}) {
      Books books = new Books(null);
      books.ret = vendorReturn(c[0], c[1]);
      PurchaseService svc = new PurchaseService();
      svc.repo = books;
      // Past the amount: no accounting-period reader stands in here.
      Throwable past =
          assertThrows(
              Throwable.class,
              () ->
                  svc.recordCreditNote(
                      owner(),
                      books.ret.id(),
                      new com.storeql.purchase.dto.Dtos.RecordCreditNoteRequest(
                          "CN-1", "2026-09-30", new BigDecimal(c[2]))));
      assertThat(
          c[0] + " " + c[2],
          past instanceof ApiException e && "PURCHASE_AMOUNT_TOO_PRECISE".equals(e.code()),
          is(false));
    }
  }

  @Test
  @DisplayName(
      "An intercompany pair adds up before it is posted: a gross that is not net plus VAT is"
          + " refused PURCHASE_IC_GROSS_MISMATCH, VAT on a VAT-group supply"
          + " PURCHASE_IC_VAT_DISREGARDED, and nothing is posted; net plus VAT in any currency's own"
          + " units is raised")
  void anIntercompanyPairAddsUp() {
    Books books = new Books(null);
    PurchaseService svc = new PurchaseService();
    svc.repo = books;
    for (String[] c :
        new String[][] {
          {"1000.00", "200.00", "1000.00", "GBP"},
          {"1000.00", "200.00", "1200.01", "GBP"},
          {"1000.00", "0.00", "1200.00", "GBP"},
          {"1000", "100", "1101", "JPY"},
          {"1.234", "0.123", "1.358", "KWD"}
        }) {
      ApiException e =
          assertThrows(
              ApiException.class,
              () -> svc.raiseIntercompanyInvoices(intercompany(c[0], c[1], c[2], c[3]), owner()));
      assertThat(String.join(" ", c), e.status(), is(400));
      assertThat(String.join(" ", c), e.code(), is("PURCHASE_IC_GROSS_MISMATCH"));
    }
    RaiseIntercompanyInvoiceRequest grouped =
        new RaiseIntercompanyInvoiceRequest(
            STORE_A.toString(),
            STORE_B.toString(),
            null,
            new BigDecimal("500.00"),
            new BigDecimal("100.00"),
            new BigDecimal("600.00"),
            "T1",
            true,
            "GBP");
    ApiException vat =
        assertThrows(ApiException.class, () -> svc.raiseIntercompanyInvoices(grouped, owner()));
    assertThat(vat.code(), is("PURCHASE_IC_VAT_DISREGARDED"));
    assertThat("nothing was posted", books.written, is(0));

    svc.raiseIntercompanyInvoices(intercompany("1000.00", "200.00", "1200", "GBP"), owner());
    svc.raiseIntercompanyInvoices(
        new RaiseIntercompanyInvoiceRequest(
            STORE_A.toString(),
            STORE_B.toString(),
            null,
            new BigDecimal("500.00"),
            new BigDecimal("0"),
            new BigDecimal("500"),
            "T1",
            true,
            "GBP"),
        owner());
    assertThat(books.written, is(2));
  }
}
