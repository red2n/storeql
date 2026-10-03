package com.storeql.product.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.client.InventoryClient.ReceiveItem;
import com.storeql.product.client.InventoryClient.Unreachable;
import com.storeql.product.config.ServiceConfig;
import com.storeql.test.JsonStub;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the client says to inventory-svc when an import receives stock: the caller, as themselves.
 *
 * <p>It used to send the tenant and the role tier only. inventory-svc holds a caller to their
 * stores only when it is told which they are, so a manager held to one branch was received into
 * any, as if they kept the whole business.
 */
class InventoryClientTest {

  private static final UUID TENANT = Ids.newId();
  private static final String PATH = "/admin/inventory/receive/batch";

  private JsonStub inventory;
  private JsonStub consul;
  private InventoryClient client;

  @BeforeEach
  void start() {
    inventory = JsonStub.start();
    consul = JsonStub.start();
    URI i = URI.create(inventory.baseUrl());
    consul.on(
        "GET",
        "/v1/health/service/inventory-svc",
        200,
        "[{\"Node\":{\"Address\":\""
            + i.getHost()
            + "\"},\"Service\":{\"Address\":\""
            + i.getHost()
            + "\",\"Port\":"
            + i.getPort()
            + "}}]");
    URI c = URI.create(consul.baseUrl());
    client = new InventoryClient();
    client.config =
        new ServiceConfig() {
          @Override
          public String consulHost() {
            return c.getHost();
          }

          @Override
          public int consulPort() {
            return c.getPort();
          }
        };
    client.init();
  }

  @AfterEach
  void stop() {
    inventory.close();
    consul.close();
  }

  private static List<ReceiveItem> items(int n) {
    return java.util.stream.IntStream.range(0, n)
        .mapToObj(k -> new ReceiveItem(Ids.newId().toString(), new BigDecimal("12")))
        .toList();
  }

  private static int linesIn(String body) {
    return body.split("\"variantId\"", -1).length - 1;
  }

  private static final Caller OWNER =
      new Caller(TENANT, Ids.newId(), Set.of("OWNER"), Set.of(), Set.of(), null);

  @Test
  @DisplayName("Stock is received in calls of at most inventory-svc's limit, 500 lines by default")
  void stockIsReceivedInCallsOfAtMostTheLimit() {
    // inventory-svc refuses a call of more lines than storeql.inventory.bulk.receive-max-lines
    // (500) whole, with 400 INVENTORY_BULK_TOO_LARGE: one call for the sheet received nothing.
    inventory.on(
        "POST",
        PATH,
        call ->
            linesIn(call.body()) > 500
                ? new JsonStub.Answer(400, "{\"error\":{\"code\":\"INVENTORY_BULK_TOO_LARGE\"}}")
                : JsonStub.Answer.ok("{\"received\":" + linesIn(call.body()) + ",\"errors\":[]}"));

    var result = client.batchReceive(OWNER, Ids.newId(), items(1201));

    assertThat(result.received(), is(1201));
    assertThat(result.errors(), hasSize(0));
    assertThat(
        inventory.calls().stream().map(c -> linesIn(c.body())).toList(),
        is(List.of(500, 500, 201)));
  }

  @Test
  @DisplayName("A chunk that fails is reported and the others are still received")
  void aFailedChunkIsReportedAndTheRestGoOn() {
    int[] n = {0};
    inventory.on(
        "POST",
        PATH,
        call ->
            ++n[0] == 2
                ? new JsonStub.Answer(503, "{\"error\":{\"code\":\"DOWN\"}}")
                : JsonStub.Answer.ok(
                    "{\"received\":" + (linesIn(call.body()) - 1) + ",\"errors\":[\"one line\"]}"));

    var result = client.batchReceive(OWNER, Ids.newId(), items(1201));

    assertThat("every call was made", inventory.calls(), hasSize(3));
    assertThat("the first and last chunks less a line each", result.received(), is(699));
    assertThat(result.errors(), hasSize(3));
    assertThat(result.errors(), hasItem("one line"));
    assertThat(result.errors(), hasItem("500 lines not received: inventory-svc answered HTTP 503"));
  }

  @Test
  @DisplayName("The chunk size is product-svc's own setting, so it can follow inventory-svc's")
  void theChunkSizeIsASetting() {
    inventory.on(
        "POST",
        PATH,
        call -> JsonStub.Answer.ok("{\"received\":" + linesIn(call.body()) + ",\"errors\":[]}"));
    client.receiveMaxLines = 2;

    var result = client.batchReceive(OWNER, Ids.newId(), items(5));

    assertThat(result.received(), is(5));
    assertThat(
        inventory.calls().stream().map(c -> linesIn(c.body())).toList(), is(List.of(2, 2, 1)));
  }

  @Test
  @DisplayName("Stock is received as the caller: their stores, permissions, login and request")
  void stockIsReceivedAsTheCaller() {
    UUID user = Ids.newId();
    UUID store = Ids.newId();
    inventory.on("POST", PATH, call -> JsonStub.Answer.ok("{\"received\":2,\"errors\":[]}"));
    Caller held =
        new Caller(
            TENANT, user, Set.of("MANAGER"), Set.of(store), Set.of("stock.transfer"), "req-9");

    var result = client.batchReceive(held, store, items(2));

    assertThat(result.received(), is(2));
    var call = inventory.calls().get(0);
    assertThat(call.path(), is(PATH));
    assertThat(call.header("X-Tenant-Id"), is(TENANT.toString()));
    assertThat(call.header("X-User-Id"), is(user.toString()));
    assertThat(call.header("X-Roles"), is("MANAGER"));
    assertThat(call.header("X-Store-Ids"), is(store.toString()));
    assertThat(call.header("X-Permissions"), is("stock.transfer"));
    assertThat(call.header("X-Request-Id"), is("req-9"));
    // Every line names the destination store, which is what inventory-svc holds the caller to.
    assertThat(call.body(), containsString("\"storeId\":\"" + store + "\""));
  }

  @Test
  @DisplayName("A refusal from inventory-svc is reported as lines not received, in its own words")
  void aRefusalIsReportedAsLinesNotReceived() {
    inventory.on(
        "POST",
        PATH,
        403,
        "{\"code\":\"STORE_ACCESS_DENIED\",\"error\":{\"code\":\"STORE_ACCESS_DENIED\"}}");
    Caller held =
        new Caller(TENANT, Ids.newId(), Set.of("MANAGER"), Set.of(Ids.newId()), Set.of(), null);

    var result = client.batchReceive(held, Ids.newId(), items(3));

    assertThat(result.received(), is(0));
    assertThat(result.errors(), hasSize(1));
    assertThat(result.errors().get(0), is("3 lines not received: inventory-svc answered HTTP 403"));
  }

  // ── an inventory-svc that stops answering (2 Oct 2026) ───────────────────────

  /** Points discovery at a port nobody listens on: what a dead inventory-svc looks like. */
  private void inventoryIsGone() {
    JsonStub gone = JsonStub.start();
    URI g = URI.create(gone.baseUrl());
    gone.close();
    consul.on(
        "GET",
        "/v1/health/service/inventory-svc",
        200,
        "[{\"Node\":{\"Address\":\""
            + g.getHost()
            + "\"},\"Service\":{\"Address\":\""
            + g.getHost()
            + "\",\"Port\":"
            + g.getPort()
            + "}}]");
  }

  @Test
  @DisplayName(
      "Once inventory-svc gives no answer, no further chunk is sent and every line is told")
  void noFurtherChunkIsSentOnceInventoryGivesNoAnswer() {
    // The second call is dropped without an answer, as a connection reset or a crash would leave
    // it. Sending the third into the same silence would only make the import wait for nothing.
    int[] n = {0};
    inventory.on(
        "POST",
        PATH,
        call -> {
          if (++n[0] == 2) throw new IllegalStateException("the connection is dropped");
          return JsonStub.Answer.ok("{\"received\":" + linesIn(call.body()) + ",\"errors\":[]}");
        });

    Unreachable e =
        assertThrows(Unreachable.class, () -> client.batchReceive(OWNER, Ids.newId(), items(1201)));

    assertThat("the third chunk was never sent", inventory.calls(), hasSize(2));
    assertThat("the first chunk was received", e.result().received(), is(500));
    assertThat(
        e.result().errors(),
        is(
            List.of(
                "500 lines may not have been received: inventory-svc gave no answer; check the"
                    + " store's stock before receiving them again",
                "201 lines not received: not sent, because inventory-svc had stopped answering")));
  }

  @Test
  @DisplayName("An inventory-svc that cannot be reached receives nothing, and the call says so")
  void anInventoryThatCannotBeReachedReceivesNothing() {
    inventoryIsGone();

    Unreachable e =
        assertThrows(Unreachable.class, () -> client.batchReceive(OWNER, Ids.newId(), items(1201)));

    assertThat(e.result().received(), is(0));
    assertThat(
        e.result().errors(),
        is(
            List.of(
                "500 lines not received: inventory-svc could not be reached",
                "701 lines not received: not sent, because inventory-svc had stopped answering")));
  }

  @Test
  @DisplayName("The breaker is on the call that fails, and a receipt is never sent twice")
  void theBreakerSeesTheFailureAndNothingIsResent() throws NoSuchMethodException {
    // The circuit breaker counts what the method throws: a failure swallowed inside it was never
    // counted, so a dead inventory-svc never opened it. And a receipt carries no idempotency key at
    // inventory-svc, so a call that may have landed is never retried.
    var method =
        InventoryClient.class.getMethod("batchReceive", Caller.class, UUID.class, List.class);
    assertThat(method.isAnnotationPresent(CircuitBreaker.class), is(true));
    assertThat(method.isAnnotationPresent(Retry.class), is(false));
  }

  @Test
  @DisplayName("When the breaker is open, every line is reported as not received")
  void anOpenBreakerReportsEveryLine() {
    assertThat(
        InventoryClient.notAsked(1201).errors(),
        is(
            List.of(
                "1201 lines not received: inventory-svc is not being asked for now, after too"
                    + " many recent failures; receive them again later")));
    assertThat(InventoryClient.notAsked(1201).received(), is(0));
  }

  // ── a quantity inventory-svc would refuse is never sent (2 Oct 2026) ───────────

  /**
   * inventory-svc as it validates a call: whole, before any line is received. A quantity not above
   * zero, with more than three places (trailing zeros dropped) or fifteen whole digits refuses
   * every line sent with it ({@code @Positive @Fits(integer = 15, fraction = 3)}).
   */
  private void inventoryValidatesTheCallWhole() {
    inventory.on(
        "POST",
        PATH,
        call -> {
          var items =
              jakarta.json.Json.createReader(new java.io.StringReader(call.body()))
                  .readObject()
                  .getJsonArray("items");
          for (int k = 0; k < items.size(); k++) {
            BigDecimal qty = items.getJsonObject(k).getJsonNumber("qty").bigDecimalValue();
            boolean fits =
                qty.signum() > 0
                    && qty.precision() - qty.scale() <= 15
                    && qty.stripTrailingZeros().scale() <= 3;
            if (!fits) {
              return new JsonStub.Answer(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\"}}");
            }
          }
          return JsonStub.Answer.ok("{\"received\":" + items.size() + ",\"errors\":[]}");
        });
  }

  @Test
  @DisplayName("A line inventory-svc would refuse is not sent: the rest are received, it is named")
  void aLineInventoryWouldRefuseIsNotSent() {
    inventoryValidatesTheCallWhole();
    String fine = Ids.newId().toString();
    String tooFine = Ids.newId().toString();
    String trailing = Ids.newId().toString();
    String none = Ids.newId().toString();
    String huge = Ids.newId().toString();

    var result =
        client.batchReceive(
            OWNER,
            Ids.newId(),
            List.of(
                new ReceiveItem(fine, new BigDecimal("2")),
                new ReceiveItem(tooFine, new BigDecimal("1.2345")),
                new ReceiveItem(trailing, new BigDecimal("2.5000")),
                new ReceiveItem(none, BigDecimal.ZERO),
                new ReceiveItem(huge, new BigDecimal("1234567890123456"))));

    // One call, carrying only what inventory-svc takes: never refused whole for one line.
    assertThat(inventory.calls(), hasSize(1));
    assertThat(linesIn(inventory.calls().get(0).body()), is(2));
    assertThat(inventory.calls().get(0).body().contains(tooFine), is(false));
    assertThat(result.received(), is(2));
    assertThat(
        result.errors(),
        is(
            List.of(
                tooFine
                    + ": quantity 1.2345 has more decimal places than stock is kept to (3); not"
                    + " received",
                none + ": quantity 0 is not above zero; not received",
                huge
                    + ": quantity 1234567890123456 has more whole digits than stock is kept to"
                    + " (15); not received")));
  }

  @Test
  @DisplayName("Lines that would all be refused ask inventory-svc nothing, and each is named")
  void linesThatWouldAllBeRefusedAskNothing() {
    inventoryValidatesTheCallWhole();
    String negative = Ids.newId().toString();

    var result =
        client.batchReceive(
            OWNER, Ids.newId(), List.of(new ReceiveItem(negative, new BigDecimal("-3"))));

    assertThat(inventory.calls(), hasSize(0));
    assertThat(result.received(), is(0));
    assertThat(
        result.errors(), is(List.of(negative + ": quantity -3 is not above zero; not received")));
  }

  @Test
  @DisplayName("A quantity is judged as inventory-svc judges it: places without trailing zeros")
  void aQuantityIsJudgedAsInventoryJudgesIt() {
    for (String fits :
        new String[] {"1", "0.001", "0.0010000", "2.5000", "999999999999999.999", "1E+3", "+7"}) {
      assertThat(fits, InventoryClient.quantityProblem(new BigDecimal(fits)), is((String) null));
    }
    for (String refused :
        new String[] {"0", "0.000", "-0.5", "0.0001", "1.2345", "1E+15", "1234567890123456"}) {
      assertThat(
          refused, InventoryClient.quantityProblem(new BigDecimal(refused)) != null, is(true));
    }
    assertThat(InventoryClient.quantityProblem(null), is("no quantity"));
  }
}
