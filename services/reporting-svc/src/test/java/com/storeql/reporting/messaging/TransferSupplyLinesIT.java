package com.storeql.reporting.messaging;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.reporting.service.ReportingService;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stock in transit, from the transfer events to the supply/demand report, over a real database.
 *
 * <p>The events here are written the way inventory-svc writes them ({@code Events.transferOrder*}):
 * the shipment and the receipt are <b>two events with two event ids</b> that share one {@code
 * aggregateId}, the transfer order. That is the whole difficulty: a receipt cannot name the
 * shipment's event id, so what it retires is found by the transfer. They arrive on two topics, so a
 * receipt can also be read before its shipment.
 */
@HelidonTest
class TransferSupplyLinesIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("reporting");

  private static final String SHIPPED = "storeql.inventory.transfer-order-shipped";
  private static final String RECEIVED = "storeql.inventory.transfer-order-received";
  private static final String[] STAFF = {"OWNER", "MANAGER", "STOREKEEPER", "CASHIER"};
  private static final String SHOPPER = "CUSTOMER";

  @Inject StockEventDispatcher dispatcher;
  @Inject ReportingService reporting;
  @Inject WebTarget target;

  // One business per test: the class shares a database, so each test seeds its own.
  private UUID tenant;
  private UUID otherTenant;
  private UUID warehouse;
  private UUID shop;
  private UUID cola;
  private UUID crisps;

  @BeforeEach
  void freshBusiness() {
    tenant = Ids.newId();
    otherTenant = Ids.newId();
    warehouse = Ids.newId();
    shop = Ids.newId();
    cola = Ids.newId();
    crisps = Ids.newId();
  }

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  // ── the events, as inventory-svc writes them ───────────────────────────────

  private record Line(UUID variant, String qty) {}

  private static String lines(Line... lines) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(lines[i].variant())
          .append("\",\"qty\":")
          .append(lines[i].qty())
          .append('}');
    }
    return sb.append(']').toString();
  }

  /** A TransferOrderShipped: its own fresh event id, the transfer as the aggregate. */
  private static String shipped(
      UUID business, UUID transfer, UUID from, UUID to, String type, Line... lines) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"TransferOrderShipped\",\"tenantId\":\""
        + business
        + "\",\"aggregateId\":\""
        + transfer
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\",\"fromStoreId\":\""
        + from
        + "\",\"toStoreId\":\""
        + to
        + "\",\"lines\":"
        + lines(lines)
        + (type == null ? "" : ",\"transferType\":\"" + type + "\"")
        + "}";
  }

  /** A TransferOrderReceived: a different event id from the shipment's, the same aggregate. */
  private static String received(UUID business, UUID transfer, UUID from, UUID to, Line... lines) {
    return "{\"eventId\":\""
        + Ids.newId()
        + "\",\"eventType\":\"TransferOrderReceived\",\"tenantId\":\""
        + business
        + "\",\"aggregateId\":\""
        + transfer
        + "\",\"occurredAt\":\""
        + Instant.now()
        + "\",\"fromStoreId\":\""
        + from
        + "\",\"toStoreId\":\""
        + to
        + "\",\"lines\":"
        + lines(lines)
        + "}";
  }

  private String ship(UUID business, UUID transfer, Line... lines) {
    String event = shipped(business, transfer, warehouse, shop, "INTRANSIT", lines);
    dispatcher.dispatch(SHIPPED, event);
    return event;
  }

  private String receive(UUID business, UUID transfer, Line... lines) {
    String event = received(business, transfer, warehouse, shop, lines);
    dispatcher.dispatch(RECEIVED, event);
    return event;
  }

  // ── what is held, read behind the app ──────────────────────────────────────

  /** "store|variant|qty" of every open supply line of a business, in no order. */
  private static List<String> open(UUID business) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password());
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT to_store_id, variant_id, qty FROM reporting.open_supply_lines"
                    + " WHERE tenant_id = ?")) {
      ps.setObject(1, business);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              rs.getObject(1)
                  + "|"
                  + rs.getObject(2)
                  + "|"
                  + rs.getBigDecimal(3).stripTrailingZeros().toPlainString());
        }
      }
    }
    return out;
  }

  private String expect(UUID store, UUID variant, String qty) {
    return store + "|" + variant + "|" + qty;
  }

  // ── reading the report ─────────────────────────────────────────────────────

  private Response netting(UUID business, String role, UUID storeScope) {
    var request =
        WebTargets.at(target, "/admin/reports/inventory/supply-demand")
            .request()
            .header("X-Tenant-Id", business.toString())
            .header("X-User-Id", Ids.newId().toString())
            .header("X-Roles", role);
    if (storeScope != null) {
      request = request.header("X-Store-Ids", storeScope.toString());
    }
    return request.get();
  }

  private List<JsonObject> nettingRows(UUID business) {
    try (Response r = netting(business, "OWNER", null)) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(200));
      return Json.createReader(new StringReader(body))
          .readObject()
          .getJsonObject("data")
          .getJsonArray("rows")
          .getValuesAs(JsonObject.class);
    }
  }

  private void onHand(UUID business, UUID store, UUID variant, String qty) {
    reporting.applyStockDeltaOnce(
        Ids.newId(), "test", business, store, variant, new BigDecimal(qty), "StockReceived");
  }

  // ── the tests ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A shipped transfer opens one line per variant, for the store it is bound for")
  void aShippedTransferOpensOneLinePerVariantAtTheDestination() throws SQLException {
    ship(tenant, Ids.newId(), new Line(cola, "6"), new Line(crisps, "2.5"));

    assertThat(
        open(tenant), containsInAnyOrder(expect(shop, cola, "6"), expect(shop, crisps, "2.5")));
  }

  @Test
  @DisplayName("The supply/demand report counts the lines against the destination store's on-hand")
  void theReportNetsTheLinesAgainstTheDestinationStore() {
    onHand(tenant, shop, cola, "4");
    onHand(tenant, shop, crisps, "1");
    onHand(tenant, warehouse, cola, "100");
    ship(tenant, Ids.newId(), new Line(cola, "6"), new Line(crisps, "2.5"));

    List<JsonObject> rows = nettingRows(tenant);
    JsonObject colaAtShop =
        rows.stream()
            .filter(r -> shop.toString().equals(r.getString("storeId")))
            .filter(r -> cola.toString().equals(r.getString("variantId")))
            .findFirst()
            .orElseThrow();
    assertThat(colaAtShop.getJsonNumber("supplyInTransit").bigDecimalValue().intValue(), is(6));
    assertThat(colaAtShop.getJsonNumber("netAvailable").bigDecimalValue().intValue(), is(10));
    JsonObject colaAtWarehouse =
        rows.stream()
            .filter(r -> warehouse.toString().equals(r.getString("storeId")))
            .findFirst()
            .orElseThrow();
    assertThat(
        "the shipping store is owed nothing",
        colaAtWarehouse.getJsonNumber("supplyInTransit").bigDecimalValue().signum(),
        is(0));
  }

  @Test
  @DisplayName("The same shipment delivered again opens nothing more")
  void aRedeliveredShipmentDoesNotDouble() throws SQLException {
    UUID transfer = Ids.newId();
    String event = ship(tenant, transfer, new Line(cola, "6"), new Line(crisps, "2"));

    dispatcher.dispatch(SHIPPED, event);
    dispatcher.dispatch(SHIPPED, event);

    assertThat(open(tenant).size(), is(2));
  }

  @Test
  @DisplayName("A receipt retires exactly the lines of its own transfer")
  void aReceiptRetiresExactlyItsTransfersLines() throws SQLException {
    UUID landing = Ids.newId();
    UUID stillOut = Ids.newId();
    ship(tenant, landing, new Line(cola, "6"), new Line(crisps, "2"));
    ship(tenant, stillOut, new Line(cola, "3"));
    assertThat(open(tenant).size(), is(3));

    receive(tenant, landing, new Line(cola, "6"), new Line(crisps, "2"));

    assertThat(
        "only the other transfer's line stays", open(tenant), contains(expect(shop, cola, "3")));
  }

  @Test
  @DisplayName("A receipt delivered again retires nothing more and breaks nothing")
  void aRedeliveredReceiptIsHarmless() throws SQLException {
    UUID landing = Ids.newId();
    UUID stillOut = Ids.newId();
    ship(tenant, landing, new Line(cola, "6"));
    ship(tenant, stillOut, new Line(crisps, "3"));

    String receipt = receive(tenant, landing, new Line(cola, "6"));
    dispatcher.dispatch(RECEIVED, receipt);

    assertThat(open(tenant), contains(expect(shop, crisps, "3")));
  }

  @Test
  @DisplayName("A receipt read before its shipment leaves nothing in transit afterwards")
  void aReceiptReadFirstLeavesNothingOpen() throws SQLException {
    UUID transfer = Ids.newId();
    // Two topics, no order between them: the landing can be read before the departure.
    String departure = shipped(tenant, transfer, warehouse, shop, "INTRANSIT", new Line(cola, "6"));
    receive(tenant, transfer, new Line(cola, "6"));

    dispatcher.dispatch(SHIPPED, departure);
    dispatcher.dispatch(SHIPPED, departure);

    assertThat("it had already landed", open(tenant), is(empty()));
  }

  @Test
  @DisplayName("A transfer that lands as it ships (DIRECT) is never in transit")
  void aDirectTransferOpensNothing() throws SQLException {
    dispatcher.dispatch(
        SHIPPED, shipped(tenant, Ids.newId(), warehouse, shop, "DIRECT", new Line(cola, "6")));
    // An event that does not say which kind it is cannot be told from one that lands as it ships.
    dispatcher.dispatch(
        SHIPPED, shipped(tenant, Ids.newId(), warehouse, shop, null, new Line(cola, "6")));

    assertThat(open(tenant), is(empty()));
  }

  @Test
  @DisplayName("A shipment with a line that cannot be read opens no line at all")
  void anUnreadableLineOpensNothing() throws SQLException {
    String bad =
        shipped(tenant, Ids.newId(), warehouse, shop, "INTRANSIT", new Line(cola, "6"))
            .replace("\"lines\":[", "\"lines\":[{\"variantId\":\"not-an-id\",\"qty\":1},");
    dispatcher.dispatch(SHIPPED, bad);

    assertThat(open(tenant), is(empty()));
  }

  @Test
  @DisplayName(
      "Another business's receipt naming our transfer retires nothing of ours, and its staff, of"
          + " every role, naming our store, see none of our lines")
  void anotherBusinessCannotRetireOrSeeOurLines() throws SQLException {
    UUID transfer = Ids.newId();
    onHand(tenant, shop, cola, "4");
    ship(tenant, transfer, new Line(cola, "6"));

    // Their TransferOrderReceived, for our transfer's id (and a redelivery of it).
    String theirs = receive(otherTenant, transfer, new Line(cola, "6"));
    dispatcher.dispatch(RECEIVED, theirs);

    assertThat("our line stands", open(tenant), contains(expect(shop, cola, "6")));
    assertThat("nothing was written under theirs", open(otherTenant), is(empty()));
    assertThat(
        "our report still shows it",
        nettingRows(tenant).get(0).getJsonNumber("supplyInTransit").bigDecimalValue().intValue(),
        is(6));

    // Their staff of every role and their shoppers, whose store scope names our store; and our own
    // shopper, who has no business with a back-office report at all.
    List<String> askers = new ArrayList<>(List.of(STAFF));
    askers.add(SHOPPER);
    for (String role : askers) {
      try (Response r = netting(otherTenant, role, shop)) {
        String body = r.readEntity(String.class);
        assertThat(role + " " + body, body.contains(transfer.toString()), is(false));
        assertThat(role + " " + body, body.contains(cola.toString()), is(false));
        assertThat(role + " " + body, body.contains(shop.toString()), is(false));
        if (r.getStatus() == 200) {
          assertThat(
              role + " sees nothing of ours",
              Json.createReader(new StringReader(body))
                  .readObject()
                  .getJsonObject("data")
                  .getJsonArray("rows")
                  .size(),
              is(0));
        } else {
          assertThat(role + " " + body, r.getStatus(), is(403));
        }
      }
    }
    try (Response r = netting(tenant, SHOPPER, null)) {
      String body = r.readEntity(String.class);
      assertThat(body, r.getStatus(), is(403));
      assertThat(body, body.contains(cola.toString()), is(false));
    }
  }

  @Test
  @DisplayName("Another business's receipt, read before our shipment, does not stop it opening")
  void anotherBusinessReceiptReadFirstDoesNotSuppressOurShipment() throws SQLException {
    UUID transfer = Ids.newId();
    receive(otherTenant, transfer, new Line(cola, "6"));

    ship(tenant, transfer, new Line(cola, "6"));

    assertThat(open(tenant), contains(expect(shop, cola, "6")));
  }
}
