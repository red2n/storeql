package com.storeql.order;

import static com.storeql.order.support.InvoicingStubs.V_STD;
import static com.storeql.order.support.InvoicingStubs.V_ZERO;
import static com.storeql.order.support.InvoicingStubs.basket;
import static com.storeql.order.support.InvoicingStubs.business;
import static com.storeql.order.support.InvoicingStubs.code;
import static com.storeql.order.support.InvoicingStubs.data;
import static com.storeql.order.support.InvoicingStubs.dataArray;
import static com.storeql.order.support.InvoicingStubs.services;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.order.support.Till;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E-reporting (18.9, second limb): the transactions an invoice does not cover, over HTTP and a real
 * database.
 *
 * <p>The assertions worth a database are the ones about <b>what is in scope</b>. A sale is reported
 * because no e-invoice carries it, and that exclusion is a {@code NOT EXISTS} against {@code
 * sales_invoices}; the day a sale falls in is the first transition to {@code FULFILLED} in the
 * append-only history; and a refund is a negative operation on the day it happened, split by the
 * rate of the line it returns. None of that can be had from a unit test.
 *
 * <p>Sales are made through the till and then <b>backdated in the database</b>, because a period
 * must have ended before it can be reported and no API backdates a sale. The shift is the fixture,
 * not the thing under test: what is tested is that the query puts each sale in the period its
 * history says.
 */
@HelidonTest
class EReportingIT {

  private static final String T = Ids.newId().toString();
  private static final String S = Ids.newId().toString();
  private static final String T_GB = Ids.newId().toString();
  private static final String S_GB = Ids.newId().toString();
  private static final String T_PEPPOL = Ids.newId().toString();
  private static final String S_PEPPOL = Ids.newId().toString();
  private static final String T_RIVAL = Ids.newId().toString();
  private static final String C_BE = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub SERVICES;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "EUR", "FR")
            .withIdentity(T, "FR32123456789", null, null)
            .withLegalName(T, "Épicerie du Port SARL")
            .withStore(T, S, "FR", "3 rue du Port", "Paris", "75001")
            .with(T_GB, "GBP", "GB")
            .withIdentity(T_GB, "GB123456789", "0088", "5790000435975")
            .withLegalName(T_GB, "Harbour Provisions Ltd")
            .withStore(T_GB, S_GB, "GB", "1 High Street", "London", "E1 6AN")
            .with(T_PEPPOL, "EUR", "FR")
            .withIdentity(T_PEPPOL, "FR44444444444", "0009", "44444444444444")
            .withLegalName(T_PEPPOL, "Peppol Seulement SARL")
            .withStore(T_PEPPOL, S_PEPPOL, "FR", "9 rue Lafayette", "Lyon", "69001")
            .with(T_RIVAL, "EUR", "FR")
            .withIdentity(T_RIVAL, "FR55555555555", null, null)
            .withLegalName(T_RIVAL, "Concurrent SARL")
            // France's duty, in force. Great Britain's absence is the point of one test below.
            .withObligation("FR", "E_REPORTING", "COUNTRY", "2026-09-01", null)
            .withObligation("FR", "E_INVOICING_ISSUE", "COUNTRY", "2026-09-01", null);
    SERVICES = services();
    business(
        SERVICES,
        C_BE,
        "Cafe Bruxelles SPRL",
        "BE0123456789",
        "BE",
        "9925",
        "BE0123456789",
        new String[] {"2 rue Neuve", "Bruxelles", "1000"});
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "orders");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty(
        "storeql.einvoice.secrets-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
    // Set here rather than assumed: a class that ran earlier in this JVM may have turned pricing
    // enforcement off and not turned it back on, and these sales must be priced by the quote — that
    // is where each line's VAT rate comes from, and the rate is what the report is grouped by.
    System.setProperty("storeql.order.pricing.enforce", "true");
  }

  @Inject WebTarget target;

  private Till till;

  @AfterAll
  static void stop() {
    // The JVM is shared with the classes after this one: nothing this class set may outlive it.
    System.clearProperty("storeql.order.pricing.enforce");
    System.clearProperty("storeql.einvoice.secrets-key");
    SERVICES.close();
    TENANTS.close();
    PG.stop();
  }

  private Till till() {
    if (till == null) till = new Till(target);
    return till;
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Chooses a network for the business: the platform standing in for France's platform. */
  private void sendsOver(String tenant, String network, String provider) {
    Response r =
        till()
            .put(
                "/admin/einvoicing/transport",
                "{\"network\":\""
                    + network
                    + "\",\"provider\":\""
                    + provider
                    + "\",\"providerAccount\":\"123456789\"}",
                tenant,
                "OWNER");
    assertThat(r.readEntity(String.class), r.getStatus(), is(200));
  }

  /**
   * Moves a sale's history and its refunds back by {@code days}.
   *
   * <p>The fixture, not the subject: a period must have ended before it is reported, and no route
   * backdates a sale. What is under test is that the query reads the day from the history.
   */
  private static void backdate(String tenant, String orderId, int days) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("orders");
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE order_status_history SET changed_at = changed_at - make_interval(days => ?)"
                  + " WHERE tenant_id = ?::uuid AND order_id = ?::uuid")) {
        ps.setInt(1, days);
        ps.setString(2, tenant);
        ps.setString(3, orderId);
        ps.executeUpdate();
      }
      // created_at as well as completed_at: a till refund is completed as it is recorded and leaves
      // completed_at null, which is exactly why the query coalesces the two.
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE returns SET created_at = created_at - make_interval(days => ?),"
                  + " completed_at = completed_at - make_interval(days => ?)"
                  + " WHERE tenant_id = ?::uuid AND order_id = ?::uuid")) {
        ps.setInt(1, days);
        ps.setInt(2, days);
        ps.setString(3, tenant);
        ps.setString(4, orderId);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not backdate " + orderId, e);
    }
  }

  private static void backdateInvoice(String tenant, String orderId, int days) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("orders");
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE sales_invoices SET issue_date = issue_date - make_interval(days => ?)"
                  + " WHERE tenant_id = ?::uuid AND order_id = ?::uuid")) {
        ps.setInt(1, days);
        ps.setString(2, tenant);
        ps.setString(3, orderId);
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not backdate the invoice of " + orderId, e);
    }
  }

  private JsonObject preview(String tenant, String returnCode, LocalDate from, LocalDate to) {
    Response r =
        till()
            .getAs(
                "/admin/ereporting/preview",
                tenant,
                "OWNER",
                "return",
                returnCode,
                "from",
                from.toString(),
                "to",
                to.toString());
    JsonObject body = data(r);
    assertThat(body.toString(), r.getStatus(), is(200));
    return body;
  }

  private Response submit(String tenant, String returnCode, LocalDate from, LocalDate to) {
    return submit(tenant, returnCode, from, to, null);
  }

  private Response submit(
      String tenant, String returnCode, LocalDate from, LocalDate to, String corrects) {
    return asManager(
        "/admin/ereporting/submissions",
        tenant,
        "{\"returnCode\":\""
            + returnCode
            + "\",\"periodStart\":\""
            + from
            + "\",\"periodEnd\":\""
            + to
            + "\""
            + (corrects == null ? "" : ",\"corrects\":\"" + corrects + "\"")
            + "}");
  }

  /**
   * A POST as a manager with a user id, which the route records as who reported the period.
   *
   * <p>{@link Till} sends a tenant and a role and no user; a filing has an author, so this one
   * does.
   */
  private Response asManager(String path, String tenant, String json) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", tenant)
        .header("X-Roles", "OWNER")
        .header("X-User-Id", USER)
        .post(jakarta.ws.rs.client.Entity.json(json));
  }

  /**
   * A till sale, with the body in the failure message.
   *
   * <p>Its own rather than {@link Till}'s so that a refusal says what the platform answered: a sale
   * that will not place is the first thing to know about, and a null envelope tells nobody
   * anything.
   */
  private String sell(String basket, String tenant) {
    Response placed = till().post("/orders", basket, tenant);
    String body = placed.readEntity(String.class);
    assertThat("placing: " + body, placed.getStatus(), is(201));
    String id =
        jakarta.json.Json.createReader(new java.io.StringReader(body))
            .readObject()
            .getJsonObject("data")
            .getString("id");
    Response confirmed = till().post("/orders/" + id + "/confirm", "{}", tenant);
    assertThat("confirming: " + confirmed.readEntity(String.class), confirmed.getStatus(), is(200));
    return id;
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A shop's own sales are reported by day and rate, and an invoiced sale is not")
  void whatIsInScope() {
    sendsOver(T, "FR_PDP", "SIMULATED");
    // Two till sales four days ago: one basket at two rates, one at the standard rate.
    String mixed = sell(basket(S, null, "EUR", V_STD, "1", V_ZERO, "2"), T);
    String plain = sell(basket(S, null, "EUR", V_STD, "3"), T);
    backdate(T, mixed, 4);
    backdate(T, plain, 4);

    // A sale to a business, which gets an e-invoice: the other limb reports it, so this one must
    // not.
    String invoiced = sell(basket(S, C_BE, "EUR", V_STD, "1"), T);
    till().documentsOf(invoiced, T, 1);
    backdate(T, invoiced, 4);
    backdateInvoice(T, invoiced, 4);

    LocalDate from = today().minusDays(5);
    LocalDate to = today().minusDays(2);
    JsonObject p = preview(T, "EREPORTING_TX_FR", from, to);

    assertThat(p.getString("currency"), is("EUR"));
    assertThat(
        "two shop sales, and the invoiced one excluded", p.getInt("transactionCount"), is(3));
    var days = p.getJsonArray("days");
    assertThat(days.toString(), days.size(), is(1));
    JsonObject day = days.getJsonObject(0);
    assertThat(
        "one basket is one operation, whatever rates it spanned",
        day.getInt("transactionCount"),
        is(2));
    assertThat("both rates are there", day.getJsonArray("rates").size(), is(2));

    // The invoiced sale appears once — as a cross-border operation, at invoice level, because the
    // buyer's VAT identifier is Belgian and the business is French.
    var abroad = p.getJsonArray("crossBorder");
    assertThat(abroad.toString(), abroad.size(), is(1));
    assertThat(abroad.getJsonObject(0).getString("buyerCountry"), is("BE"));
  }

  @Test
  @DisplayName("A refund lowers the day it happened, and the report with it")
  void refundsComeOffTheDay() {
    sendsOver(T, "FR_PDP", "SIMULATED");
    String sale = sell(basket(S, null, "EUR", V_STD, "2"), T);
    Response refunded =
        till()
            .post(
                "/orders/" + sale + "/returns",
                "{\"reason\":\"CHANGED_MIND\",\"items\":[{\"variantId\":\""
                    + V_STD
                    + "\",\"qty\":1,\"condition\":\"SEALED\",\"refundAmount\":12.00}]}",
                T);
    assertThat(refunded.readEntity(String.class), refunded.getStatus(), is(201));
    backdate(T, sale, 8);

    JsonObject p = preview(T, "EREPORTING_TX_FR", today().minusDays(9), today().minusDays(6));
    // 2 x 10.00 net sold, one returned: the platform refunds the line's net unit price, so 10.00
    // net and 2.00 of VAT come back out of the day.
    assertThat(p.getString("netTotal"), is("10.00"));
    assertThat(p.getString("vatTotal"), is("2.00"));
    assertThat("the sale and the refund are two operations", p.getInt("transactionCount"), is(2));
  }

  @Test
  @DisplayName("A period is reported once, kept byte for byte, and its digest is the filing's")
  void reportingAPeriod() {
    sendsOver(T, "FR_PDP", "SIMULATED");
    String sale = sell(basket(S, null, "EUR", V_STD, "1"), T);
    backdate(T, sale, 12);
    LocalDate from = today().minusDays(13);
    LocalDate to = today().minusDays(10);

    JsonObject sent = data(submit(T, "EREPORTING_TX_FR", from, to));
    assertThat(sent.getString("status"), is("ACCEPTED"));
    assertThat(sent.getString("payloadDigest").length(), is(44));
    assertThat(sent.getString("network"), is("FR_PDP"));

    // The document as transmitted, byte for byte.
    Response doc =
        till()
            .getAs(
                "/admin/ereporting/submissions/" + sent.getString("id") + "/document", T, "OWNER");
    String xml = doc.readEntity(String.class);
    assertThat(xml, doc.getStatus(), is(200));
    assertThat(xml, containsString("<EReporting"));
    assertThat(xml, containsString("<NumeroTVA>FR32123456789</NumeroTVA>"));
    assertThat(xml, containsString("nombreOperations=\"1\""));

    // Twice is refused: the period has a submission that stands.
    Response again = submit(T, "EREPORTING_TX_FR", from, to);
    assertThat(again.readEntity(String.class), again.getStatus(), is(409));

    // A correction supersedes it, and both stay on the record.
    JsonObject corrected = data(submit(T, "EREPORTING_TX_FR", from, to, sent.getString("id")));
    assertThat(corrected.getString("supersedes"), is(sent.getString("id")));
    JsonObject first =
        data(till().getAs("/admin/ereporting/submissions/" + sent.getString("id"), T, "OWNER"));
    assertThat(first.getString("supersededBy"), is(corrected.getString("id")));
  }

  @Test
  @DisplayName("A period with nothing in it is reported all the same")
  void anEmptyPeriodIsStillAReport() {
    // Silence is indistinguishable from a platform that stopped working.
    sendsOver(T, "FR_PDP", "SIMULATED");
    LocalDate from = today().minusDays(40);
    LocalDate to = today().minusDays(37);
    JsonObject p = preview(T, "EREPORTING_TX_FR", from, to);
    assertThat(p.getBoolean("nothingToReport"), is(true));

    JsonObject sent = data(submit(T, "EREPORTING_TX_FR", from, to));
    assertThat(sent.getString("status"), is("ACCEPTED"));
    Response doc =
        till()
            .getAs(
                "/admin/ereporting/submissions/" + sent.getString("id") + "/document", T, "OWNER");
    assertThat(doc.readEntity(String.class), containsString("<Neant>true</Neant>"));
  }

  @Test
  @DisplayName("A period that has not ended, or is longer than a month, is refused")
  void periodsAreChecked() {
    sendsOver(T, "FR_PDP", "SIMULATED");
    Response future = submit(T, "EREPORTING_TX_FR", today().minusDays(2), today().plusDays(1));
    assertThat(code(future), is("EREPORTING_PERIOD_INVALID"));
    assertThat(future.getStatus(), is(400));

    Response wide = submit(T, "EREPORTING_TX_FR", today().minusDays(200), today().minusDays(1));
    assertThat(code(wide), is("EREPORTING_PERIOD_INVALID"));
  }

  @Test
  @DisplayName("An unknown return is refused before anything is read")
  void unknownReturn() {
    sendsOver(T, "FR_PDP", "SIMULATED");
    Response r = submit(T, "EREPORTING_VAT_XX", today().minusDays(4), today().minusDays(1));
    assertThat(code(r), is("EREPORTING_RETURN_UNKNOWN"));
  }

  @Test
  @DisplayName("Where the duty does not bind, nothing is reported")
  void notDue() {
    // A British business owes no French e-reporting, and the refusal comes from tenant-svc's
    // obligations rather than from a country list in this service.
    sendsOver(T_GB, "PEPPOL", "SIMULATED");
    Response r = submit(T_GB, "EREPORTING_TX_FR", today().minusDays(4), today().minusDays(1));
    assertThat(code(r), is("EREPORTING_NOT_DUE"));
    assertThat(r.getStatus(), is(409));
  }

  @Test
  @DisplayName("A network that carries invoices and not reports refuses to take one")
  void networkCannotReport() {
    // A Peppol access point takes invoices. Silently accepting a report would leave a business
    // believing it had reported.
    sendsOver(T_PEPPOL, "PEPPOL", "SIMULATED");
    Response r = submit(T_PEPPOL, "EREPORTING_TX_FR", today().minusDays(4), today().minusDays(1));
    assertThat(code(r), is("EREPORTING_NETWORK_CANNOT_REPORT"));
    assertThat(r.getStatus(), is(409));
  }

  @Test
  @DisplayName("With no network at all there is nowhere to report")
  void noNetwork() {
    Response r = submit(T_RIVAL, "EREPORTING_TX_FR", today().minusDays(4), today().minusDays(1));
    assertThat(code(r), is("EREPORTING_TRANSPORT_NOT_SET"));
  }

  @Test
  @DisplayName("Another business cannot see this one's submissions")
  void tenantsAreSeparate() {
    sendsOver(T, "FR_PDP", "SIMULATED");
    String sale = sell(basket(S, null, "EUR", V_STD, "1"), T);
    backdate(T, sale, 20);
    JsonObject sent =
        data(submit(T, "EREPORTING_TX_FR", today().minusDays(21), today().minusDays(18)));

    Response mine =
        till().getAs("/admin/ereporting/submissions/" + sent.getString("id"), T, "OWNER");
    assertThat(mine.getStatus(), is(200));
    Response theirs =
        till().getAs("/admin/ereporting/submissions/" + sent.getString("id"), T_RIVAL, "OWNER");
    assertThat(theirs.getStatus(), is(404));
    assertThat(
        dataArray(till().getAs("/admin/ereporting/submissions", T_RIVAL, "OWNER")).toString(),
        not(containsString(sent.getString("id"))));
  }

  @Test
  @DisplayName("A cashier neither previews nor reports")
  void onlyManagement() {
    Response preview =
        till()
            .getAs(
                "/admin/ereporting/preview",
                T,
                "CASHIER",
                "return",
                "EREPORTING_TX_FR",
                "from",
                today().minusDays(4).toString(),
                "to",
                today().minusDays(1).toString());
    assertThat(preview.getStatus(), is(403));
    Response r =
        target
            .path("/admin/ereporting/submissions")
            .request()
            .header("X-Tenant-Id", T)
            .header("X-Roles", "CASHIER")
            .header("X-User-Id", USER)
            .post(
                jakarta.ws.rs.client.Entity.json(
                    "{\"returnCode\":\"EREPORTING_TX_FR\",\"periodStart\":\""
                        + today().minusDays(4)
                        + "\",\"periodEnd\":\""
                        + today().minusDays(1)
                        + "\"}"));
    assertThat(r.getStatus(), is(403));
  }
}
