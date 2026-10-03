package com.storeql.product.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.product.client.PricingClient.PriceItem;
import com.storeql.product.config.ServiceConfig;
import com.storeql.test.JsonStub;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
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
 * What the client says to pricing-svc: only paths pricing-svc really serves (the stub answers 404
 * to every other, so a wrong path is a failed test), and never more rows in one call than it
 * accepts.
 */
class PricingClientTest {

  private static final UUID TENANT = Ids.newId();
  private static final String LIST_ID = Ids.newId().toString();
  private static final Caller OWNER =
      new Caller(TENANT, Ids.newId(), Set.of("OWNER"), Set.of(), Set.of(), null);

  private JsonStub pricing;
  private JsonStub consul;
  private PricingClient client;

  @BeforeEach
  void start() {
    pricing = JsonStub.start();
    // The default list is found through the staff-readable list endpoint.
    pricing.on(
        "GET",
        "/price-lists",
        200,
        "{\"data\":[{\"id\":\"" + LIST_ID + "\",\"channel\":\"ALL\",\"active\":true}]}");
    consul = JsonStub.start();
    URI p = URI.create(pricing.baseUrl());
    consul.on(
        "GET",
        "/v1/health/service/pricing-svc",
        200,
        "[{\"Node\":{\"Address\":\""
            + p.getHost()
            + "\"},\"Service\":{\"Address\":\""
            + p.getHost()
            + "\",\"Port\":"
            + p.getPort()
            + "}}]");
    URI c = URI.create(consul.baseUrl());
    client = new PricingClient();
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
    pricing.close();
    consul.close();
  }

  private static List<PriceItem> items(int n) {
    List<PriceItem> items = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      items.add(new PriceItem(Ids.newId().toString(), new BigDecimal("1.50")));
    }
    return items;
  }

  private static int rowsIn(String body) {
    return body.split("\"variantId\"", -1).length - 1;
  }

  @Test
  @DisplayName("Prices go to the admin batch path, in calls of at most 500 rows")
  void pricesAreSentInChunksToTheRealPath() {
    String path = "/admin/price-lists/" + LIST_ID + "/items/batch";
    pricing.on(
        "POST",
        path,
        call -> JsonStub.Answer.ok("{\"upserted\":" + rowsIn(call.body()) + ",\"errors\":[]}"));

    var result = client.batchSetPrices(OWNER, "GBP", items(1201));

    assertThat(result.upserted(), is(1201));
    assertThat(result.errors(), hasSize(0));
    var posts = pricing.calls().stream().filter(c -> c.method().equals("POST")).toList();
    assertThat(posts.stream().map(c -> rowsIn(c.body())).toList(), is(List.of(500, 500, 201)));
    assertThat(posts.stream().map(c -> c.path()).distinct().toList(), is(List.of(path)));
  }

  @Test
  @DisplayName("A chunk that fails is reported and the others are still sent")
  void aFailedChunkIsReportedAndTheRestGoOn() {
    String path = "/admin/price-lists/" + LIST_ID + "/items/batch";
    int[] n = {0};
    pricing.on(
        "POST",
        path,
        call ->
            ++n[0] == 1
                ? new JsonStub.Answer(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\"}}")
                : JsonStub.Answer.ok(
                    "{\"upserted\":" + rowsIn(call.body()) + ",\"errors\":[\"one row\"]}"));

    var result = client.batchSetPrices(OWNER, "GBP", items(600));

    assertThat(result.upserted(), is(100));
    assertThat(result.errors(), hasSize(2));
    assertThat(result.errors(), hasItem("one row"));
  }

  @Test
  @DisplayName("Every call to pricing-svc is made as the caller: stores, permissions and login")
  void everyCallIsMadeAsTheCaller() {
    UUID user = Ids.newId();
    UUID store = Ids.newId();
    UUID secondStore = Ids.newId();
    Caller held =
        new Caller(
            TENANT,
            user,
            Set.of("MANAGER"),
            Set.of(store, secondStore),
            Set.of("pricing.write", "stock.adjust"),
            "req-7");
    // No list yet: the client makes the default one, then sets the prices on it.
    pricing.on("GET", "/price-lists", 200, "{\"data\":[]}");
    pricing.on("POST", "/admin/price-lists", 201, "{\"data\":{\"id\":\"" + LIST_ID + "\"}}");
    pricing.on(
        "POST",
        "/admin/price-lists/" + LIST_ID + "/items/batch",
        call -> JsonStub.Answer.ok("{\"upserted\":" + rowsIn(call.body()) + ",\"errors\":[]}"));

    var result = client.batchSetPrices(held, "JPY", items(2));

    assertThat(result.upserted(), is(2));
    var calls = pricing.calls();
    assertThat(
        calls.stream().map(c -> c.method() + " " + c.path()).toList(),
        is(
            List.of(
                "GET /price-lists",
                "POST /admin/price-lists",
                "POST /admin/price-lists/" + LIST_ID + "/items/batch")));
    for (var call : calls) {
      assertThat(call.header("X-Tenant-Id"), is(TENANT.toString()));
      assertThat(call.header("X-User-Id"), is(user.toString()));
      assertThat(call.header("X-Roles"), is("MANAGER"));
      assertThat(
          Set.of(call.header("X-Store-Ids").split(",")),
          is(Set.of(store.toString(), secondStore.toString())));
      assertThat(
          Set.of(call.header("X-Permissions").split(",")),
          is(Set.of("pricing.write", "stock.adjust")));
      assertThat(call.header("X-Request-Id"), is("req-7"));
    }
  }

  @Test
  @DisplayName("A caller held to no store names none, and one holding no permission says so")
  void aCallerHeldToNoStoreAndHoldingNothingIsSaidPlainly() {
    pricing.on(
        "POST",
        "/admin/price-lists/" + LIST_ID + "/items/batch",
        call -> JsonStub.Answer.ok("{\"upserted\":" + rowsIn(call.body()) + ",\"errors\":[]}"));
    Caller narrowed = new Caller(TENANT, null, Set.of("MANAGER"), Set.of(), Set.of(), null);

    client.batchSetPrices(narrowed, "GBP", items(1));

    for (var call : pricing.calls()) {
      // No header at all is "held to no store"; an empty one would be read the same way, but an
      // absent one is what the gateway sends for it.
      assertThat(call.header("X-Store-Ids"), is((String) null));
      // "-" is a token naming no permission: never the tier's defaults, which are everything.
      assertThat(call.header("X-Permissions"), is("-"));
      assertThat(call.header("X-User-Id"), is((String) null));
    }
  }

  // ── a pricing-svc that stops answering (2 Oct 2026) ──────────────────────────

  private static final String BATCH = "/admin/price-lists/" + LIST_ID + "/items/batch";

  /** Points discovery at a port nobody listens on: what a dead pricing-svc looks like. */
  private void pricingIsGone() {
    JsonStub gone = JsonStub.start();
    URI g = URI.create(gone.baseUrl());
    gone.close();
    consul.on(
        "GET",
        "/v1/health/service/pricing-svc",
        200,
        "[{\"Node\":{\"Address\":\""
            + g.getHost()
            + "\"},\"Service\":{\"Address\":\""
            + g.getHost()
            + "\",\"Port\":"
            + g.getPort()
            + "}}]");
  }

  /** The requests that set prices, as opposed to finding or making the list. */
  private List<JsonStub.Call> batches() {
    return pricing.calls().stream().filter(c -> c.path().equals(BATCH)).toList();
  }

  private List<JsonStub.Call> listsMade() {
    return pricing.calls().stream()
        .filter(c -> c.method().equals("POST") && c.path().equals("/admin/price-lists"))
        .toList();
  }

  @Test
  @DisplayName("Once pricing-svc gives no answer, no further chunk is sent and every row is told")
  void noFurtherChunkIsSentOncePricingGivesNoAnswer() {
    // The second call is dropped without an answer, as a connection reset or a crash would leave
    // it. Sending the third into the same silence would only make the import wait for nothing.
    int[] n = {0};
    pricing.on(
        "POST",
        BATCH,
        call -> {
          if (++n[0] == 2) throw new IllegalStateException("the connection is dropped");
          return JsonStub.Answer.ok("{\"upserted\":" + rowsIn(call.body()) + ",\"errors\":[]}");
        });

    PricingClient.Unreachable e =
        assertThrows(
            PricingClient.Unreachable.class,
            () -> client.batchSetPrices(OWNER, "GBP", items(1201)));

    assertThat("the third chunk was never sent", batches(), hasSize(2));
    assertThat("the first chunk was set", e.result().upserted(), is(500));
    assertThat(
        e.result().errors(),
        is(
            List.of(
                "500 prices may not have been set: pricing-svc gave no answer; check them on the"
                    + " price list before setting them again",
                "201 prices not set: not sent, because pricing-svc had stopped answering")));
  }

  @Test
  @DisplayName("A pricing-svc that cannot be reached sets nothing, and says so in plain words")
  void aPricingThatCannotBeReachedSetsNothing() {
    pricingIsGone();

    PricingClient.Unreachable e =
        assertThrows(
            PricingClient.Unreachable.class,
            () -> client.batchSetPrices(OWNER, "GBP", items(1201)));

    assertThat(e.result().upserted(), is(0));
    // Never the exception's own words ("Connection refused", a class name, a host and port): those
    // are this client's internals, kept for the log.
    assertThat(
        e.result().errors(), is(List.of("1201 prices not set: pricing-svc could not be reached")));
  }

  @Test
  @DisplayName("Price lists that cannot be read never make a second Default list")
  void priceListsThatCannotBeReadMakeNoSecondList() {
    // It answered, but not with its lists: a refusal, a failure, or words this client cannot read.
    // Taking that for "there is no list" made a second Default list beside the business's own.
    record Case(int status, String body, String says) {}
    for (Case c :
        List.of(
            new Case(
                403,
                "{\"error\":{\"code\":\"PERMISSION_DENIED\"}}",
                "3 prices not set: pricing-svc answered HTTP 403 when asked for its price lists"),
            new Case(
                503,
                "{\"error\":{\"code\":\"DOWN\"}}",
                "3 prices not set: pricing-svc answered HTTP 503 when asked for its price lists"),
            new Case(
                200,
                "<html>not json</html>",
                "3 prices not set: pricing-svc's price lists could" + " not be read"),
            new Case(
                200,
                "{\"meta\":{}}",
                "3 prices not set: pricing-svc's price lists could not be" + " read"))) {
      pricing.reset();
      pricing.on("GET", "/price-lists", c.status(), c.body());

      var result = client.batchSetPrices(OWNER, "GBP", items(3));

      assertThat(c.toString(), result.upserted(), is(0));
      assertThat(c.toString(), result.errors(), is(List.of(c.says())));
      assertThat("no list was made: " + c, listsMade(), hasSize(0));
      assertThat("no price was sent: " + c, batches(), hasSize(0));
    }
  }

  @Test
  @DisplayName("Price lists that never come make no list and send no price")
  void priceListsThatNeverComeMakeNoList() {
    pricing.on(
        "GET",
        "/price-lists",
        call -> {
          throw new IllegalStateException("the connection is dropped");
        });

    PricingClient.Unreachable e =
        assertThrows(
            PricingClient.Unreachable.class, () -> client.batchSetPrices(OWNER, "GBP", items(3)));

    assertThat(e.result().errors(), is(List.of("3 prices not set: pricing-svc gave no answer")));
    assertThat(listsMade(), hasSize(0));
    assertThat(batches(), hasSize(0));
  }

  @Test
  @DisplayName("A default list pricing-svc will not make sets no price, and the rows say why")
  void aDefaultListThatIsNotMadeSetsNoPrice() {
    pricing.on("GET", "/price-lists", 200, "{\"data\":[]}");
    pricing.on("POST", "/admin/price-lists", 403, "{\"error\":{\"code\":\"PERMISSION_DENIED\"}}");

    var refused = client.batchSetPrices(OWNER, "GBP", items(2));

    assertThat(refused.upserted(), is(0));
    assertThat(
        refused.errors(),
        is(
            List.of(
                "2 prices not set: pricing-svc answered HTTP 403 when asked to make the default"
                    + " price list")));
    assertThat(batches(), hasSize(0));

    // And one that is asked for and never answered: the list may have been made, so the next
    // import finds it rather than making another.
    pricing.reset();
    pricing.on(
        "POST",
        "/admin/price-lists",
        call -> {
          throw new IllegalStateException("the connection is dropped");
        });
    PricingClient.Unreachable e =
        assertThrows(
            PricingClient.Unreachable.class, () -> client.batchSetPrices(OWNER, "GBP", items(2)));
    assertThat(
        e.result().errors(),
        is(
            List.of(
                "2 prices not set: pricing-svc gave no answer when asked to make the default price"
                    + " list")));
    assertThat(batches(), hasSize(0));
  }

  @Test
  @DisplayName("The breaker is on the call that fails, and prices are never resent blindly")
  void theBreakerSeesTheFailureAndNothingIsResent() throws NoSuchMethodException {
    // The circuit breaker counts what the method throws: a failure swallowed inside it was never
    // counted, so a dead pricing-svc never opened it. A retry would send every chunk again from the
    // first, each one writing its price history and its PriceChanged events a second time, and
    // wait out the read timeout three times over inside the import.
    var method =
        PricingClient.class.getMethod("batchSetPrices", Caller.class, String.class, List.class);
    assertThat(method.isAnnotationPresent(CircuitBreaker.class), is(true));
    assertThat(method.isAnnotationPresent(Retry.class), is(false));
  }

  @Test
  @DisplayName("When the breaker is open, every row is reported as not priced")
  void anOpenBreakerReportsEveryRow() {
    assertThat(PricingClient.notAsked(1201).upserted(), is(0));
    assertThat(
        PricingClient.notAsked(1201).errors(),
        is(
            List.of(
                "1201 prices not set: pricing-svc is not being asked for now, after too many"
                    + " recent failures; set them again later")));
  }
}
