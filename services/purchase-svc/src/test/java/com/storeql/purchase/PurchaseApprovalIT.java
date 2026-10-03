package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Purchase order approval with spend authority limits, end to end.
 *
 * <p>Separate from {@link PurchaseIT} because the two need opposite configuration, and both states
 * matter: with no limits configured, approval is <b>off</b> and submission behaves exactly as it
 * did before this feature — which is what every existing test in PurchaseIT still proves. This
 * class configures limits and proves the control works.
 *
 * <p>The limits below are set up the way a real multi-currency tenant would: roughly equivalent
 * authorities per market rather than one number reused, because one number reused is the defect
 * this feature was designed around.
 */
@HelidonTest
class PurchaseApprovalIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    // The tenants this suite acts for, as tenant-svc would describe them (SJ-D53).
    // Sterling at home, and a rate for the dollar (03.x): a dollar order is measured in sterling.
    TenantSvcStub.start()
        .with(PurchaseApprovalIT.T, "GBP", "GB")
        .withFxRate(PurchaseApprovalIT.T, "USD", "0.79")
        // A business at home in dinars (three minor units) and one in yen (none), each with a
        // rate for the dollar: the translated net is kept at the home currency's own scale.
        .with(PurchaseApprovalIT.T_BHD, "BHD", "BH")
        .withFxRate(PurchaseApprovalIT.T_BHD, "USD", "0.376")
        .with(PurchaseApprovalIT.T_JPY, "JPY", "JP")
        .withFxRate(PurchaseApprovalIT.T_JPY, "USD", "149.37");
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "purchase");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    // A storekeeper may top up shelf stock; a manager runs the store's buying; the owner is
    // unlimited. Sterling, yen, rupee and dinar are configured — the dollar deliberately is NOT, so
    // the fail-closed path has something real to be tested against, and a dollar order at a
    // business at home in yen or dinars is measured (and its translation kept) at home.
    System.setProperty(
        "storeql.purchase.approval.limits",
        "GBP:STOREKEEPER:500,GBP:MANAGER:5000,GBP:OWNER:UNLIMITED,"
            + "JPY:MANAGER:800000,JPY:OWNER:UNLIMITED,"
            + "INR:MANAGER:500000,INR:OWNER:UNLIMITED,"
            + "BHD:MANAGER:2000,BHD:OWNER:UNLIMITED");
  }

  private static final String T = "01a090ae-611e-702c-a97b-d1b8025478e1";
  private static final String T2 = "01a090ae-611e-7037-a4b7-c854f0266ace";
  private static final String T_BHD = "01a090ae-611e-7041-8a11-0000000000b3";
  private static final String T_JPY = "01a090ae-611e-7042-8a11-0000000000a4";
  private static final String STORE_A = "01a090ae-611e-703c-a378-a4972ea461c8";
  private static final String VARIANT = "01a090ae-611e-705c-994c-5daee3fbd033";
  private static final String BUYER = "01a090ae-611e-700b-bde4-50df0324c37c";
  private static final String BOSS = "01a090ae-611e-700f-b645-a14095230b77";

  @Inject WebTarget target;

  @AfterAll
  static void stopDb() {
    PG.stop();
    System.clearProperty("storeql.purchase.approval.limits");
  }

  @BeforeEach
  void truncate() throws Exception {
    try (var conn = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        var st = conn.createStatement()) {
      st.execute(
          "TRUNCATE TABLE purchase.purchase_order_approvals, purchase.goods_receipt_lines,"
              + " purchase.goods_receipts, purchase.purchase_order_lines,"
              + " purchase.purchase_orders, purchase.suppliers, purchase.outbox CASCADE");
    }
  }

  // ── the control ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("An order inside the submitter's authority goes straight to the supplier")
  void withinAuthoritySubmitsDirectly() {
    String po = order("Brighton Provisions", "GBP", "100", "4.00"); // £400, under £500
    Response r = post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"SUBMITTED\""));
    assertThat(body, not(containsString("PENDING_APPROVAL")));
  }

  @Test
  @DisplayName(
      "A dollar order, with no dollar ceiling, is measured in sterling at the business's rate — held or through by the translated figure, which the order keeps")
  void aForeignOrderIsTranslatedIntoTheHomeCurrency() {
    // $4,000 at 0.79 is £3,160: over a storekeeper's £500, and the reason says how it was measured.
    String big = order("Dollar Supplies Inc", "USD", "1000", "4.00");
    Response held = post("/purchase-orders/" + big + "/submit", "{}", "STOREKEEPER", BUYER);
    assertThat(held.getStatus(), is(200));
    String body = held.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"PENDING_APPROVAL\""));
    assertThat(body, containsString("\"fxRate\":0.79"));
    assertThat(body, containsString("\"totalNetHome\":3160.00"));
    assertThat(body, containsString("\"homeCurrency\":\"GBP\""));
    String trail =
        get("/purchase-orders/" + big + "/approvals", "STOREKEEPER", BUYER)
            .readEntity(String.class);
    assertThat(trail, containsString("translated to GBP 3160.00 at 0.79 GBP per USD"));

    // $500 is £395: inside the same storekeeper's authority.
    String small = order("Dollar Sundries Inc", "USD", "125", "4.00");
    Response through = post("/purchase-orders/" + small + "/submit", "{}", "STOREKEEPER", BUYER);
    assertThat(through.getStatus(), is(200));
    String ok = through.readEntity(String.class);
    assertThat(ok, containsString("\"status\":\"SUBMITTED\""));
    assertThat(ok, containsString("\"totalNetHome\":395.00"));
  }

  @Test
  @DisplayName(
      "The translated net is kept at the home currency's own minor units: thousandths of a dinar, whole yen")
  void theTranslatedNetKeepsTheHomeCurrencysScale() {
    // $1.23 at 0.376 is BHD 0.46248 → 0.462: the third decimal survives the write and the read.
    String dinar = foreignOrder(T_BHD, "Gulf Dollar Trading", "1.23");
    Response held =
        send("POST", T_BHD, "/purchase-orders/" + dinar + "/submit", "{}", "OWNER", BOSS, null);
    assertThat(held.readEntity(String.class), held.getStatus(), is(200));
    assertThat(homeNet(T_BHD, dinar), is("0.462"));
    assertThat(storedHomeNet(dinar), is("0.462"));

    // $12.34 at 149.37 is ¥1843.2258 → ¥1843: whole yen, never 1843.00.
    String yen = foreignOrder(T_JPY, "Tokyo Dollar Trading", "12.34");
    Response sent =
        send("POST", T_JPY, "/purchase-orders/" + yen + "/submit", "{}", "OWNER", BOSS, null);
    assertThat(sent.readEntity(String.class), sent.getStatus(), is(200));
    assertThat(homeNet(T_JPY, yen), is("1843"));
    assertThat(storedHomeNet(yen), is("1843"));
  }

  /** A dollar supplier's draft order for one unit at {@code unitPrice}, for {@code tenant}. */
  private String foreignOrder(String tenant, String supplierName, String unitPrice) {
    Response sup =
        send(
            "POST",
            tenant,
            "/suppliers",
            "{\"name\":\"" + supplierName + "\",\"currency\":\"USD\"}",
            "OWNER",
            BOSS,
            null);
    assertThat(sup.getStatus(), is(201));
    Response po =
        send(
            "POST",
            tenant,
            "/purchase-orders",
            "{\"supplierId\":\""
                + extractId(sup.readEntity(String.class))
                + "\",\"storeId\":\""
                + STORE_A
                + "\"}",
            "OWNER",
            BUYER,
            null);
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));
    Response line =
        send(
            "POST",
            tenant,
            "/purchase-orders/" + poId + "/lines",
            "{\"variantId\":\"" + VARIANT + "\",\"qty\":1,\"unitPrice\":" + unitPrice + "}",
            "OWNER",
            BUYER,
            null);
    assertThat(line.getStatus(), is(201));
    return poId;
  }

  /** The order's translated net as the API answers it, at the scale it is answered in. */
  private String homeNet(String tenant, String poId) {
    Response r = send("GET", tenant, "/purchase-orders/" + poId, null, "OWNER", BOSS, null);
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(200));
    return Envelopes.parse(body)
        .getJsonObject("data")
        .getJsonNumber("totalNetHome")
        .bigDecimalValue()
        .toPlainString();
  }

  /** The translated net as the column holds it. */
  private static String storedHomeNet(String poId) {
    return Envelopes.scalar(
        PG, "SELECT total_net_home::text FROM purchase.purchase_orders WHERE id = '" + poId + "'");
  }

  @Test
  @DisplayName("An order above it is held for approval, and the reason names both figures")
  void aboveAuthorityIsHeld() {
    String po = order("Big Order Ltd", "GBP", "1000", "4.00"); // £4,000, over a storekeeper's £500
    Response r = post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"status\":\"PENDING_APPROVAL\""));

    String trail =
        get("/purchase-orders/" + po + "/approvals", "STOREKEEPER", BUYER).readEntity(String.class);
    assertThat(trail, containsString("\"decision\":\"REQUESTED\""));
    // The figure the decision was made against is captured, not re-derived later.
    assertThat(trail, containsString("\"totalNet\":4000.00"));
    assertThat(trail, containsString("\"authority\":500"));
  }

  @Test
  @DisplayName("A manager approves it, and the order goes to the supplier naming who agreed")
  void approvalReleasesTheOrder() {
    String po = order("Approvable Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    Response r = post("/purchase-orders/" + po + "/approve", "{}", "MANAGER", BOSS);
    assertThat(r.getStatus(), is(200));
    String body = r.readEntity(String.class);
    assertThat(body, containsString("\"status\":\"SUBMITTED\""));
    assertThat(body, containsString("\"approvedBy\":\"" + BOSS + "\""));
    assertThat(body, containsString("\"createdBy\":\"" + BUYER + "\""));
  }

  @Test
  @DisplayName("Separation of duties falls out of the limits: the submitter cannot self-approve")
  void theSubmitterCannotApproveTheirOwnOrder() {
    // The order is only here because it exceeded this person's ceiling, so the same check that
    // routed it refuses their approval. No separate "no self-approval" rule is needed — and a
    // separate rule would deadlock a one-person shop, where the owner legitimately does both.
    String po = order("Self Approval Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    Response r = post("/purchase-orders/" + po + "/approve", "{}", "STOREKEEPER", BUYER);
    assertThat(r.getStatus(), is(403));
    assertThat(r.readEntity(String.class), containsString("PURCHASE_APPROVAL_EXCEEDS_AUTHORITY"));
  }

  @Test
  @DisplayName("An approver whose own ceiling is too low is refused as well")
  void approverNeedsEnoughAuthorityToo() {
    String po = order("Very Big Order Ltd", "GBP", "10000", "4.00"); // £40,000, over a manager
    post("/purchase-orders/" + po + "/submit", "{}", "MANAGER", BUYER);

    assertThat(
        post("/purchase-orders/" + po + "/approve", "{}", "MANAGER", BOSS).getStatus(), is(403));
    // The owner is unlimited, so they can.
    assertThat(
        post("/purchase-orders/" + po + "/approve", "{}", "OWNER", BOSS).getStatus(), is(200));
  }

  // ── rejection ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A rejection returns the order to DRAFT with its reason, and must give one")
  void rejectionReturnsToDraft() {
    String po = order("Rejectable Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);

    // A rejection without a reason leaves the buyer no idea what to change.
    Response noReason = post("/purchase-orders/" + po + "/reject", "{}", "MANAGER", BOSS);
    assertThat(noReason.getStatus(), is(400));
    assertThat(
        noReason.readEntity(String.class), containsString("PURCHASE_APPROVAL_REASON_REQUIRED"));

    Response r =
        post(
            "/purchase-orders/" + po + "/reject",
            "{\"reason\":\"Get a second quote first\"}",
            "MANAGER",
            BOSS);
    assertThat(r.getStatus(), is(200));
    assertThat(r.readEntity(String.class), containsString("\"status\":\"DRAFT\""));

    String trail =
        get("/purchase-orders/" + po + "/approvals", "MANAGER", BOSS).readEntity(String.class);
    assertThat(trail, containsString("\"decision\":\"REJECTED\""));
    assertThat(trail, containsString("Get a second quote first"));
  }

  @Test
  @DisplayName("The trail is append-only, so a resubmitted order keeps every earlier decision")
  void theTrailKeepsEveryCycle() {
    String po = order("Cycling Ltd", "GBP", "1000", "4.00");
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);
    post("/purchase-orders/" + po + "/reject", "{\"reason\":\"too much\"}", "MANAGER", BOSS);
    post("/purchase-orders/" + po + "/submit", "{}", "STOREKEEPER", BUYER);
    post("/purchase-orders/" + po + "/approve", "{}", "MANAGER", BOSS);

    String trail =
        get("/purchase-orders/" + po + "/approvals", "MANAGER", BOSS).readEntity(String.class);
    // Two submissions, one rejection, one approval — a trail keeping only the last decision would
    // report an order that was simply approved, which is not what happened.
    assertThat(countOf(trail, "\"decision\":\"REQUESTED\""), is(2));
    assertThat(countOf(trail, "\"decision\":\"REJECTED\""), is(1));
    assertThat(countOf(trail, "\"decision\":\"APPROVED\""), is(1));
  }

  @Test
  @DisplayName("An order not awaiting a decision cannot be approved")
  void cannotApproveWhatIsNotPending() {
    String po = order("Draft Ltd", "GBP", "10", "1.00");
    Response approve = post("/purchase-orders/" + po + "/approve", "{}", "OWNER", BOSS);
    String approveBody = approve.readEntity(String.class);
    assertThat(approveBody, approve.getStatus(), is(409));
    assertThat(
        approveBody,
        com.storeql.test.Envelopes.parse(approveBody).getString("code", null),
        is("PURCHASE_PO_NOT_PENDING_APPROVAL"));
    // Nor rejected: the same refusal, and the order is still a draft either way.
    Response reject =
        post("/purchase-orders/" + po + "/reject", "{\"reason\":\"too much\"}", "OWNER", BOSS);
    String rejectBody = reject.readEntity(String.class);
    assertThat(rejectBody, reject.getStatus(), is(409));
    assertThat(
        rejectBody,
        com.storeql.test.Envelopes.parse(rejectBody).getString("code", null),
        is("PURCHASE_PO_NOT_PENDING_APPROVAL"));
    assertThat(
        get("/purchase-orders/" + po, "OWNER", BOSS).readEntity(String.class),
        containsString("\"status\":\"DRAFT\""));
  }

  // ── multi-currency, which is what makes the ceiling meaningful ──────────────

  @Test
  @DisplayName("A yen order is measured against the yen ceiling, not sterling's")
  void yenIsMeasuredAgainstTheYenCeiling() {
    // ¥700,000 is roughly £3,600 — inside a manager's ¥800,000 authority.
    String inside = order("Tokyo Trading KK", "JPY", "700", "1000");
    assertThat(
        post("/purchase-orders/" + inside + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("\"status\":\"SUBMITTED\""));

    // ¥900,000 is not. Under a single sterling ceiling of 5,000 both of these would have been
    // held — the control would have been useless in Japan rather than merely wrong.
    String outside = order("Osaka Trading KK", "JPY", "900", "1000");
    assertThat(
        post("/purchase-orders/" + outside + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("\"status\":\"PENDING_APPROVAL\""));
  }

  @Test
  @DisplayName("The same NUMBER that passes in yen is held in sterling")
  void theSameNumberMeansDifferentThingsPerCurrency() {
    String jpy = order("Yen Supplier KK", "JPY", "700", "1000"); // ¥700,000 — fine
    String gbp = order("Pound Supplier Ltd", "GBP", "700", "1000"); // £700,000 — not

    assertThat(
        post("/purchase-orders/" + jpy + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("SUBMITTED"));
    assertThat(
        post("/purchase-orders/" + gbp + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("PENDING_APPROVAL"));
  }

  @Test
  @DisplayName("India: a rupee order is measured against the rupee ceiling")
  void rupeeCeiling() {
    String inside = order("Mumbai Supplies Pvt", "INR", "1000", "400"); // ₹400,000
    assertThat(
        post("/purchase-orders/" + inside + "/submit", "{}", "MANAGER", BUYER)
            .readEntity(String.class),
        containsString("SUBMITTED"));
  }

  @Test
  @DisplayName("A currency nobody configured fails CLOSED — even for an unlimited owner")
  void unconfiguredCurrencyIsHeld() {
    // The euro is deliberately absent from the limit table, and the business keeps no rate for it
    // either (the dollar has one, and is measured in sterling — see the translation test). The
    // first order from a market nobody set up is exactly the order nobody reviewed, so it must not
    // be the one that sails through.
    String po = order("EU Wholesale GmbH", "EUR", "1", "1.00"); // €1
    Response r = post("/purchase-orders/" + po + "/submit", "{}", "OWNER", BUYER);

    assertThat(r.readEntity(String.class), containsString("\"status\":\"PENDING_APPROVAL\""));
    // And nobody can approve it either, which is the correct outcome: the answer is to configure
    // the dollar, not to let one person quietly decide it does not need configuring.
    Response approve = post("/purchase-orders/" + po + "/approve", "{}", "OWNER", BOSS);
    assertThat(approve.getStatus(), is(403));
    assertThat(approve.readEntity(String.class), containsString("no purchase authority"));
  }

  // ── discoverability ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("A buyer can ask what they may commit before building the order")
  void spendAuthorityIsDiscoverable() {
    String gbp =
        get("/purchase-orders/spend-authority?currency=GBP", "STOREKEEPER", BUYER)
            .readEntity(String.class);
    assertThat(gbp, containsString("\"ceiling\":500"));
    assertThat(gbp, containsString("\"role\":\"STOREKEEPER\""));

    String jpy =
        get("/purchase-orders/spend-authority?currency=JPY", "MANAGER", BUYER)
            .readEntity(String.class);
    assertThat(jpy, containsString("\"ceiling\":800000"));

    String unlimited =
        get("/purchase-orders/spend-authority?currency=GBP", "OWNER", BOSS)
            .readEntity(String.class);
    assertThat(unlimited, containsString("\"unlimited\":true"));

    // An unconfigured currency says so rather than implying an unlimited ceiling by omission.
    String usd =
        get("/purchase-orders/spend-authority?currency=USD", "OWNER", BOSS)
            .readEntity(String.class);
    assertThat(usd, containsString("no purchase authority is configured for USD"));

    assertThat(
        get("/purchase-orders/spend-authority?currency=POUNDS", "OWNER", BOSS).getStatus(),
        is(400));
  }

  // ── an order with nothing on it is not sent ─────────────────────────────────

  @Test
  @DisplayName(
      "An order with no lines is refused at submission and at approval, by name, and nothing moves")
  void anOrderWithNothingOnItIsNotSent() {
    // Submission: a draft with no lines is not sent, whoever sends it, and stays a draft.
    String empty = emptyOrder("Nothing On It Ltd");
    String events = outboxRows();
    for (String roles : new String[] {"OWNER", "MANAGER", "STOREKEEPER"}) {
      assertThat(
          roles,
          code(
              call("POST", T, "/purchase-orders/" + empty + "/submit", "{}", roles, BUYER, null),
              409),
          is("PURCHASE_PO_HAS_NO_LINES"));
    }
    assertThat("still a draft", statusOf(empty), is("DRAFT"));
    assertThat("no submission recorded", trailRows(empty), is("0"));
    assertThat("nothing announced", outboxRows(), is(events));

    // The same order with a line goes through: it was the missing line that was refused.
    assertThat(
        post(
                "/purchase-orders/" + empty + "/lines",
                "{\"variantId\":\"" + VARIANT + "\",\"qty\":1,\"unitPrice\":1.00}",
                "OWNER",
                BUYER)
            .getStatus(),
        is(201));
    assertThat(
        post("/purchase-orders/" + empty + "/submit", "{}", "OWNER", BUYER).getStatus(), is(200));
    assertThat(statusOf(empty), is("SUBMITTED"));

    // Approval: an order waiting for a decision that has lost its lines is not approved either. It
    // can be sent back, which is what a rejection is for.
    String held = order("Held And Emptied Ltd", "GBP", "1000", "4.00");
    assertThat(
        post("/purchase-orders/" + held + "/submit", "{}", "STOREKEEPER", BUYER).getStatus(),
        is(200));
    assertThat(statusOf(held), is("PENDING_APPROVAL"));
    Envelopes.exec(PG, "DELETE FROM purchase.purchase_order_lines WHERE po_id = '" + held + "'");
    String trail = trailRows(held);
    String announced = outboxRows();
    assertThat(
        code(
            call("POST", T, "/purchase-orders/" + held + "/approve", "{}", "OWNER", BOSS, null),
            409),
        is("PURCHASE_PO_HAS_NO_LINES"));
    assertThat("still waiting", statusOf(held), is("PENDING_APPROVAL"));
    assertThat("no decision recorded", trailRows(held), is(trail));
    assertThat("nothing announced", outboxRows(), is(announced));
    assertThat(
        "a rejection is not refused: it sends the order back to its buyer",
        post("/purchase-orders/" + held + "/reject", "{\"reason\":\"no lines\"}", "OWNER", BOSS)
            .getStatus(),
        is(200));
    assertThat(statusOf(held), is("DRAFT"));
  }

  // ── another business, and another store ─────────────────────────────────────

  @Test
  @DisplayName(
      "Another business, of any role, cannot submit, decide on or read our order, nor can a manager"
          + " held to another store; nothing moves, and the store's own manager still can")
  void anotherBusinessOrAnotherStoreCannotTouchOurOrder() {
    // £4,000 is over a storekeeper's £500 (held for approval) and inside a manager's £5,000.
    String held = order("Held Ltd", "GBP", "1000", "4.00");
    String draft = order("Still Draft Ltd", "GBP", "10", "1.00");
    assertThat(
        post("/purchase-orders/" + held + "/submit", "{}", "STOREKEEPER", BUYER).getStatus(),
        is(200));
    assertThat(statusOf(held), is("PENDING_APPROVAL"));
    String heldTrail = trailRows(held);
    String draftTrail = trailRows(draft);
    String events = outboxRows();

    // Another business: its staff of every role find no such order. Its owner and manager reach the
    // service and are told it is not there; a till or a shelf role is refused as it is at home, and
    // a shopper is refused before any of it.
    for (String roles : new String[] {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"}) {
      assertThat(
          roles + " submitting",
          code(
              call("POST", T2, "/purchase-orders/" + draft + "/submit", "{}", roles, BUYER, null),
              404),
          is("PURCHASE_PO_NOT_FOUND"));
      assertThat(
          roles + " approving",
          code(
              call("POST", T2, "/purchase-orders/" + held + "/approve", "{}", roles, BOSS, null),
              404),
          is("PURCHASE_PO_NOT_FOUND"));
      assertThat(
          roles + " rejecting",
          code(
              call(
                  "POST",
                  T2,
                  "/purchase-orders/" + held + "/reject",
                  "{\"reason\":\"not yours\"}",
                  roles,
                  BOSS,
                  null),
              404),
          is("PURCHASE_PO_NOT_FOUND"));
      assertThat(
          roles + " reading the trail",
          code(
              call("GET", T2, "/purchase-orders/" + held + "/approvals", null, roles, BOSS, null),
              404),
          is("PURCHASE_PO_NOT_FOUND"));
    }
    for (String tenant : new String[] {T, T2}) {
      assertThat(
          call(
                  "POST",
                  tenant,
                  "/purchase-orders/" + draft + "/submit",
                  "{}",
                  "CUSTOMER",
                  BUYER,
                  null)
              .getStatus(),
          is(403));
      assertThat(
          call(
                  "POST",
                  tenant,
                  "/purchase-orders/" + held + "/approve",
                  "{}",
                  "CUSTOMER",
                  BOSS,
                  null)
              .getStatus(),
          is(403));
      assertThat(
          call(
                  "POST",
                  tenant,
                  "/purchase-orders/" + held + "/reject",
                  "{\"reason\":\"x\"}",
                  "CUSTOMER",
                  BOSS,
                  null)
              .getStatus(),
          is(403));
      assertThat(
          call(
                  "GET",
                  tenant,
                  "/purchase-orders/" + held + "/approvals",
                  null,
                  "CUSTOMER",
                  BOSS,
                  null)
              .getStatus(),
          is(403));
      assertThat(
          call(
                  "GET",
                  tenant,
                  "/purchase-orders/spend-authority?currency=GBP",
                  null,
                  "CUSTOMER",
                  BOSS,
                  null)
              .getStatus(),
          is(403));
    }

    // A manager of ours who is held to another store does not act on this store's orders.
    String elsewhere = Ids.newId().toString();
    assertThat(
        code(
            call(
                "POST",
                T,
                "/purchase-orders/" + draft + "/submit",
                "{}",
                "MANAGER",
                BUYER,
                elsewhere),
            403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(
            call(
                "POST",
                T,
                "/purchase-orders/" + held + "/approve",
                "{}",
                "MANAGER",
                BOSS,
                elsewhere),
            403),
        is("STORE_ACCESS_DENIED"));
    assertThat(
        code(
            call(
                "POST",
                T,
                "/purchase-orders/" + held + "/reject",
                "{\"reason\":\"not at this store\"}",
                "MANAGER",
                BOSS,
                elsewhere),
            403),
        is("STORE_ACCESS_DENIED"));

    assertThat("the held order is still waiting", statusOf(held), is("PENDING_APPROVAL"));
    assertThat("the draft is still a draft", statusOf(draft), is("DRAFT"));
    assertThat("no decision was recorded", trailRows(held), is(heldTrail));
    assertThat("no submission was recorded", trailRows(draft), is(draftTrail));
    assertThat("nothing was announced", outboxRows(), is(events));

    // The manager held to this order's store is not turned away: what was refused was the store.
    assertThat(
        call("POST", T, "/purchase-orders/" + draft + "/submit", "{}", "MANAGER", BUYER, STORE_A)
            .getStatus(),
        is(200));
    assertThat(
        call("POST", T, "/purchase-orders/" + held + "/approve", "{}", "MANAGER", BOSS, STORE_A)
            .getStatus(),
        is(200));
    assertThat(statusOf(draft), is("SUBMITTED"));
    assertThat(statusOf(held), is("SUBMITTED"));
  }

  @Test
  @DisplayName(
      "Creating, changing, cancelling, closing, receiving, cross-docking, delivering and invoicing"
          + " an order are for the order's own business and its store")
  void everyWriteOnAnOrderChecksTheBusinessAndTheStore() {
    String draft = order("Writes Ltd", "GBP", "10", "1.00");
    String sent = order("Sent Ltd", "GBP", "10", "1.00");
    assertThat(
        post("/purchase-orders/" + sent + "/submit", "{}", "OWNER", BOSS).getStatus(), is(200));
    String line =
        Envelopes.scalar(
            PG,
            "SELECT id FROM purchase.purchase_order_lines WHERE po_id = '" + draft + "' LIMIT 1");
    String supplier =
        Envelopes.scalar(
            PG, "SELECT supplier_id FROM purchase.purchase_orders WHERE id = '" + draft + "'");
    String elsewhere = Ids.newId().toString();
    String lineBody = "{\"variantId\":\"" + VARIANT + "\",\"qty\":1,\"unitPrice\":1.00}";
    String amendBody = "{\"qty\":2,\"unitPrice\":2.00}";
    String cancelBody = "{\"reason\":\"not now\"}";
    String createBody = "{\"supplierId\":\"" + supplier + "\",\"storeId\":\"" + STORE_A + "\"}";
    String receiptBody =
        "{\"poId\":\""
            + sent
            + "\",\"storeId\":\""
            + STORE_A
            + "\",\"lines\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qtyReceived\":1}]}";
    String sentLine =
        Envelopes.scalar(
            PG,
            "SELECT id FROM purchase.purchase_order_lines WHERE po_id = '" + sent + "' LIMIT 1");
    // A shop the allocation names: never reached, the order's store is checked first.
    String allocateBody = "{\"allocations\":[{\"storeId\":\"" + elsewhere + "\",\"qty\":1}]}";
    String invoiceBody =
        "{\"poId\":\""
            + sent
            + "\",\"invoiceNumber\":\"INV-HELD-1\",\"invoiceDate\":\"2026-09-01\",\"vatAmount\":0,"
            + "\"lines\":[{\"variantId\":\""
            + VARIANT
            + "\",\"qty\":1,\"unitPrice\":1.00}]}";
    // Cross-docking a line, delivering a dropship order and capturing the supplier's invoice are
    // writes on the order as much as changing it is (the last two post to the ledger).
    String[][] orderWrites = {
      {"PUT", "/purchase-orders/" + draft + "/lines/" + line + "/allocations", allocateBody},
      {"POST", "/purchase-orders/" + draft + "/lines/" + line + "/allocations/fill", "{}"},
      {"POST", "/purchase-orders/" + sent + "/dropship-delivered", "{}"},
      {"POST", "/supplier-invoices", invoiceBody},
    };
    String before = writeState();

    // Another business: nothing of ours is there for its owner or manager; a till or a shelf role
    // is refused at home, and a shopper before any of it.
    for (String roles : new String[] {"OWNER", "MANAGER"}) {
      assertThat(
          roles + " creating",
          send("POST", T2, "/purchase-orders", createBody, roles, BUYER, null).getStatus(),
          is(404));
      for (String[] c :
          new String[][] {
            {"POST", "/purchase-orders/" + draft + "/lines", lineBody},
            {"PUT", "/purchase-orders/" + draft + "/lines/" + line, amendBody},
            {"DELETE", "/purchase-orders/" + draft + "/lines/" + line, null},
            {"POST", "/purchase-orders/" + draft + "/cancel", cancelBody},
            {"POST", "/purchase-orders/" + sent + "/close", cancelBody},
            {"POST", "/goods-receipts", receiptBody},
            orderWrites[0],
            orderWrites[1],
            orderWrites[2],
            orderWrites[3],
          }) {
        assertThat(
            roles + " " + c[0] + " " + c[1],
            code(send(c[0], T2, c[1], c[2], roles, BOSS, null), 404),
            is("PURCHASE_PO_NOT_FOUND"));
      }
    }
    // Their storekeeper buys too, and finds none of ours; their cashier is refused at home.
    for (String[] c : orderWrites) {
      assertThat(
          "STOREKEEPER " + c[0] + " " + c[1],
          code(send(c[0], T2, c[1], c[2], "STOREKEEPER", BOSS, null), 404),
          is("PURCHASE_PO_NOT_FOUND"));
      assertThat(
          "CASHIER " + c[0] + " " + c[1],
          send(c[0], T2, c[1], c[2], "CASHIER", BOSS, null).getStatus(),
          is(403));
    }
    for (String tenant : new String[] {T, T2}) {
      assertThat(
          send("POST", tenant, "/purchase-orders", createBody, "CUSTOMER", BUYER, null).getStatus(),
          is(403));
      assertThat(
          send(
                  "POST",
                  tenant,
                  "/purchase-orders/" + draft + "/cancel",
                  cancelBody,
                  "CUSTOMER",
                  BOSS,
                  null)
              .getStatus(),
          is(403));
      assertThat(
          send("POST", tenant, "/goods-receipts", receiptBody, "CUSTOMER", BOSS, null).getStatus(),
          is(403));
      for (String[] c : orderWrites) {
        assertThat(
            "CUSTOMER " + c[0] + " " + c[1],
            send(c[0], tenant, c[1], c[2], "CUSTOMER", BOSS, null).getStatus(),
            is(403));
      }
    }

    // A manager of ours held to another store does none of it at this store.
    assertThat(
        code(send("POST", T, "/purchase-orders", createBody, "MANAGER", BUYER, elsewhere), 403),
        is("STORE_ACCESS_DENIED"));
    for (String[] c :
        new String[][] {
          {"POST", "/purchase-orders/" + draft + "/lines", lineBody},
          {"PUT", "/purchase-orders/" + draft + "/lines/" + line, amendBody},
          {"DELETE", "/purchase-orders/" + draft + "/lines/" + line, null},
          {"POST", "/purchase-orders/" + draft + "/cancel", cancelBody},
          {"POST", "/purchase-orders/" + sent + "/close", cancelBody},
          {"POST", "/goods-receipts", receiptBody},
          orderWrites[0],
          orderWrites[1],
          orderWrites[2],
          orderWrites[3],
        }) {
      assertThat(
          c[0] + " " + c[1],
          code(send(c[0], T, c[1], c[2], "MANAGER", BOSS, elsewhere), 403),
          is("STORE_ACCESS_DENIED"));
    }
    // A storekeeper held to another store is refused the buying writes the same way.
    for (String[] c : orderWrites) {
      assertThat(
          "STOREKEEPER " + c[0] + " " + c[1],
          code(send(c[0], T, c[1], c[2], "STOREKEEPER", BOSS, elsewhere), 403),
          is("STORE_ACCESS_DENIED"));
    }
    // Held to this store, but receiving into another one: refused too.
    assertThat(
        code(
            send(
                "POST",
                T,
                "/goods-receipts",
                receiptBody.replace(STORE_A, elsewhere),
                "MANAGER",
                BOSS,
                STORE_A),
            403),
        is("STORE_ACCESS_DENIED"));
    assertThat("nothing moved", writeState(), is(before));

    // Held to this store, the same manager is not turned away.
    assertThat(
        send("POST", T, "/purchase-orders", createBody, "MANAGER", BUYER, STORE_A).getStatus(),
        is(201));
    assertThat(
        send("POST", T, "/purchase-orders/" + draft + "/lines", lineBody, "MANAGER", BOSS, STORE_A)
            .getStatus(),
        is(201));
    assertThat(
        send(
                "POST",
                T,
                "/purchase-orders/" + draft + "/cancel",
                cancelBody,
                "MANAGER",
                BOSS,
                STORE_A)
            .getStatus(),
        is(200));
    // Past the store check, each answers on the order itself: a sent order is no draft to
    // allocate and no dropship order to deliver, and its invoice is captured.
    assertThat(
        code(
            send(
                "PUT",
                T,
                "/purchase-orders/" + sent + "/lines/" + sentLine + "/allocations",
                allocateBody,
                "MANAGER",
                BOSS,
                STORE_A),
            409),
        is("PURCHASE_ALLOCATION_ORDER_NOT_DRAFT"));
    assertThat(
        code(
            send(
                "POST",
                T,
                "/purchase-orders/" + sent + "/dropship-delivered",
                "{}",
                "MANAGER",
                BOSS,
                STORE_A),
            409),
        is("PURCHASE_PO_NOT_DELIVERABLE"));
    assertThat(
        send("POST", T, "/supplier-invoices", invoiceBody, "STOREKEEPER", BOSS, STORE_A)
            .getStatus(),
        is(201));
  }

  /** Rows a write on an order could move, as one string. */
  private static String writeState() {
    return Envelopes.scalar(
        PG,
        "SELECT (SELECT count(*) FROM purchase.purchase_orders) || '/'"
            + " || (SELECT string_agg(status, ',' ORDER BY id) FROM purchase.purchase_orders) || '/'"
            + " || (SELECT count(*) FROM purchase.purchase_order_lines) || '/'"
            + " || (SELECT coalesce(sum(qty), 0) FROM purchase.purchase_order_lines) || '/'"
            + " || (SELECT count(*) FROM purchase.goods_receipts) || '/'"
            + " || (SELECT count(*) FROM purchase.purchase_order_line_allocations) || '/'"
            + " || (SELECT count(*) FROM purchase.supplier_invoices) || '/'"
            + " || (SELECT count(*) FROM purchase.nominal_ledger_entries) || '/'"
            + " || (SELECT count(*) FROM purchase.outbox)");
  }

  /** Any method, as somebody of a business held to the stores named or to none. */
  private Response send(
      String method,
      String tenant,
      String path,
      String json,
      String roles,
      String userId,
      String storeIds) {
    var req =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("X-User-Id", userId)
            .header("Idempotency-Key", Ids.newId().toString());
    if (storeIds != null) {
      req = req.header("X-Store-Ids", storeIds);
    }
    return json == null
        ? req.build(method).invoke()
        : req.build(method, Entity.entity(json, MediaType.APPLICATION_JSON)).invoke();
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A draft order with no lines, which is what an order is before anyone adds one. */
  private String emptyOrder(String supplierName) {
    Response sup =
        post(
            "/suppliers",
            "{\"name\":\"" + supplierName + "\",\"currency\":\"GBP\"}",
            "OWNER",
            BOSS);
    assertThat(sup.getStatus(), is(201));
    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\""
                + extractId(sup.readEntity(String.class))
                + "\",\"storeId\":\""
                + STORE_A
                + "\"}",
            "OWNER",
            BUYER);
    assertThat(po.getStatus(), is(201));
    return extractId(po.readEntity(String.class));
  }

  private static String statusOf(String poId) {
    return Envelopes.scalar(
        PG, "SELECT status FROM purchase.purchase_orders WHERE id = '" + poId + "'");
  }

  /** How many entries the order's approval trail holds. */
  private static String trailRows(String poId) {
    return Envelopes.scalar(
        PG, "SELECT count(*) FROM purchase.purchase_order_approvals WHERE po_id = '" + poId + "'");
  }

  private static String outboxRows() {
    return Envelopes.scalar(PG, "SELECT count(*) FROM purchase.outbox");
  }

  /** The stable code of a refused answer, after checking its status. */
  private static String code(Response r, int status) {
    String body = r.readEntity(String.class);
    assertThat(body, r.getStatus(), is(status));
    return Envelopes.parse(body).getString("code");
  }

  /** A call as somebody of a business, held to the stores named (comma-separated) or to none. */
  private Response call(
      String method,
      String tenant,
      String path,
      String json,
      String roles,
      String userId,
      String storeIds) {
    var req =
        com.storeql.test.WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-Roles", roles)
            .header("X-User-Id", userId);
    if (storeIds != null) {
      req = req.header("X-Store-Ids", storeIds);
    }
    return "GET".equals(method)
        ? req.get()
        : req.post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  /** A supplier in {@code currency} with one line, returning the draft order's id. */
  private String order(String supplierName, String currency, String qty, String unitPrice) {
    Response sup =
        post(
            "/suppliers",
            "{\"name\":\"" + supplierName + "\",\"currency\":\"" + currency + "\"}",
            "OWNER",
            BOSS);
    assertThat(sup.getStatus(), is(201));
    String supId = extractId(sup.readEntity(String.class));

    Response po =
        post(
            "/purchase-orders",
            "{\"supplierId\":\"" + supId + "\",\"storeId\":\"" + STORE_A + "\"}",
            "OWNER",
            BUYER);
    assertThat(po.getStatus(), is(201));
    String poId = extractId(po.readEntity(String.class));

    Response line =
        post(
            "/purchase-orders/" + poId + "/lines",
            "{\"variantId\":\""
                + VARIANT
                + "\",\"qty\":"
                + qty
                + ",\"unitPrice\":"
                + unitPrice
                + "}",
            "OWNER",
            BUYER);
    assertThat(line.getStatus(), is(201));
    return poId;
  }

  private Response post(String path, String json, String roles, String userId) {
    return target
        .path(path)
        .request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", roles)
        .header("X-User-Id", userId)
        .post(Entity.entity(json, MediaType.APPLICATION_JSON));
  }

  private Response get(String pathAndQuery, String roles, String userId) {
    int q = pathAndQuery.indexOf('?');
    WebTarget t = target.path(q < 0 ? pathAndQuery : pathAndQuery.substring(0, q));
    if (q >= 0) {
      for (String param : pathAndQuery.substring(q + 1).split("&")) {
        int eq = param.indexOf('=');
        t = t.queryParam(param.substring(0, eq), param.substring(eq + 1));
      }
    }
    return t.request()
        .header("X-Tenant-Id", T)
        .header("X-Roles", roles)
        .header("X-User-Id", userId)
        .get();
  }

  private static String extractId(String json) {
    int start = json.indexOf("\"id\":\"") + 6;
    return json.substring(start, json.indexOf("\"", start));
  }

  private static int countOf(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
    return n;
  }
}
