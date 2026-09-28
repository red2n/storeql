package com.storeql.purchase;

import static com.storeql.purchase.PaymentRunSteps.assertCode;
import static com.storeql.purchase.PaymentRunSteps.data;
import static com.storeql.purchase.PurchaseFixtures.STORE_A;
import static com.storeql.purchase.PurchaseFixtures.T;
import static com.storeql.purchase.PurchaseFixtures.T2;
import static com.storeql.purchase.PurchaseFixtures.USER;
import static com.storeql.purchase.PurchaseFixtures.VARIANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Invoice;
import com.storeql.ids.Ids;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Supplier e-invoices received (07.13): a UBL invoice or a Factur-X PDF read, checked, matched and
 * captured through the three-way match; what cannot be matched waits for a person, who teaches it;
 * what breaks the rules, or is addressed elsewhere, is kept and not captured; and what an attacker
 * or another business sends is refused.
 */
@HelidonTest
class SupplierEInvoiceIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("purchase");

  /** This business, as its e-invoices name it. */
  private static final String OUR_VAT = "GB123456789";

  /** The key every network presents when it delivers (the transport seam). */
  private static final String DELIVERY_KEY = "it-delivery-key";

  /** A third business, holding the same address as the second: a delivery to it lands nowhere. */
  private static final String T3 = Ids.newId().toString();

  /** A business whose plan keeps no e-invoice documents at all (21.11). */
  private static final String T_CAPPED = Ids.newId().toString();

  private static final String SHARED_GLN = "5790000435975";

  static {
    System.setProperty("storeql.purchase.approval.limits", "");
    System.setProperty("storeql.einvoice.inbound.key", DELIVERY_KEY);
    TenantSvcStub.start()
        .with(T, "GBP", "GB")
        .withIdentity(T, OUR_VAT, "9932", OUR_VAT)
        .with(T2, "GBP", "GB")
        .withIdentity(T2, "GB222222222", "0088", SHARED_GLN)
        .with(T3, "GBP", "GB")
        .withIdentity(T3, "GB333333333", "0088", SHARED_GLN)
        .with(T_CAPPED, "GBP", "GB")
        .withLimit(T_CAPPED, "documents.mb.max", 0);
  }

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  @BeforeEach
  void clean() throws Exception {
    PurchaseFixtures.truncateAll(PG);
  }

  // ── the flow ─────────────────────────────────────────────────────────────────

  @Test
  void anInvoiceThatNamesItsOrderAndLinesIsCapturedAndKeptAsItArrived() {
    String supplier = supplier("Acme Wholesale", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    byte[] doc =
        ubl(
            invoice(
                "INV-100",
                "GB999999973",
                null,
                OUR_VAT,
                po,
                "380",
                null,
                line("Apples", "A-1", "1", "10", "2.50")));

    JsonObject received = data(send(doc, "application/xml", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("CAPTURED"));
    assertThat(received.getString("syntax"), is("UBL"));
    String invoiceId = received.getString("supplierInvoiceId");
    JsonObject invoice = data(get("/supplier-invoices/" + invoiceId, T, "OWNER"), 200);
    assertThat(invoice.toString(), invoice.getString("status"), is("MATCHED"));
    assertThat(invoice.getString("invoiceNumber"), is("INV-100"));

    String id = received.getString("id");
    Response original = as("/e-invoices/" + id + "/document", T, "OWNER").get();
    assertThat(original.getStatus(), is(200));
    assertArrayEquals(doc, original.readEntity(byte[].class));

    JsonObject again = data(send(doc, "application/xml", T, "OWNER"), 200);
    assertThat(again.getString("id"), is(id));
    assertThat(again.getBoolean("alreadyReceived"), is(true));
    assertThat(count("supplier_invoices"), is(1));
    assertThat(text(get("/e-invoices?status=CAPTURED", T, "OWNER"), 200), containsString(id));
  }

  @Test
  void aFacturXPdfIsReadFromTheCiiInsideIt() {
    String supplier = supplier("Dupont SA", "GB999999973", null, null);
    String po = receivedOrder(supplier, 4, "12.00");
    byte[] pdf =
        EInvoices.toFacturX(
            invoice(
                "FX-7",
                "GB999999973",
                null,
                OUR_VAT,
                po,
                "380",
                null,
                line("Cheese", "C-1", "1", "4", "12.00")));
    JsonObject received = data(send(pdf, "application/pdf", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("CAPTURED"));
    assertThat(received.getString("container"), is("PDF"));
    assertThat(received.getString("syntax"), is("CII"));
    assertThat(received.getString("embeddedFilename"), is("factur-x.xml"));
  }

  @Test
  void aNonCompliantInvoiceIsKeptNotCapturedAndCanOnlyBeRefused() {
    String supplier = supplier("Sloppy Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    String xml =
        new String(
                ubl(
                    invoice(
                        "BAD-1",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        po,
                        "380",
                        null,
                        line("Apples", null, "1", "10", "2.50"))),
                StandardCharsets.UTF_8)
            .replace(
                "<cbc:PayableAmount currencyID=\"GBP\">30.00</cbc:PayableAmount>",
                "<cbc:PayableAmount currencyID=\"GBP\">3.00</cbc:PayableAmount>");
    JsonObject received =
        data(send(xml.getBytes(StandardCharsets.UTF_8), "application/xml", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("NOT_COMPLIANT"));
    assertThat(received.toString(), containsString("BR-CO-16"));
    assertThat(count("supplier_invoices"), is(0));
    String id = received.getString("id");

    assertCode(
        post("/e-invoices/" + id + "/match", "{}", T, "OWNER"),
        409,
        "PURCHASE_EINVOICE_NOT_COMPLIANT");
    assertCode(
        post(
            "/e-invoices/" + id + "/refuse",
            "{\"reason\":\"Totals do not add up\"}",
            T,
            "STOREKEEPER"),
        403,
        "");
    assertCode(post("/e-invoices/" + id + "/refuse", "{\"reason\":\" \"}", T, "OWNER"), 400, "");
    JsonObject refused =
        data(
            post(
                "/e-invoices/" + id + "/refuse",
                "{\"reason\":\"Totals do not add up\"}",
                T,
                "OWNER"),
            200);
    assertThat(refused.getString("status"), is("REFUSED"));
    assertThat(refused.getString("decisionReason"), is("Totals do not add up"));
    assertCode(
        post("/e-invoices/" + id + "/refuse", "{\"reason\":\"again\"}", T, "OWNER"),
        409,
        "PURCHASE_EINVOICE_SETTLED");
  }

  @Test
  void anUnknownSupplierWaitsAndItsAddressIsRememberedOnceMatched() {
    String supplier = supplier("Northern Farms", null, null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    byte[] first =
        ubl(
            invoice(
                "NF-1",
                "GB555555555",
                "GB555555555",
                OUR_VAT,
                po,
                "380",
                null,
                line("Apples", null, "1", "10", "2.50")));
    JsonObject waiting = data(send(first, "application/xml", T, "OWNER"), 201);
    assertThat(waiting.toString(), waiting.getString("status"), is("NEEDS_SUPPLIER"));
    assertThat(waiting.getString("problem"), containsString("9932:GB555555555"));

    JsonObject matched =
        data(
            post(
                "/e-invoices/" + waiting.getString("id") + "/match",
                "{\"supplierId\":\"" + supplier + "\",\"remember\":true}",
                T,
                "OWNER"),
            200);
    assertThat(matched.toString(), matched.getString("status"), is("CAPTURED"));
    JsonObject remembered = data(get("/suppliers/" + supplier, T, "OWNER"), 200);
    assertThat(remembered.getString("einvoiceScheme"), is("9932"));
    assertThat(remembered.getString("einvoiceId"), is("GB555555555"));

    String po2 = receivedOrder(supplier, 10, "2.50");
    byte[] second =
        ubl(
            invoice(
                "NF-2",
                "GB555555555",
                "GB555555555",
                OUR_VAT,
                po2,
                "380",
                null,
                line("Apples", null, "1", "10", "2.50")));
    JsonObject next = data(send(second, "application/xml", T, "OWNER"), 201);
    assertThat("found by the address it was taught", next.getString("status"), is("CAPTURED"));
  }

  @Test
  void linesWithoutReferencesWaitAndTheCodeATaughtLineCarriesMatchesTheNextInvoice()
      throws Exception {
    String supplier = supplier("Code Supplies", "GB999999973", null, null);
    String po = receivedOrder(supplier, 6, "5.00");
    JsonObject waiting =
        data(
            send(
                ubl(
                    invoice(
                        "CS-1",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        po,
                        "380",
                        null,
                        line("Widgets", "SKU-9", null, "6", "5.00"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(waiting.toString(), waiting.getString("status"), is("NEEDS_LINES"));
    assertThat(waiting.getString("problem"), containsString("Widgets"));

    String poLine = orderLines(po).get(0);
    assertCode(
        post(
            "/e-invoices/" + waiting.getString("id") + "/match",
            "{\"lines\":[{\"position\":2,\"poLineId\":\"" + poLine + "\"}]}",
            T,
            "OWNER"),
        400,
        "PURCHASE_EINVOICE_LINE_UNKNOWN");
    assertCode(
        post(
            "/e-invoices/" + waiting.getString("id") + "/match",
            "{\"lines\":[{\"position\":1,\"poLineId\":\"" + Ids.newId() + "\"}]}",
            T,
            "OWNER"),
        400,
        "PURCHASE_EINVOICE_LINE_NOT_ON_ORDER");
    JsonObject matched =
        data(
            post(
                "/e-invoices/" + waiting.getString("id") + "/match",
                "{\"remember\":true,\"lines\":[{\"position\":1,\"poLineId\":\"" + poLine + "\"}]}",
                T,
                "OWNER"),
            200);
    assertThat(matched.toString(), matched.getString("status"), is("CAPTURED"));

    String po2 = receivedOrder(supplier, 6, "5.00");
    JsonObject next =
        data(
            send(
                ubl(
                    invoice(
                        "CS-2",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        po2,
                        "380",
                        null,
                        line("Widgets", "SKU-9", null, "6", "5.00"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(next.toString(), next.getString("status"), is("CAPTURED"));
    assertThat(next.toString(), containsString("\"matchedBy\":\"ITEM_CODE\""));
  }

  @Test
  void anInvoiceThatNamesNoOrderWaitsForOneAndAnotherSuppliersOrderIsRefused() {
    String supplier = supplier("No Ref Ltd", "GB999999973", null, null);
    String other = supplier("Someone Else", null, null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    String theirs = receivedOrder(other, 10, "2.50");
    JsonObject waiting =
        data(
            send(
                ubl(
                    invoice(
                        "NR-1",
                        "GB999999973",
                        null,
                        OUR_VAT,
                        "4500012345",
                        "380",
                        null,
                        line("Apples", null, "1", "10", "2.50"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(waiting.toString(), waiting.getString("status"), is("NEEDS_ORDER"));
    String id = waiting.getString("id");
    assertCode(
        post("/e-invoices/" + id + "/match", "{\"poId\":\"" + theirs + "\"}", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_ORDER_NOT_SUPPLIERS");
    JsonObject matched =
        data(post("/e-invoices/" + id + "/match", "{\"poId\":\"" + po + "\"}", T, "OWNER"), 200);
    assertThat(matched.toString(), matched.getString("status"), is("CAPTURED"));
  }

  @Test
  void anInvoiceAddressedToAnotherBusinessIsKeptAndNotCaptured() {
    String supplier = supplier("Wrong Door", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    JsonObject received =
        data(
            send(
                ubl(
                    invoice(
                        "WD-1",
                        "GB999999973",
                        null,
                        "GB987654321",
                        po,
                        "380",
                        null,
                        line("Apples", null, "1", "10", "2.50"))),
                "application/xml",
                T,
                "OWNER"),
            201);
    assertThat(received.toString(), received.getString("status"), is("MISDIRECTED"));
    assertThat(received.getString("problem"), containsString("GB987654321"));
    assertCode(
        post(
            "/e-invoices/" + received.getString("id") + "/match",
            "{\"poId\":\"" + po + "\"}",
            T,
            "OWNER"),
        409,
        "PURCHASE_EINVOICE_MISDIRECTED");
    assertThat(count("supplier_invoices"), is(0));
  }

  @Test
  void theSameInvoiceNumberSentAgainInADifferentFileIsADuplicate() {
    String supplier = supplier("Twice Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 20, "2.50");
    sendApples("TW-1", po, "Apples");
    JsonObject second = sendApples("TW-1", po, "Apples, resent");
    assertThat(second.toString(), second.getString("status"), is("DUPLICATE"));
    assertThat(count("supplier_invoices"), is(1));
  }

  @Test
  void aCreditNoteClosesTheReturnItCredits() {
    String supplier = supplier("Returns Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    sendApples("RT-1", po, "Apples");
    JsonObject ret =
        data(
            post(
                "/vendor-returns",
                "{\"poId\":\""
                    + po
                    + "\",\"reason\":\"DAMAGED\",\"notes\":\"crushed\",\"lines\":[{\"variantId\":\""
                    + VARIANT
                    + "\",\"qty\":2}]}",
                T,
                "OWNER"),
            201);
    assertThat(ret.getString("status"), is("RAISED"));

    byte[] credit =
        ubl(
            invoice(
                "CN-1",
                "GB999999973",
                null,
                OUR_VAT,
                null,
                "381",
                "RT-1",
                line("Apples returned", null, null, "2", "2.50")));
    JsonObject received = data(send(credit, "application/xml", T, "OWNER"), 201);
    assertThat(received.toString(), received.getString("status"), is("CREDITED"));
    assertThat(received.getBoolean("creditNote"), is(true));
    assertThat(received.getString("vendorReturnId"), is(ret.getString("id")));
    assertThat(
        data(get("/vendor-returns/" + ret.getString("id"), T, "OWNER"), 200).getString("status"),
        is("CREDITED"));
  }

  // ── refusals ─────────────────────────────────────────────────────────────────

  @Test
  void hostileDocumentsAndAnotherBusinessesReadsAreRefused() {
    String xxe =
        "<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">&x;</Invoice>";
    assertCode(
        send(xxe.getBytes(StandardCharsets.UTF_8), "application/xml", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_DTD_REFUSED");
    assertCode(
        send("<Order/>".getBytes(StandardCharsets.UTF_8), "application/xml", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_NOT_AN_INVOICE");
    assertCode(
        send("%PDF-1.7 not really".getBytes(StandardCharsets.UTF_8), "application/pdf", T, "OWNER"),
        400,
        "PURCHASE_EINVOICE_PDF_UNREADABLE");
    assertCode(send(new byte[0], "application/xml", T, "OWNER"), 400, "PURCHASE_EINVOICE_EMPTY");
    Response json =
        as("/e-invoices", T, "OWNER").post(Entity.entity("{}", MediaType.APPLICATION_JSON));
    assertThat(json.readEntity(String.class), json.getStatus(), is(415));
    assertThat(count("supplier_einvoices"), is(0));

    String supplier = supplier("Private Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    String id = sendApples("PV-1", po, "Apples").getString("id");
    assertCode(get("/e-invoices/" + id, T2, "OWNER"), 404, "PURCHASE_EINVOICE_NOT_FOUND");
    assertThat(as("/e-invoices/" + id + "/document", T2, "OWNER").get().getStatus(), is(404));
    assertCode(
        post("/e-invoices/" + id + "/refuse", "{\"reason\":\"not mine\"}", T2, "OWNER"),
        404,
        "PURCHASE_EINVOICE_NOT_FOUND");
    assertThat(text(get("/e-invoices", T2, "OWNER"), 200), containsString("\"data\":[]"));
    assertCode(get("/e-invoices?status=LOST", T, "OWNER"), 400, "PURCHASE_EINVOICE_STATUS_INVALID");
  }

  // ── delivered by a network (the transport seam) ───────────────────────────────

  @Test
  void aNetworkDeliversToTheBusinessTheDocumentNamesAndLearnsOnlyTheId() {
    String supplier = supplier("Acme Wholesale", "GB999999973", null, null);
    String po = receivedOrder(supplier, 10, "2.50");
    byte[] doc =
        ubl(
            invoice(
                "AP-100",
                "GB999999973",
                null,
                OUR_VAT,
                po,
                "380",
                null,
                line("Apples", "A-1", "1", "10", "2.50")));

    JsonObject ack =
        data(deliver("peppol", DELIVERY_KEY, "AP-MSG-77", doc, "application/xml"), 201);
    assertThat(ack.toString(), ack.getString("network"), is("PEPPOL"));
    assertThat(ack.getString("reference"), is("AP-MSG-77"));
    assertThat(ack.getBoolean("alreadyReceived"), is(false));
    assertThat(
        "the network learns nothing of the receiver's own",
        ack.containsKey("status") || ack.containsKey("supplierId") || ack.containsKey("lines"),
        is(false));

    // Inside, it went through the intake as an upload does: matched to its order and captured, by
    // nobody, with the network as its channel and the network's reference kept.
    String id = ack.getString("id");
    JsonObject kept = data(get("/e-invoices/" + id, T, "OWNER"), 200);
    assertThat(kept.toString(), kept.getString("channel"), is("PEPPOL"));
    assertThat(kept.getString("deliveryRef"), is("AP-MSG-77"));
    assertThat(kept.getString("status"), is("CAPTURED"));
    assertThat(count("supplier_invoices"), is(1));
    assertThat(receivedBy(id), nullValue());

    JsonObject again =
        data(deliver("PEPPOL", DELIVERY_KEY, "AP-MSG-78", doc, "application/xml"), 200);
    assertThat(again.getString("id"), is(id));
    assertThat(again.getBoolean("alreadyReceived"), is(true));
    assertThat(text(get("/e-invoices", T2, "OWNER"), 200), not(containsString(id)));
  }

  @Test
  void aDocumentNamingItsBuyerByVatNumberAloneLandsAndWaitsForItsSupplier() {
    byte[] doc =
        withoutBuyerEndpoint(
            ubl(
                invoice(
                    "PDP-1",
                    "FR99999999901",
                    null,
                    OUR_VAT,
                    null,
                    "380",
                    null,
                    line("Pommes", "P-1", null, "10", "2.50"))));
    JsonObject ack = data(deliver("fr_pdp", DELIVERY_KEY, null, doc, "application/xml"), 201);
    JsonObject kept = data(get("/e-invoices/" + ack.getString("id"), T, "OWNER"), 200);
    assertThat(kept.toString(), kept.getString("channel"), is("FR_PDP"));
    assertThat(kept.containsKey("deliveryRef") && !kept.isNull("deliveryRef"), is(false));
    // It lands where its VAT number says; what it then is, the rules say: a document claiming
    // Peppol BIS with no buyer endpoint breaks PEPPOL-EN16931-R010, and is kept, not captured.
    assertThat(kept.getString("status"), is("NOT_COMPLIANT"));
    assertThat(kept.toString(), containsString("PEPPOL-EN16931-R010"));
  }

  @Test
  void aDeliveryWithoutTheKeyOrToNobodyIsRefusedBeforeAnythingIsKept() {
    byte[] doc =
        ubl(
            invoice(
                "AP-200",
                "GB999999973",
                null,
                OUR_VAT,
                null,
                "380",
                null,
                line("Apples", "A-1", null, "10", "2.50")));
    String xml = "application/xml";
    assertCode(deliver("peppol", null, null, doc, xml), 401, "PURCHASE_EINVOICE_KEY_REFUSED");
    assertCode(
        deliver("peppol", "not-the-key", null, doc, xml), 401, "PURCHASE_EINVOICE_KEY_REFUSED");
    // Identity headers make nobody a network: without the key they are not even read.
    assertCode(
        deliver("peppol", null, null, doc, xml, "X-Tenant-Id", T, "X-Roles", "OWNER"),
        401,
        "PURCHASE_EINVOICE_KEY_REFUSED");
    assertCode(
        deliver("fax", DELIVERY_KEY, null, doc, xml), 400, "PURCHASE_EINVOICE_NETWORK_UNKNOWN");
    assertCode(
        deliver("ksef", DELIVERY_KEY, null, doc, xml), 400, "PURCHASE_EINVOICE_NETWORK_UNKNOWN");
    assertCode(
        deliver("upload", DELIVERY_KEY, null, doc, xml), 400, "PURCHASE_EINVOICE_NETWORK_UNKNOWN");
    assertCode(
        deliver("peppol", DELIVERY_KEY, "x".repeat(201), doc, xml),
        400,
        "PURCHASE_EINVOICE_REFERENCE_TOO_LONG");
    assertCode(
        deliver("peppol", DELIVERY_KEY, null, new byte[0], xml), 400, "PURCHASE_EINVOICE_EMPTY");
    assertThat(deliver("peppol", DELIVERY_KEY, null, doc, "application/json").getStatus(), is(415));
    String xxe =
        "<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\">&x;</Invoice>";
    assertCode(
        deliver("peppol", DELIVERY_KEY, null, xxe.getBytes(StandardCharsets.UTF_8), xml),
        400,
        "PURCHASE_EINVOICE_DTD_REFUSED");

    byte[] stranger =
        ubl(
            invoice(
                "AP-201",
                "GB999999973",
                null,
                "GB000000001",
                null,
                "380",
                null,
                line("Apples", "A-1", null, "10", "2.50")));
    assertCode(
        deliver("peppol", DELIVERY_KEY, null, stranger, xml),
        404,
        "PURCHASE_EINVOICE_RECEIVER_UNKNOWN");
    byte[] nobody = withoutBuyerEndpoint(doc, "<cbc:CompanyID>" + OUR_VAT + "</cbc:CompanyID>");
    assertCode(
        deliver("peppol", DELIVERY_KEY, null, nobody, xml),
        422,
        "PURCHASE_EINVOICE_RECEIVER_UNNAMED");
    byte[] shared =
        new String(doc, StandardCharsets.UTF_8)
            .replace(
                "<cbc:EndpointID schemeID=\"9932\">" + OUR_VAT + "</cbc:EndpointID>",
                "<cbc:EndpointID schemeID=\"0088\">" + SHARED_GLN + "</cbc:EndpointID>")
            .getBytes(StandardCharsets.UTF_8);
    assertCode(
        deliver("peppol", DELIVERY_KEY, null, shared, xml),
        409,
        "PURCHASE_EINVOICE_RECEIVER_SHARED");
    assertThat(count("supplier_einvoices"), is(0));
  }

  @Test
  void identityHeadersOnADeliveryChooseNothing() {
    byte[] doc =
        ubl(
            invoice(
                "AP-300",
                "GB999999973",
                null,
                OUR_VAT,
                null,
                "380",
                null,
                line("Apples", "A-1", null, "10", "2.50")));
    JsonObject ack =
        data(
            deliver(
                "simulated",
                DELIVERY_KEY,
                "SIM-1",
                doc,
                "application/xml",
                "X-Tenant-Id",
                T2,
                "X-User-Id",
                USER,
                "X-Roles",
                "OWNER"),
            201);
    String id = ack.getString("id");
    assertThat(
        data(get("/e-invoices/" + id, T, "OWNER"), 200).getString("channel"), is("SIMULATED"));
    assertCode(get("/e-invoices/" + id, T2, "OWNER"), 404, "PURCHASE_EINVOICE_NOT_FOUND");
    assertThat("written by nobody, not the header's user", receivedBy(id), nullValue());
  }

  @Test
  void tenPeopleMatchingOneInvoiceAtOnceMakeOneInvoice() throws Exception {
    String supplier = supplier("Busy Ltd", "GB999999973", null, null);
    String po = receivedOrder(supplier, 6, "5.00");
    String id =
        data(
                send(
                    ubl(
                        invoice(
                            "BZ-1",
                            "GB999999973",
                            null,
                            OUR_VAT,
                            po,
                            "380",
                            null,
                            line("Widgets", "SKU-1", null, "6", "5.00"))),
                    "application/xml",
                    T,
                    "OWNER"),
                201)
            .getString("id");
    String poLine = orderLines(po).get(0);
    String choice = "{\"lines\":[{\"position\":1,\"poLineId\":\"" + poLine + "\"}]}";
    List<Integer> statuses =
        PaymentRunSteps.inParallel(
            10,
            () -> {
              Response r = post("/e-invoices/" + id + "/match", choice, T, "OWNER");
              r.readEntity(String.class);
              return r.getStatus();
            });
    for (int s : statuses) assertThat(statuses.toString(), s, anyOf(is(200), is(409)));
    assertThat(count("supplier_invoices"), is(1));
    assertThat(data(get("/e-invoices/" + id, T, "OWNER"), 200).getString("status"), is("CAPTURED"));
  }

  // ── steps ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A plan's cap on documents refuses the one there is no room for, before it is read")
  void theDocumentCapRefusesBeforeParsing() {
    // Refused before it is read, so it need not even be a real document.
    byte[] pdf = "%PDF-1.7 not really".getBytes(StandardCharsets.UTF_8);
    Response refused = send(pdf, "application/pdf", T_CAPPED, "OWNER");
    String body = refused.readEntity(String.class);
    assertThat(body, refused.getStatus(), is(409));
    assertThat(body, containsString("PLAN_LIMIT_REACHED"));
    assertThat(body, containsString("allows 0 MB of supplier e-invoice documents"));
    assertThat(
        "nothing was kept",
        as("/e-invoices?limit=50", T_CAPPED, "OWNER").get().readEntity(String.class),
        containsString("\"data\":[]"));
  }

  private Invocation.Builder as(String pathAndQuery, String tenant, String role) {
    return WebTargets.at(target, pathAndQuery)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-User-Id", USER)
        .header("X-Roles", role);
  }

  private Response post(String path, String json, String tenant, String role) {
    return as(path, tenant, role).post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private static String text(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return body;
  }

  private Response get(String pathAndQuery, String tenant, String role) {
    return as(pathAndQuery, tenant, role).get();
  }

  private Response send(byte[] document, String type, String tenant, String role) {
    return as("/e-invoices", tenant, role).post(Entity.entity(document, type));
  }

  /** A network's delivery: no identity, the key and reference as headers, plus any extra pairs. */
  private Response deliver(
      String network, String key, String reference, byte[] document, String type, String... extra) {
    Invocation.Builder b = WebTargets.at(target, "/e-invoices/inbound/" + network).request();
    if (key != null) b = b.header("X-EInvoice-Key", key);
    if (reference != null) b = b.header("X-EInvoice-Reference", reference);
    for (int i = 0; i + 1 < extra.length; i += 2) b = b.header(extra[i], extra[i + 1]);
    return b.post(Entity.entity(document, type));
  }

  /** The document with its buyer's electronic address taken out, and any other elements named. */
  private static byte[] withoutBuyerEndpoint(byte[] ubl, String... alsoWithout) {
    String xml =
        new String(ubl, StandardCharsets.UTF_8)
            .replace("<cbc:EndpointID schemeID=\"9932\">" + OUR_VAT + "</cbc:EndpointID>", "");
    for (String element : alsoWithout) xml = xml.replace(element, "");
    return xml.getBytes(StandardCharsets.UTF_8);
  }

  private String receivedBy(String id) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT received_by FROM purchase.supplier_einvoices WHERE id = ?")) {
      ps.setObject(1, Ids.parse(id));
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) throw new AssertionError("no document " + id);
        return rs.getString(1);
      }
    } catch (java.sql.SQLException e) {
      throw new AssertionError(e);
    }
  }

  private String supplier(String name, String vat, String scheme, String endpoint) {
    StringBuilder json =
        new StringBuilder("{\"name\":\"")
            .append(name)
            .append("\",\"currency\":\"GBP\",\"paymentTermsDays\":30");
    if (vat != null)
      json.append(",\"vatNumber\":\"").append(vat).append("\",\"vatRegistered\":true");
    if (scheme != null)
      json.append(",\"einvoiceScheme\":\"")
          .append(scheme)
          .append("\",\"einvoiceId\":\"")
          .append(endpoint)
          .append('"');
    return data(post("/suppliers", json.append('}').toString(), T, "OWNER"), 201).getString("id");
  }

  /** A submitted order for {@code qty} of the variant at the price, received in full. */
  private String receivedOrder(String supplierId, int qty, String price) {
    String po =
        data(post("/purchase-orders", PurchaseFixtures.orderJson(supplierId), T, "OWNER"), 201)
            .getString("id");
    data(
        post(
            "/purchase-orders/" + po + "/lines", PurchaseFixtures.lineJson(qty, price), T, "OWNER"),
        201);
    data(post("/purchase-orders/" + po + "/submit", "{}", T, "OWNER"), 200);
    data(post("/goods-receipts", PurchaseFixtures.receiptJson(po, qty), T, "OWNER"), 201);
    return po;
  }

  private List<String> orderLines(String po) throws Exception {
    List<String> ids = new ArrayList<>();
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var ps =
            c.prepareStatement(
                "SELECT id FROM purchase.purchase_order_lines WHERE tenant_id = ?::uuid AND po_id = ?::uuid"
                    + " ORDER BY created_at, id")) {
      ps.setString(1, T);
      ps.setString(2, po);
      try (var rs = ps.executeQuery()) {
        while (rs.next()) ids.add(rs.getString(1));
      }
    }
    return ids;
  }

  private int count(String table) {
    try (var c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = c.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM purchase." + table)) {
      return rs.next() ? rs.getInt(1) : -1;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  // ── documents a supplier sends ──────────────────────────────────────────────

  private record Item(String name, String sellersId, String orderLine, String qty, String price) {}

  private static Item line(
      String name, String sellersId, String orderLine, String qty, String price) {
    return new Item(name, sellersId, orderLine, qty, price);
  }

  private static byte[] ubl(Invoice invoice) {
    return EInvoices.toUbl(invoice).getBytes(StandardCharsets.UTF_8);
  }

  /** Ten apples at 2.50 against the order's first line, from GB999999973, sent by the owner. */
  private JsonObject sendApples(String number, String po, String name) {
    return data(
        send(
            ubl(
                invoice(
                    number,
                    "GB999999973",
                    null,
                    OUR_VAT,
                    po,
                    "380",
                    null,
                    line(name, null, "1", "10", "2.50"))),
            "application/xml",
            T,
            "OWNER"),
        201);
  }

  /** A Peppol BIS Billing 3.0 invoice at 20% VAT that breaks no rule, from a UK supplier to us. */
  private static Invoice invoice(
      String number,
      String sellerVat,
      String sellerEndpoint,
      String buyerVat,
      String orderRef,
      String typeCode,
      String preceding,
      Item... items) {
    List<Invoice.Line> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < items.length; i++) {
      Item it = items[i];
      BigDecimal qty = new BigDecimal(it.qty());
      BigDecimal price = new BigDecimal(it.price());
      BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
      net = net.add(amount);
      lines.add(
          new Invoice.Line(
              Integer.toString(i + 1),
              null,
              null,
              qty,
              "C62",
              amount,
              it.orderLine(),
              null,
              null,
              List.of(),
              new Invoice.Price(price, null, null, null, null),
              "S",
              new BigDecimal("20"),
              new Invoice.Item(
                  it.name(), null, it.sellersId(), null, null, List.of(), null, List.of())));
    }
    BigDecimal vat = net.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
    BigDecimal gross = net.add(vat);
    Invoice.Address london =
        new Invoice.Address("1 High Street", null, null, "London", "E1 6AN", null, "GB");
    Invoice.Identifier from =
        sellerEndpoint != null
            ? new Invoice.Identifier(sellerEndpoint, "9932")
            : new Invoice.Identifier("5790000435975", "0088");
    Invoice.Party seller =
        new Invoice.Party(
            "Supplier Ltd", null, List.of(), null, sellerVat, null, null, from, london, null);
    Invoice.Party buyer =
        new Invoice.Party(
            "Corner Shop Ltd",
            null,
            List.of(),
            null,
            buyerVat,
            null,
            null,
            new Invoice.Identifier(buyerVat, "9932"),
            london,
            null);
    return new Invoice(
        Invoice.PEPPOL_BIS_3,
        Invoice.PEPPOL_BILLING_PROFILE,
        number,
        LocalDate.of(2026, 9, 10),
        typeCode,
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
        preceding == null
            ? List.of()
            : List.of(new Invoice.PrecedingInvoice(preceding, LocalDate.of(2026, 9, 10))),
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
        lines);
  }

  @SuppressWarnings("unused")
  private static final String STORE = STORE_A;
}
