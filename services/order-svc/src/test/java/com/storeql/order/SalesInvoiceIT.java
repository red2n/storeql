package com.storeql.order;

import static com.storeql.order.support.InvoicingStubs.V_GST;
import static com.storeql.order.support.InvoicingStubs.V_NAMELESS;
import static com.storeql.order.support.InvoicingStubs.V_NOHSN;
import static com.storeql.order.support.InvoicingStubs.V_ODD;
import static com.storeql.order.support.InvoicingStubs.V_RED;
import static com.storeql.order.support.InvoicingStubs.V_STD;
import static com.storeql.order.support.InvoicingStubs.V_ZERO;
import static com.storeql.order.support.InvoicingStubs.amount;
import static com.storeql.order.support.InvoicingStubs.basket;
import static com.storeql.order.support.InvoicingStubs.business;
import static com.storeql.order.support.InvoicingStubs.code;
import static com.storeql.order.support.InvoicingStubs.data;
import static com.storeql.order.support.InvoicingStubs.dataArray;
import static com.storeql.order.support.InvoicingStubs.envelope;
import static com.storeql.order.support.InvoicingStubs.only;
import static com.storeql.order.support.InvoicingStubs.readBack;
import static com.storeql.order.support.InvoicingStubs.services;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Invoice;
import com.storeql.ids.Ids;
import com.storeql.order.support.Till;
import com.storeql.test.Concurrency;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invoices and credit notes to business buyers (18.9).
 *
 * <p>The sale is placed at a till with the quote pricing-svc gives it, so every line carries the
 * rate it was taxed at; the buyer's registration, name and address, and the items' names and codes,
 * come from stubs standing where pricing-svc, customer-svc and product-svc would. The interesting
 * tests are what must not happen: a number burned by a refusal, two numbers for one sale, a VAT
 * figure that does not follow from what was paid, and another tenant's documents in view.
 */
@HelidonTest
class SalesInvoiceIT {

  // Declared before the static block, which registers them with the stubs.
  private static final String T_GB = Ids.newId().toString();
  private static final String T_IN = Ids.newId().toString();
  private static final String T_BARE = Ids.newId().toString();
  private static final String T_SEQ = Ids.newId().toString();
  private static final String T_OTHER = Ids.newId().toString();
  private static final String T_NONAME = Ids.newId().toString();
  private static final String S_NONAME = Ids.newId().toString();
  private static final String S_GB = Ids.newId().toString();
  private static final String S_IN = Ids.newId().toString();
  private static final String S_BARE = Ids.newId().toString();
  private static final String S_SEQ = Ids.newId().toString();
  private static final String S_OTHER = Ids.newId().toString();
  private static final String C_BIZ = Ids.newId().toString();
  private static final String C_PEPPOL = Ids.newId().toString();
  private static final String C_PRIVATE = Ids.newId().toString();
  private static final String C_UNREG = Ids.newId().toString();
  private static final String C_NOADDR = Ids.newId().toString();
  private static final String C_IN = Ids.newId().toString();
  private static final String C_DOWN = Ids.newId().toString();

  private static final String[] LEEDS = {"2 Mill Lane", "Leeds", "LS1 4AB"};

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub SERVICES;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T_GB, "GBP", "GB")
            .withIdentity(T_GB, "GB123456789", "0088", "5790000435975")
            .withLegalName(T_GB, "Harbour Provisions Ltd")
            .withStore(T_GB, S_GB, "GB", "1 High Street", "London", "E1 6AN")
            .with(T_IN, "INR", "IN")
            .withIdentity(T_IN, "27AAPFU0939F1ZV", null, null)
            .withLegalName(T_IN, "Kiran Traders Pvt Ltd")
            .withStore(T_IN, S_IN, "IN", "12 Marine Drive", "Mumbai", "400002")
            .with(T_BARE, "GBP", "GB")
            .withLegalName(T_BARE, "Bare Counter Ltd")
            .withStore(T_BARE, S_BARE, "GB", "3 Quiet Lane", "Bath", "BA1 1AA")
            .with(T_SEQ, "GBP", "GB")
            .withIdentity(T_SEQ, "GB987654321", null, null)
            .withLegalName(T_SEQ, "Sequence Stores Ltd")
            .withStore(T_SEQ, S_SEQ, "GB", "9 Number Row", "York", "YO1 7HH")
            .with(T_NONAME, "GBP", "GB")
            .withIdentity(T_NONAME, "GB246813579", null, null)
            .withStore(T_NONAME, S_NONAME, "GB", "5 Silent Row", "Ely", "CB7 4AA")
            .with(T_OTHER, "GBP", "GB")
            .withIdentity(T_OTHER, "GB111111111", null, null)
            .withLegalName(T_OTHER, "Someone Else Ltd")
            .withStore(T_OTHER, S_OTHER, "GB", "1 Other Street", "Hull", "HU1 1AA");
    SERVICES = services();
    business(SERVICES, C_BIZ, "Cafe Leeds Ltd", "GB555555555", "GB", null, null, LEEDS);
    business(
        SERVICES, C_PEPPOL, "Cafe Leeds Ltd", "GB555555555", "GB", "9932", "GB555555555", LEEDS);
    business(SERVICES, C_NOADDR, "Nowhere Ltd", "GB222222222", "GB", null, null, null);
    business(
        SERVICES,
        C_IN,
        "Bengaluru Stores Pvt Ltd",
        "29AAGCB7383J1Z4",
        "IN",
        null,
        null,
        new String[] {"4 Residency Road", "Bengaluru", "560025"});
    // Known to the shop, recorded as not registered; and one pricing-svc cannot answer for.
    business(SERVICES, C_UNREG, "Ada Lovelace", null, "GB", null, null, LEEDS);
    SERVICES.on(
        "GET",
        "/customer-vat-status/" + C_UNREG,
        200,
        "{\"data\":{\"customerId\":\""
            + C_UNREG
            + "\",\"vatRegistered\":false,\"reverseChargeEligible\":false}}");
    SERVICES.on(
        "GET",
        "/customer-vat-status/" + C_DOWN,
        503,
        "{\"error\":{\"code\":\"DOWN\",\"message\":\"pricing-svc is down\"}}");
    // C_PRIVATE has no record anywhere: every read is 404.
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    // The quote is what records each line's rate, so pricing is enforced here.
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    // The JVM is shared with the classes after this one: the stubs and the flag must not outlive
    // it.
    System.clearProperty("storeql.order.pricing.enforce");
    SERVICES.close();
    TENANTS.close();
    PG.stop();
  }

  private Till till() {
    return new Till(target);
  }

  private Response post(String path, String json, String tenant) {
    return till().post(path, json, tenant);
  }

  private Response postAs(String path, String json, String tenant, String role) {
    return till().postAs(path, json, tenant, role);
  }

  private Response get(String path, String tenant, String... params) {
    return till().get(path, tenant, params);
  }

  private Response getAs(String path, String tenant, String role, String... params) {
    return till().getAs(path, tenant, role, params);
  }

  private String place(String basket, String tenant) {
    return till().place(basket, tenant);
  }

  private String sell(String basket, String tenant) {
    return till().sell(basket, tenant);
  }

  private JsonArray documentsOf(String order, String tenant) {
    return till().documentsOf(order, tenant);
  }

  private JsonArray documentsOf(String order, String tenant, int n) {
    return till().documentsOf(order, tenant, n);
  }

  // ── the invoice ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A sale to a business is invoiced without anyone asking, once")
  void invoicedOnConfirm() {
    String order = sell(basket(S_GB, C_BIZ, "GBP", V_STD, "2", V_RED, "1", V_ZERO, "2"), T_GB);
    JsonObject inv = only(documentsOf(order, T_GB, 1), "INVOICE");

    // 20.00 at 20%, 4.00 at 5% and 5.00 at nothing: what the sale charged, line by line.
    assertThat(amount(inv, "netAmount"), is(new BigDecimal("29.00")));
    assertThat(amount(inv, "vatAmount"), is(new BigDecimal("4.20")));
    assertThat(amount(inv, "payableAmount"), is(new BigDecimal("33.20")));
    assertThat(inv.getString("fullNumber"), startsWith("INV/"));
    assertThat(inv.getString("typeCode"), is("380"));
    assertThat(inv.getString("buyerName"), is("Cafe Leeds Ltd"));
    assertThat(inv.getString("buyerVatId"), is("GB555555555"));
    assertThat(inv.getString("currency"), is("GBP"));
    assertThat(inv.getBoolean("peppol"), is(false));
    assertThat(inv.getJsonArray("formats").toString(), is("[\"UBL\",\"CII\",\"FACTURX\"]"));
    assertThat(inv.getJsonArray("irpProblems").size(), is(0));

    // Asking again is answered with the same document, not a second number.
    Response again = post("/admin/orders/" + order + "/invoice", "{}", T_GB);
    assertThat(again.getStatus(), is(200));
    assertThat(data(again).getString("id"), is(inv.getString("id")));
    assertThat(documentsOf(order, T_GB), hasSize(1));
    assertThat(get("/admin/sales-invoices/" + inv.getString("id"), T_GB).getStatus(), is(200));
  }

  @Test
  @DisplayName("The document downloads as issued, in every syntax, and reads back clean")
  void downloads() {
    String order = sell(basket(S_GB, C_BIZ, "GBP", V_STD, "2", V_RED, "1"), T_GB);
    JsonObject inv = only(documentsOf(order, T_GB, 1), "INVOICE");
    String id = inv.getString("id");
    String number = inv.getString("fullNumber");

    Response ubl = get("/admin/sales-invoices/" + id + "/document", T_GB);
    assertThat(ubl.getStatus(), is(200));
    assertThat(ubl.getHeaderString("Content-Type"), startsWith("application/xml"));
    assertThat(
        ubl.getHeaderString("Content-Disposition"),
        is("attachment; filename=\"" + number.replace('/', '-') + ".xml\""));
    assertThat(ubl.getHeaderString("X-Content-Type-Options"), is("nosniff"));
    assertThat(ubl.getHeaderString("Cache-Control"), is("no-store"));
    EInvoices.Received received = readBack(ubl);
    assertThat(received.syntax().name(), is("UBL"));
    Invoice model = received.invoice();
    assertThat(model.number(), is(number));
    assertThat(model.seller().name(), is("Harbour Provisions Ltd"));
    assertThat(model.seller().vatId(), is("GB123456789"));
    assertThat(model.buyer().vatId(), is("GB555555555"));
    assertThat(model.totals().payable(), is(new BigDecimal("28.20")));
    assertThat(model.lines().get(0).item().name(), is("Espresso beans 1kg"));
    assertThat(model.lines().get(0).unitCode(), is("KGM"));

    Response cii = get("/admin/sales-invoices/" + id + "/document", T_GB, "format", "cii");
    assertThat(cii.getStatus(), is(200));
    assertThat(readBack(cii).syntax().name(), is("CII"));

    Response pdf = get("/admin/sales-invoices/" + id + "/document", T_GB, "format", "FACTURX");
    assertThat(pdf.getStatus(), is(200));
    assertThat(pdf.getHeaderString("Content-Type"), startsWith("application/pdf"));
    byte[] bytes = pdf.readEntity(byte[].class);
    assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1), is("%PDF-"));

    // The portal's document belongs to an Indian business; an unknown format is a bad request.
    Response irp = get("/admin/sales-invoices/" + id + "/document", T_GB, "format", "IRP");
    assertThat(irp.getStatus(), is(404));
    assertThat(code(irp), is("ORDER_INVOICE_IRP_NOT_APPLICABLE"));
    Response docx = get("/admin/sales-invoices/" + id + "/document", T_GB, "format", "DOCX");
    assertThat(docx.getStatus(), is(400));
    assertThat(code(docx), is("ORDER_INVOICE_FORMAT_UNKNOWN"));
  }

  @Test
  @DisplayName("Both parties on Peppol make a BIS Billing document")
  void peppol() {
    String order = sell(basket(S_GB, C_PEPPOL, "GBP", V_STD, "1"), T_GB);
    JsonObject inv = only(documentsOf(order, T_GB, 1), "INVOICE");
    assertThat(inv.getBoolean("peppol"), is(true));
    assertThat(inv.getString("customizationId"), containsString("peppol"));
    Response ubl = get("/admin/sales-invoices/" + inv.getString("id") + "/document", T_GB);
    Invoice model = readBack(ubl).invoice();
    assertThat(model.buyer().electronicAddress().id(), is("GB555555555"));
    assertThat(model.seller().electronicAddress().scheme(), is("0088"));
  }

  @Test
  @DisplayName("A till discount is stated net, so the VAT follows what was actually paid")
  void tillDiscount() {
    // 33.20 with VAT; the owner takes 5.00 off the total, after the tax.
    String withDiscount =
        basket(S_GB, C_BIZ, "GBP", V_STD, "2", V_RED, "1", V_ZERO, "2")
            .replaceFirst(
                "\\{", "{\"discountAmount\":5.00,\"discountReason\":\"Regular trade customer\",");
    String order = sell(withDiscount, T_GB);
    JsonObject inv = only(documentsOf(order, T_GB, 1), "INVOICE");
    assertThat(amount(inv, "payableAmount"), is(new BigDecimal("28.20")));
    assertThat(amount(inv, "netAmount"), is(new BigDecimal("24.63")));
    assertThat(amount(inv, "vatAmount"), is(new BigDecimal("3.57")));
    Invoice model =
        readBack(get("/admin/sales-invoices/" + inv.getString("id") + "/document", T_GB)).invoice();
    assertThat(model.totals().rounding() == null, is(true));
    // The discount is an allowance on every rate it came off, largest share on the 20% lines.
    assertThat(model.allowanceCharges(), hasSize(3));
    assertThat(model.totals().allowances(), is(new BigDecimal("4.37")));
  }

  // ── the sequence ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("A refusal takes no number, eight requests at once take one, and pages follow")
  void numbering() throws Exception {
    String first = sell(basket(S_SEQ, C_BIZ, "GBP", V_STD, "1"), T_SEQ);
    JsonObject one = only(documentsOf(first, T_SEQ, 1), "INVOICE");
    assertThat(one.getJsonNumber("number").longValue(), is(1L));
    assertThat(one.getString("fullNumber"), is("INV/" + one.getString("period") + "/000001"));

    // A customer who is not a registered business: refused, in the background and by hand.
    String unregistered = sell(basket(S_SEQ, C_UNREG, "GBP", V_STD, "1"), T_SEQ);
    Response refused = post("/admin/orders/" + unregistered + "/invoice", "{}", T_SEQ);
    assertThat(refused.getStatus(), is(409));
    assertThat(code(refused), is("ORDER_INVOICE_BUYER_NOT_REGISTERED"));

    // A sale whose tax does not follow from its lines' rates: the number it took is given back.
    String odd = sell(basket(S_SEQ, C_BIZ, "GBP", V_ODD, "1"), T_SEQ);
    Response differ = post("/admin/orders/" + odd + "/invoice", "{}", T_SEQ);
    assertThat(differ.getStatus(), is(409));
    assertThat(code(differ), is("ORDER_INVOICE_TOTALS_DIFFER"));
    assertThat(documentsOf(odd, T_SEQ), hasSize(0));

    String second = sell(basket(S_SEQ, C_BIZ, "GBP", V_RED, "1"), T_SEQ);
    assertThat(
        only(documentsOf(second, T_SEQ, 1), "INVOICE").getJsonNumber("number").longValue(), is(2L));

    // Eight requests at once for one sale: one number, one document.
    String third = sell(basket(S_SEQ, C_BIZ, "GBP", V_ZERO, "1"), T_SEQ);
    List<Response> atOnce =
        Concurrency.inParallel(8, () -> post("/admin/orders/" + third + "/invoice", "{}", T_SEQ));
    Set<String> ids = new HashSet<>();
    for (Response r : atOnce) {
      assertThat(r.getStatus(), is(200));
      ids.add(data(r).getString("id"));
    }
    assertThat(ids, hasSize(1));
    assertThat(documentsOf(third, T_SEQ), hasSize(1));

    // The register: three documents, numbered 1, 2, 3, and nothing else.
    Response all = get("/admin/sales-invoices", T_SEQ);
    JsonArray docs = dataArray(all);
    assertThat(docs, hasSize(3));
    assertThat(
        docs.stream()
            .map(d -> d.asJsonObject().getJsonNumber("number").longValue())
            .sorted()
            .toList(),
        is(List.of(1L, 2L, 3L)));

    // Newest first, a page at a time.
    Response page1 = get("/admin/sales-invoices", T_SEQ, "limit", "2");
    JsonObject env1 = envelope(page1);
    assertThat(env1.getJsonArray("data"), hasSize(2));
    assertThat(
        env1.getJsonArray("data").getJsonObject(0).getJsonNumber("number").longValue(), is(3L));
    String cursor = env1.getJsonObject("meta").getString("nextCursor");
    Response page2 = get("/admin/sales-invoices", T_SEQ, "limit", "2", "after", cursor);
    JsonObject env2 = envelope(page2);
    assertThat(env2.getJsonArray("data"), hasSize(1));
    assertThat(
        env2.getJsonArray("data").getJsonObject(0).getJsonNumber("number").longValue(), is(1L));
    // JSON-B leaves a null field out: no cursor means the last page.
    JsonObject meta2 = env2.getJsonObject("meta");
    assertThat(!meta2.containsKey("nextCursor") || meta2.isNull("nextCursor"), is(true));
    Response bad = get("/admin/sales-invoices", T_SEQ, "after", "not-a-cursor");
    assertThat(bad.getStatus(), is(400));
  }

  // ── the credit note ────────────────────────────────────────────────────────

  @Test
  @DisplayName("A return against an invoiced sale is credited, naming the invoice")
  void creditNote() {
    String order = sell(basket(S_GB, C_BIZ, "GBP", V_STD, "2", V_RED, "1"), T_GB);
    JsonObject inv = only(documentsOf(order, T_GB, 1), "INVOICE");

    Response returned =
        post(
            "/orders/" + order + "/returns",
            "{\"reason\":\"one bag split\",\"refundMethod\":\"ORIGINAL\","
                + "\"items\":[{\"variantId\":\""
                + V_STD
                + "\",\"qty\":1,\"condition\":\"SEALED\"}]}",
            T_GB);
    JsonObject ret = data(returned);
    assertThat(ret.toString(), returned.getStatus(), is(201));
    String returnId = ret.getString("id");

    JsonObject credit = only(documentsOf(order, T_GB, 2), "CREDIT_NOTE");
    assertThat(credit.getString("typeCode"), is("381"));
    assertThat(credit.getString("fullNumber"), startsWith("CRN/"));
    assertThat(credit.getString("returnId"), is(returnId));
    assertThat(credit.getString("precedingInvoiceId"), is(inv.getString("id")));
    // One bag at 10.00 and 20%: the goods at the rate they were sold at.
    assertThat(amount(credit, "netAmount"), is(new BigDecimal("10.00")));
    assertThat(amount(credit, "vatAmount"), is(new BigDecimal("2.00")));
    assertThat(amount(credit, "payableAmount"), is(new BigDecimal("12.00")));

    Invoice model =
        readBack(get("/admin/sales-invoices/" + credit.getString("id") + "/document", T_GB))
            .invoice();
    assertThat(model.typeCode(), is("381"));
    assertThat(model.precedingInvoices().get(0).number(), is(inv.getString("fullNumber")));

    // By hand: the same credit note; the same return cannot be credited twice.
    Response again = post("/admin/returns/" + returnId + "/credit-note", "{}", T_GB);
    assertThat(again.getStatus(), is(200));
    assertThat(data(again).getString("id"), is(credit.getString("id")));
    assertThat(documentsOf(order, T_GB), hasSize(2));
  }

  @Test
  @DisplayName("A return on a sale that was never invoiced has nothing to credit")
  void creditNoteNeedsAnInvoice() {
    String order = sell(basket(S_GB, C_UNREG, "GBP", V_STD, "1"), T_GB);
    Response returned =
        post(
            "/orders/" + order + "/returns",
            "{\"reason\":\"changed mind\",\"items\":[{\"variantId\":\""
                + V_STD
                + "\",\"qty\":1,\"condition\":\"SEALED\"}]}",
            T_GB);
    String returnId = data(returned).getString("id");
    Response credit = post("/admin/returns/" + returnId + "/credit-note", "{}", T_GB);
    assertThat(credit.getStatus(), is(409));
    assertThat(code(credit), is("ORDER_CREDIT_NOTE_NO_INVOICE"));
    assertThat(documentsOf(order, T_GB), hasSize(0));
    Response unknown = post("/admin/returns/" + Ids.newId() + "/credit-note", "{}", T_GB);
    assertThat(unknown.getStatus(), is(404));
    assertThat(code(unknown), is("ORDER_RETURN_NOT_FOUND"));
  }

  // ── India ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An Indian business gets the portal's document, or what it would refuse")
  void india() {
    String order = sell(basket(S_IN, C_IN, "INR", V_GST, "1"), T_IN);
    JsonObject inv = only(documentsOf(order, T_IN, 1), "INVOICE");
    assertThat(inv.getString("buyerVatId"), is("29AAGCB7383J1Z4"));
    assertThat(amount(inv, "payableAmount"), is(new BigDecimal("118.00")));
    assertThat(inv.getJsonArray("formats").toString(), containsString("\"IRP\""));
    assertThat(inv.getJsonArray("irpProblems").size(), is(0));

    Response irp =
        get("/admin/sales-invoices/" + inv.getString("id") + "/document", T_IN, "format", "IRP");
    assertThat(irp.getStatus(), is(200));
    assertThat(irp.getHeaderString("Content-Type"), startsWith("application/json"));
    String json = irp.readEntity(String.class);
    assertThat(json, containsString("\"Version\":\"1.1\""));
    assertThat(json, containsString("\"Gstin\":\"27AAPFU0939F1ZV\""));
    // Mumbai to Bengaluru: an inter-state supply, so IGST.
    assertThat(json, containsString("\"IgstAmt\":18"));
    assertThat(json, containsString("\"HsnCd\":\"1006\""));

    // An item with no HSN code: the document is issued, and what the portal would say is kept.
    String noHsn = sell(basket(S_IN, C_IN, "INR", V_NOHSN, "1"), T_IN);
    JsonObject objected = only(documentsOf(noHsn, T_IN, 1), "INVOICE");
    assertThat(objected.getJsonArray("formats").toString(), is("[\"UBL\",\"CII\",\"FACTURX\"]"));
    assertThat(objected.getJsonArray("irpProblems").toString(), containsString("HSN"));
    Response refused =
        get(
            "/admin/sales-invoices/" + objected.getString("id") + "/document",
            T_IN,
            "format",
            "IRP");
    assertThat(refused.getStatus(), is(409));
    assertThat(code(refused), is("ORDER_INVOICE_IRP_NOT_READY"));
  }

  // ── refusals ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("What cannot be invoiced says why, and takes no number")
  void refusals() {
    String pending = place(basket(S_GB, C_BIZ, "GBP", V_STD, "1"), T_GB);
    Response notSold = post("/admin/orders/" + pending + "/invoice", "{}", T_GB);
    assertThat(notSold.getStatus(), is(409));
    assertThat(code(notSold), is("ORDER_INVOICE_NOT_SOLD"));

    String anonymous = sell(basket(S_GB, null, "GBP", V_STD, "1"), T_GB);
    Response noBuyer = post("/admin/orders/" + anonymous + "/invoice", "{}", T_GB);
    assertThat(noBuyer.getStatus(), is(409));
    assertThat(code(noBuyer), is("ORDER_INVOICE_NO_BUYER"));

    String privateSale = sell(basket(S_GB, C_PRIVATE, "GBP", V_STD, "1"), T_GB);
    Response unregistered = post("/admin/orders/" + privateSale + "/invoice", "{}", T_GB);
    assertThat(unregistered.getStatus(), is(409));
    assertThat(code(unregistered), is("ORDER_INVOICE_BUYER_NOT_REGISTERED"));

    String nowhere = sell(basket(S_GB, C_NOADDR, "GBP", V_STD, "1"), T_GB);
    Response noAddress = post("/admin/orders/" + nowhere + "/invoice", "{}", T_GB);
    assertThat(noAddress.getStatus(), is(409));
    assertThat(code(noAddress), is("ORDER_INVOICE_BUYER_ADDRESS_MISSING"));

    String bare = sell(basket(S_BARE, C_BIZ, "GBP", V_STD, "1"), T_BARE);
    Response noVat = post("/admin/orders/" + bare + "/invoice", "{}", T_BARE);
    assertThat(noVat.getStatus(), is(409));
    assertThat(code(noVat), is("ORDER_INVOICE_SELLER_VAT_MISSING"));

    String down = sell(basket(S_GB, C_DOWN, "GBP", V_STD, "1"), T_GB);
    Response unavailable = post("/admin/orders/" + down + "/invoice", "{}", T_GB);
    assertThat(unavailable.getStatus(), is(503));
    assertThat(code(unavailable), is("ORDER_INVOICE_DEPENDENCY_UNAVAILABLE"));

    assertThat(post("/admin/orders/" + Ids.newId() + "/invoice", "{}", T_GB).getStatus(), is(404));
    Response noInvoice = get("/admin/sales-invoices/" + Ids.newId(), T_GB);
    assertThat(noInvoice.getStatus(), is(404));
    assertThat(code(noInvoice), is("ORDER_INVOICE_NOT_FOUND"));
    Response noDocument = get("/admin/sales-invoices/" + Ids.newId() + "/document", T_GB);
    assertThat(noDocument.getStatus(), is(404));
    assertThat(code(noDocument), is("ORDER_INVOICE_NOT_FOUND"));
    for (String order : List.of(pending, anonymous, privateSale, nowhere, down)) {
      assertThat(documentsOf(order, T_GB), hasSize(0));
    }
    assertThat(documentsOf(bare, T_BARE), hasSize(0));
  }

  @Test
  @DisplayName("A line with no item name would break the standard, so it is not invoiced")
  void aLineWithNoItemNameIsNotInvoiced() {
    String order = sell(basket(S_GB, C_BIZ, "GBP", V_NAMELESS, "1"), T_GB);
    Response r = post("/admin/orders/" + order + "/invoice", "{}", T_GB);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(409));
    assertThat(text, containsString("ORDER_INVOICE_NOT_COMPLIANT"));
    assertThat(text, containsString("EN 16931"));
    assertThat(documentsOf(order, T_GB), hasSize(0));
  }

  @Test
  @DisplayName("An invoice needs the seller's legal name as well as its VAT number")
  void anInvoiceNeedsTheSellersLegalName() {
    String order = sell(basket(S_NONAME, C_BIZ, "GBP", V_STD, "1"), T_NONAME);
    Response r = post("/admin/orders/" + order + "/invoice", "{}", T_NONAME);
    assertThat(r.getStatus(), is(409));
    assertThat(code(r), is("ORDER_INVOICE_SELLER_NAME_MISSING"));
    assertThat(documentsOf(order, T_NONAME), hasSize(0));
  }

  @Test
  @DisplayName("Only management issues or reads invoices")
  void managementOnly() {
    String order = sell(basket(S_GB, C_BIZ, "GBP", V_STD, "1"), T_GB);
    JsonObject inv = only(documentsOf(order, T_GB, 1), "INVOICE");
    for (String role : List.of("CASHIER", "STOREKEEPER", "CUSTOMER")) {
      assertThat(
          role,
          postAs("/admin/orders/" + order + "/invoice", "{}", T_GB, role).getStatus(),
          is(403));
      assertThat(role, getAs("/admin/sales-invoices", T_GB, role).getStatus(), is(403));
      assertThat(
          role,
          getAs("/admin/sales-invoices/" + inv.getString("id") + "/document", T_GB, role)
              .getStatus(),
          is(403));
    }
    assertThat(getAs("/admin/sales-invoices", T_GB, "MANAGER").getStatus(), is(200));
  }

  @Test
  @DisplayName("Another tenant sees none of it")
  void anotherTenantSeesNothing() {
    String order = sell(basket(S_GB, C_BIZ, "GBP", V_STD, "1"), T_GB);
    JsonObject inv = only(documentsOf(order, T_GB, 1), "INVOICE");
    String id = inv.getString("id");
    Response theirs = get("/admin/sales-invoices/" + id, T_OTHER);
    assertThat(theirs.getStatus(), is(404));
    assertThat(code(theirs), is("ORDER_INVOICE_NOT_FOUND"));
    Response theirsDocument = get("/admin/sales-invoices/" + id + "/document", T_OTHER);
    assertThat(theirsDocument.getStatus(), is(404));
    assertThat(code(theirsDocument), is("ORDER_INVOICE_NOT_FOUND"));
    assertThat(post("/admin/orders/" + order + "/invoice", "{}", T_OTHER).getStatus(), is(404));
    assertThat(get("/admin/orders/" + order + "/invoices", T_OTHER).getStatus(), is(200));
    assertThat(documentsOf(order, T_OTHER), hasSize(0));
    List<String> others =
        dataArray(get("/admin/sales-invoices", T_OTHER)).stream()
            .map(d -> d.asJsonObject().getString("id"))
            .collect(Collectors.toCollection(ArrayList::new));
    assertThat(others.contains(id), is(false));
  }
}
