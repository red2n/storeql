package com.storeql.order;

import static com.storeql.order.support.InvoicingStubs.V_STD;
import static com.storeql.order.support.InvoicingStubs.basket;
import static com.storeql.order.support.InvoicingStubs.code;
import static com.storeql.order.support.InvoicingStubs.data;
import static com.storeql.order.support.InvoicingStubs.dataArray;
import static com.storeql.order.support.InvoicingStubs.services;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.storeql.ids.Ids;
import com.storeql.order.support.Till;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Commission statements over HTTP and a real database (store operations & workforce).
 *
 * <p>What only this test can show. <b>Which sales a statement counts</b>: attributed ones, on the
 * day they were supplied, with refunds subtracting on the day they were refunded — three separate
 * SQL decisions over two tables. <b>What is sent to tenant-svc</b>: figures, never rows, which the
 * stub records so the query's answer can be read back. <b>That approval freezes a statement</b> and
 * that only one can stand for a period, which is a partial unique index and not a service check.
 * And <b>that nothing is produced when the arrangements cannot be read</b> — a statement of zeros
 * would be signed off and paid.
 */
@HelidonTest
class CommissionStatementIT {

  private static final String T = Ids.newId().toString();
  private static final String T_OTHER = Ids.newId().toString();
  private static final String S = Ids.newId().toString();
  private static final String S_TWO = Ids.newId().toString();
  private static final String S_OTHER = Ids.newId().toString();
  private static final String ALICE = Ids.newId().toString();
  private static final String BEN = Ids.newId().toString();
  private static final String MANAGER = Ids.newId().toString();

  private static final PostgresSupport PG;
  private static final TenantSvcStub TENANTS;
  private static final JsonStub SERVICES;

  static {
    PG = PostgresSupport.start();
    TENANTS =
        TenantSvcStub.start()
            .with(T, "GBP", "GB")
            .withLegalName(T, "Counter Stores Ltd")
            .withStore(T, S, "GB", "1 High Street", "London", "E1 6AN")
            .withStore(T, S_TWO, "GB", "2 Side Street", "Leeds", "LS1 4AB")
            .withCommission(T, "2")
            .with(T_OTHER, "GBP", "GB")
            .withLegalName(T_OTHER, "Someone Else Ltd")
            .withStore(T_OTHER, S_OTHER, "GB", "1 Other Street", "Hull", "HU1 1AA")
            .withCommission(T_OTHER, "5");
    SERVICES = services();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    for (String p :
        List.of("storeql.order.pricing.enforce", "storeql.order.inventory.reserve-enforce")) {
      System.clearProperty(p);
    }
    SERVICES.close();
    TENANTS.close();
    PG.stop();
  }

  // ── harness ────────────────────────────────────────────────────────────────

  private Till till(String user) {
    return new Till(target).operatedBy(user);
  }

  /** A till sale of one unit at 10.00 net, credited to somebody, supplied and then backdated. */
  private String sold(String tenant, String store, String seller, int daysAgo) {
    String order = place(tenant, store, seller);
    // A till sale is handed over as it is confirmed (SJ-D40), so confirming is the supply: asking
    // for
    // a separate hand-over would be refused, and the FULFILLED history row is what a statement
    // counts.
    Response confirmed = till(MANAGER).post("/orders/" + order + "/confirm", "{}", tenant);
    String text = confirmed.readEntity(String.class);
    assertThat(text, confirmed.getStatus(), is(200));
    assertThat(text, object(text).getJsonObject("data").getString("status"), is("FULFILLED"));
    backdate(tenant, order, daysAgo);
    return order;
  }

  private String place(String tenant, String store, String seller) {
    String body = basket(store, null, "GBP", V_STD, "1");
    if (seller != null) {
      body = body.substring(0, body.length() - 1) + ",\"sellerUserId\":\"" + seller + "\"}";
    }
    Response r = till(MANAGER).post("/orders", body, tenant);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(201));
    return object(text).getJsonObject("data").getString("id");
  }

  private static JsonObject object(String text) {
    try (jakarta.json.JsonReader reader =
        jakarta.json.Json.createReader(new java.io.StringReader(text))) {
      return reader.readObject();
    }
  }

  /**
   * An order as this service holds it; there is no admin list, and the shape carries the seller.
   */
  private JsonObject order(String tenant, String id) {
    Response r = till(MANAGER).get("/orders/" + id, tenant);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(200));
    return object(text).getJsonObject("data");
  }

  /** The statements of a business, newest period first. */
  private List<JsonObject> statements(String tenant, String... params) {
    return dataArray(till(MANAGER).get("/admin/commission/statements", tenant, params))
        .getValuesAs(JsonObject.class);
  }

  /**
   * The statement a draft created, from its own response.
   *
   * <p>Never "the newest statement": every test here trades in the same business, so a period read
   * back by recency is somebody else's, and the first version of this class failed exactly that
   * way.
   */
  private JsonObject drafted(String tenant, String from, String to, String extra) {
    Response r = draft(tenant, from, to, extra);
    String text = r.readEntity(String.class);
    assertThat(text, r.getStatus(), is(201));
    return object(text).getJsonObject("data");
  }

  private JsonObject statement(String tenant, String id) {
    return data(till(MANAGER).get("/admin/commission/statements/" + id, tenant));
  }

  /**
   * Moves a sale's supply — and any refund of it — into a finished period.
   *
   * <p>The fixture, not the subject: nothing sells last month, and what is under test is a
   * statement for a period that is over, which is the only period a statement may cover.
   */
  private static void backdate(String tenant, String orderId, int days) {
    shift(tenant, orderId, days, true, false);
  }

  /**
   * Moves only the refund of a sale, leaving the sale where it is.
   *
   * <p>Separate from {@link #backdate} on purpose: moving both would put the sale in the same month
   * as its refund, and the one thing this is here to show is a refund landing in a
   * <em>different</em> period from the sale.
   */
  private static void refunded(String tenant, String orderId, int days) {
    shift(tenant, orderId, days, false, true);
  }

  /**
   * Moves a sale's supply, its refund, or both, into a finished period.
   *
   * <p>The fixture, not the subject: nothing sells last year, and what is under test is a statement
   * for a period that is over, which is the only period a statement may cover.
   */
  private static void shift(
      String tenant, String orderId, int days, boolean supply, boolean refund) {
    try (Connection c = PG.dataSource().getConnection()) {
      c.setSchema("order");
      if (supply) {
        try (PreparedStatement ps =
            c.prepareStatement(
                "UPDATE order_status_history SET changed_at = changed_at -"
                    + " make_interval(days => ?) WHERE tenant_id = ?::uuid AND order_id = ?::uuid")) {
          ps.setInt(1, days);
          ps.setString(2, tenant);
          ps.setString(3, orderId);
          ps.executeUpdate();
        }
      }
      if (refund) {
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
      }
    } catch (SQLException e) {
      throw new IllegalStateException("could not move " + orderId + " in time", e);
    }
  }

  private Response draft(String tenant, String from, String to, String extra) {
    String body =
        "{\"from\":\""
            + from
            + "\",\"to\":\""
            + to
            + "\""
            + (extra == null ? "" : "," + extra)
            + "}";
    return till(MANAGER).post("/admin/commission/statements", body, tenant);
  }

  private static String day(int daysAgo) {
    return LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).toString();
  }

  // ── the point of the row ───────────────────────────────────────────────────

  @Test
  @DisplayName("A statement counts attributed sales, less refunds, and freezes when approved")
  void statement() {
    // Its own stretch of the past: every test here trades in one business, and two statements
    // that could see each other's sales would pass or fail by the order the class happened to run
    // in.
    String alice = sold(T, S, ALICE, 205);
    sold(T, S, ALICE, 205);
    sold(T, S, BEN, 204);
    // A sale credited to nobody: counted by the shop, and on nobody's statement.
    String nobodys = sold(T, S, ALICE, 205);
    assertThat(
        till(MANAGER)
            .put(
                "/admin/commission/sales/" + nobodys + "/seller",
                "{\"reason\":\"Sold off the shelf, not by a person\"}",
                T,
                "OWNER")
            .getStatus(),
        is(200));

    String id = drafted(T, day(207), day(202), null).getString("id");
    JsonObject full = statement(T, id);
    assertThat(full.getString("status"), is("DRAFT"));
    // Three attributed sales at 10.00 net; the unattributed one is not on anybody's line.
    assertThat(full.getString("netSales"), is("30.00"));
    assertThat("2% of 30.00", full.getString("commission"), is("0.60"));
    List<JsonObject> lines = full.getJsonArray("lines").getValuesAs(JsonObject.class);
    assertThat(lines, hasSize(2));
    JsonObject aliceLine =
        lines.stream()
            .filter(l -> ALICE.equals(l.getString("sellerUserId")))
            .findFirst()
            .orElseThrow();
    assertThat("two sales of 10.00", aliceLine.getString("amount"), is("20.00"));
    assertThat(aliceLine.getString("commission"), is("0.40"));
    assertThat(aliceLine.getString("schemeName"), is("stub scheme"));

    // Figures went to tenant-svc, never rows: the request names days and money, and no order.
    String asked = TENANTS.lastRating();
    assertThat(asked, containsString(ALICE));
    assertThat(asked, containsString("\"net\":20.00"));
    assertThat("no order ever leaves this service", asked, not(containsString(alice)));

    // Approving freezes it, and records who.
    Response approved =
        till(MANAGER).post("/admin/commission/statements/" + id + "/approval", "{}", T);
    assertThat(approved.readEntity(String.class), approved.getStatus(), is(200));
    JsonObject signed = statement(T, id);
    assertThat(signed.getString("status"), is("APPROVED"));
    assertThat(signed.getString("approvedBy"), is(MANAGER));
    assertThat(signed.getString("approvedAt"), not(nullValue()));
    // And it cannot be approved twice, nor thrown away.
    Response again =
        till(MANAGER).post("/admin/commission/statements/" + id + "/approval", "{}", T);
    assertThat(again.getStatus(), is(409));
    assertThat(code(again), is("COMMISSION_STATEMENT_NOT_DRAFT"));
    Response discarded = till(MANAGER).delete("/admin/commission/statements/" + id, T, "OWNER");
    assertThat(discarded.getStatus(), is(409));
  }

  @Test
  @DisplayName("A refund subtracts on the day it was refunded, not from the month it was sold in")
  void refunds() {
    String order = sold(T, S, BEN, 320);
    // Refunded later, in a different period: the sale's month is not re-rated.
    Response refunded =
        till(MANAGER)
            .post(
                "/orders/" + order + "/returns",
                "{\"reason\":\"Changed their mind\",\"items\":[{\"variantId\":\""
                    + V_STD
                    + "\",\"qty\":1,\"condition\":\"SEALED\"}]}",
                T);
    assertThat(refunded.readEntity(String.class), refunded.getStatus(), is(201));
    refunded(T, order, 303);

    // The month it was sold in still counts the sale in full.
    assertThat(drafted(T, day(325), day(315), null).getString("netSales"), is("10.00"));

    // The period the refund fell in carries it as a negative, and earns nothing.
    JsonObject after = drafted(T, day(305), day(301), null);
    assertThat(after.getString("netSales"), is("-10.00"));
    assertThat(
        "nothing is earned on goods that came back", after.getString("commission"), is("0.00"));
    List<JsonObject> lines =
        statement(T, after.getString("id")).getJsonArray("lines").getValuesAs(JsonObject.class);
    assertThat("the sales are still carried", lines, hasSize(1));
    assertThat(lines.get(0).getString("amount"), is("-10.00"));
  }

  @Test
  @DisplayName("Only one statement stands for a period, and a restatement says what it replaced")
  void restatement() {
    sold(T, S, ALICE, 445);
    String firstId = drafted(T, day(450), day(440), null).getString("id");
    assertThat(
        till(MANAGER)
            .post("/admin/commission/statements/" + firstId + "/approval", "{}", T)
            .getStatus(),
        is(200));

    // A second statement for the same period is refused unless it names the one it replaces.
    Response second = draft(T, day(450), day(440), null);
    assertThat(second.getStatus(), is(409));
    assertThat(code(second), is("COMMISSION_STATEMENT_STANDS"));

    String secondId =
        drafted(T, day(450), day(440), "\"supersedes\":\"" + firstId + "\"").getString("id");
    // The first one stands until the restatement is approved: a month must never have nothing.
    assertThat(statement(T, firstId).getString("status"), is("APPROVED"));
    assertThat(
        till(MANAGER)
            .post("/admin/commission/statements/" + secondId + "/approval", "{}", T)
            .getStatus(),
        is(200));
    JsonObject replaced = statement(T, firstId);
    assertThat(replaced.getString("status"), is("SUPERSEDED"));
    assertThat(replaced.getString("supersededBy"), is(secondId));
    assertThat(
        data(till(MANAGER).get("/admin/commission/statements/" + secondId, T))
            .getString("supersedes"),
        is(firstId));
  }

  @Test
  @DisplayName("A sale is credited to somebody else, with the reason kept")
  void credit() {
    String order = sold(T, S, ALICE, 506);
    Response credited =
        till(MANAGER)
            .put(
                "/admin/commission/sales/" + order + "/seller",
                "{\"sellerUserId\":\"" + BEN + "\",\"reason\":\"Ben served the customer\"}",
                T,
                "OWNER");
    assertThat(credited.readEntity(String.class), credited.getStatus(), is(200));
    assertThat(order(T, order).getString("sellerUserId"), is(BEN));

    JsonArray history =
        dataArray(till(MANAGER).get("/admin/commission/sales/" + order + "/seller", T));
    assertThat(history, hasSize(1));
    assertThat(history.getJsonObject(0).getString("fromUserId"), is(ALICE));
    assertThat(history.getJsonObject(0).getString("toUserId"), is(BEN));
    assertThat(history.getJsonObject(0).getString("reason"), is("Ben served the customer"));
    assertThat(history.getJsonObject(0).getString("changedBy"), is(MANAGER));

    // A reason is required, because money follows the change.
    Response noReason =
        till(MANAGER)
            .put(
                "/admin/commission/sales/" + order + "/seller",
                "{\"sellerUserId\":\"" + ALICE + "\"}",
                T,
                "OWNER");
    assertThat(noReason.getStatus(), is(400));
    // And credited to nobody is a real answer, not an omission.
    Response toNobody =
        till(MANAGER)
            .put(
                "/admin/commission/sales/" + order + "/seller",
                "{\"reason\":\"Sold by the shop, not a person\"}",
                T,
                "OWNER");
    assertThat(toNobody.readEntity(String.class), toNobody.getStatus(), is(200));
    JsonObject after = order(T, order);
    assertThat(after.containsKey("sellerUserId") && !after.isNull("sellerUserId"), is(false));
  }

  @Test
  @DisplayName("Nothing is produced when the arrangements cannot be read")
  void failsClosed() {
    sold(T, S, ALICE, 609);
    TENANTS.ratingDown(true);
    try {
      Response refused = draft(T, day(612), day(608), null);
      assertThat(refused.getStatus(), is(503));
      assertThat(code(refused), is("COMMISSION_RATES_UNAVAILABLE"));
      assertThat(
          "no half-made statement is left behind", statements(T, "status", "DRAFT").size(), is(0));
    } finally {
      TENANTS.ratingDown(false);
    }
  }

  @Test
  @DisplayName("A period still trading, and other businesses' statements, are refused")
  void refusals() {
    Response today = draft(T, day(3), day(0), null);
    // Read once: the entity stream closes on the first read, and asserting on the code and then on
    // the words would have this test failing for a reason that has nothing to do with the subject.
    String refusal = today.readEntity(String.class);
    assertThat(refusal, today.getStatus(), is(400));
    assertThat(refusal, containsString("COMMISSION_PERIOD_INVALID"));
    assertThat(refusal, containsString("has not finished"));

    Response backwards = draft(T, day(2), day(9), null);
    assertThat(backwards.getStatus(), is(400));
    Response notADate = draft(T, "the-first", day(2), null);
    assertThat(notADate.getStatus(), is(400));
    assertThat(code(notADate), is("COMMISSION_DATE_INVALID"));
    Response badStatus = till(MANAGER).get("/admin/commission/statements", T, "status", "PAID");
    assertThat(badStatus.getStatus(), is(400));
    assertThat(code(badStatus), is("COMMISSION_STATEMENT_STATUS_UNKNOWN"));

    // A cashier neither produces nor reads a statement.
    assertThat(
        till(MANAGER).getAs("/admin/commission/statements", T, "CASHIER").getStatus(), is(403));

    // Another business's statements are not there at all.
    sold(T, S, ALICE, 711);
    String id = drafted(T, day(713), day(710), null).getString("id");
    assertThat(
        till(MANAGER).get("/admin/commission/statements/" + id, T_OTHER).getStatus(), is(404));
    assertThat(statements(T_OTHER).size(), is(0));
    // Nor can another business re-credit this one's sale.
    String order = sold(T, S, ALICE, 712);
    Response theirs =
        till(MANAGER)
            .put(
                "/admin/commission/sales/" + order + "/seller",
                "{\"sellerUserId\":\"" + BEN + "\",\"reason\":\"not theirs to credit\"}",
                T_OTHER,
                "OWNER");
    assertThat(theirs.getStatus(), is(404));
  }

  @Test
  @DisplayName("A statement for one store counts that store only")
  void perStore() {
    sold(T, S, ALICE, 830);
    sold(T, S_TWO, ALICE, 830);

    JsonObject store = drafted(T, day(832), day(828), "\"storeId\":\"" + S + "\"");
    assertThat(store.getString("storeId"), is(S));
    assertThat("one store's sale only", store.getString("netSales"), is("10.00"));

    // The whole business is a different scope, and both can stand at once.
    JsonObject whole = drafted(T, day(832), day(828), null);
    assertThat(whole.containsKey("storeId") && !whole.isNull("storeId"), is(false));
    assertThat(whole.getString("netSales"), is("20.00"));
    assertThat(
        till(MANAGER)
            .post("/admin/commission/statements/" + store.getString("id") + "/approval", "{}", T)
            .getStatus(),
        is(200));
    assertThat(
        "a store's statement and the business's are different scopes",
        till(MANAGER)
            .post("/admin/commission/statements/" + whole.getString("id") + "/approval", "{}", T)
            .getStatus(),
        is(200));
  }

  @Test
  @DisplayName("An online sale is credited to nobody, and naming a seller on one is refused")
  void onlineHasNoSeller() {
    String body =
        "{\"storeId\":\""
            + S
            + "\",\"channel\":\"ONLINE\",\"fulfilmentType\":\"PICKUP\",\"currency\":\"GBP\","
            + "\"items\":[{\"variantId\":\""
            + V_STD
            + "\",\"qty\":1}],\"sellerUserId\":\""
            + ALICE
            + "\"}";
    Response refused = till(MANAGER).post("/orders", body, T);
    assertThat(refused.getStatus(), is(400));
    assertThat(code(refused), is("ORDER_SELLER_POS_ONLY"));

    Response placed =
        till(MANAGER).post("/orders", body.replace(",\"sellerUserId\":\"" + ALICE + "\"", ""), T);
    String text = placed.readEntity(String.class);
    assertThat(text, placed.getStatus(), is(201));
    JsonObject online = order(T, object(text).getJsonObject("data").getString("id"));
    assertThat(online.containsKey("sellerUserId") && !online.isNull("sellerUserId"), is(false));
  }

  @Test
  @DisplayName("A till sale with no seller named is credited to whoever is at the till")
  void tillCreditsItsOperator() {
    String order = sold(T, S, null, 908);
    assertThat(order(T, order).getString("sellerUserId"), is(MANAGER));
  }
}
