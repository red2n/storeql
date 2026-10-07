package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.GiftCard;
import com.storeql.order.domain.Domain.GiftCardTransaction;
import com.storeql.order.domain.Domain.Return;
import com.storeql.order.domain.Domain.SpecialOrder;
import com.storeql.order.domain.OrderGroup;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.Envelopes;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import com.storeql.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The unique-key backstops under every retryable write: when two attempts under one key get past
 * the "have I seen this key" read, the database's unique index is what decides, and the loser must
 * be refused cleanly — a 409 with its own code, nothing of it written — so the service can answer
 * with the winner's work.
 *
 * <p>The services read the key first and replay, so the refusal never reaches a client through
 * HTTP; the loser's branch is therefore provoked where it is thrown, with the winner's row already
 * committed (what a lost race leaves), and the one HTTP-level race there is asserts the outcome a
 * client sees: every caller answered with the one order.
 */
@HelidonTest
class DuplicateKeyRaceIT {

  private static final String T = "01a0a1c5-1111-7000-8000-000000000001";
  private static final String STORE = "01a0a1c5-2222-7000-8000-00000000000a";
  private static final String MANAGER = "01a0a1c5-4444-7000-8000-000000000001";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US");
    PRICING = ReturnsRig.pricing(new AtomicBoolean(false));
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "order");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
    System.setProperty("storeql.order.pricing.enforce", "true");
    System.setProperty("storeql.order.inventory.reserve-enforce", "false");
    System.setProperty("storeql.order.erasure-sweeper.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject OrderService orderService;
  @Inject OrderRepository repo;

  private ReturnsRig rig;

  @AfterAll
  static void stop() {
    System.clearProperty("storeql.order.pricing.enforce");
    PRICING.close();
    PG.stop();
  }

  private ReturnsRig rig() {
    if (rig == null) rig = new ReturnsRig(target, orderService, PG);
    return rig;
  }

  private static ApiException refusal(org.junit.jupiter.api.function.Executable call) {
    return assertThrows(ApiException.class, call);
  }

  // ── the single sale ───────────────────────────────────────────────────────

  @Test
  @DisplayName("Eight checkouts of one basket under one key at once make one order, each answered")
  void eightCheckoutsUnderOneKeyMakeOneOrder() throws Exception {
    String key = Ids.newId().toString();
    String basket =
        "{\"storeId\":\""
            + STORE
            + "\",\"channel\":\"POS\",\"fulfilmentType\":\"INSTORE\",\"items\":[{\"variantId\":\""
            + V_A
            + "\",\"qty\":1}]}";
    int callers = 8;
    CountDownLatch start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(callers);
    Set<String> ids = new HashSet<>();
    List<String> bodies = new ArrayList<>();
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Response>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return rig().post("/orders", basket, T, "MANAGER", MANAGER, key);
                }));
      }
      start.countDown();
      for (Future<Response> f : results) {
        Response r = f.get(60, TimeUnit.SECONDS);
        statuses.add(r.getStatus());
        String body = r.readEntity(String.class);
        bodies.add(body);
        if (r.getStatus() == 201)
          ids.add(Envelopes.parse(body).getJsonObject("data").getString("id"));
      }
    } finally {
      pool.shutdownNow();
    }
    // The loser of the race is never told its key was a duplicate: it is handed the winner's order.
    assertThat(
        statuses.toString(), statuses.stream().filter(s -> s == 201).count(), is((long) callers));
    assertThat(
        bodies.toString(),
        bodies.stream().anyMatch(b -> b.contains("ORDER_DUPLICATE_KEY")),
        is(false));
    assertThat(ids.toString(), ids.size(), is(1));
    assertThat(
        rig().count("orders", "tenant_id='" + T + "' AND idempotency_key='" + key + "'"), is(1L));
  }

  // ── a split checkout ──────────────────────────────────────────────────────

  @Test
  @DisplayName("A split checkout whose key already made a group is refused as a duplicate, once")
  void aGroupUnderAKeyThatAlreadyMadeOneIsRefused() {
    String key = Ids.newId().toString();
    Envelopes.exec(
        PG,
        "INSERT INTO \"order\".order_groups (id, tenant_id, total, currency, idempotency_key)"
            + " VALUES ('"
            + Ids.newId()
            + "', '"
            + T
            + "', 20.00, 'USD', '"
            + key
            + "')");
    var group =
        new OrderGroup(
            Ids.newId(),
            Ids.parse(T),
            null,
            null,
            new BigDecimal("20.00"),
            "USD",
            Instant.now(),
            List.of());

    ApiException e = refusal(() -> repo.createOrderGroup(group, key, List.of()));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("ORDER_DUPLICATE_KEY"));
    assertThat(
        rig().count("order_groups", "tenant_id='" + T + "' AND idempotency_key='" + key + "'"),
        is(1L));
    assertThat(rig().count("order_groups", "id='" + group.id() + "'"), is(0L));
  }

  // ── a return ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A return whose key already made one is refused as a duplicate and writes nothing")
  void aReturnUnderAKeyThatAlreadyMadeOneIsRefused() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    UUID key = Ids.newId();
    data(
        rig()
            .post(
                "/orders/" + order + "/returns",
                "{\"reason\":\"changed their mind\",\"items\":[{\"variantId\":\""
                    + V_A
                    + "\",\"qty\":1,\"condition\":\"SEALED\"}]}",
                T,
                "MANAGER",
                MANAGER,
                key.toString()),
        201);
    var lost =
        new Return(
            Ids.newId(),
            Ids.parse(T),
            Ids.parse(order),
            Ids.parse(STORE),
            "the same return, sent again",
            new BigDecimal("10.00"),
            Return.METHOD_ORIGINAL,
            Return.STATUS_COMPLETED,
            Instant.now(),
            Instant.now(),
            Ids.parse(MANAGER),
            key,
            null,
            List.of(),
            null);

    ApiException e = refusal(() -> repo.createReturn(lost, List.of(), null));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("RETURN_DUPLICATE_KEY"));
    assertThat(rig().count("returns", "order_id='" + order + "'"), is(1L));
    assertThat(rig().count("returns", "id='" + lost.id() + "'"), is(0L));
    assertThat(rig().events(order, "OrderReturned"), is(1L));
  }

  // ── a void ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A void whose key already voided another sale is refused and leaves the sale alone")
  void aVoidUnderAKeyThatAlreadyVoidedIsRefused() {
    String first = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    String second = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    UUID key = Ids.newId();
    Response voided =
        rig()
            .post(
                "/orders/" + first + "/void",
                "{\"reason\":\"rung twice\"}",
                T,
                "MANAGER",
                MANAGER,
                key.toString());
    assertThat(voided.readEntity(String.class), voided.getStatus(), is(200));
    String status = rig().one("SELECT status FROM \"order\".orders WHERE id='" + second + "'");

    // The loser of a race on the key is told so inside its transaction, which then undoes the
    // sale's move to VOIDED as well.
    ApiException e =
        refusal(
            () ->
                repo.voidOrder(
                    Ids.parse(T),
                    Ids.parse(second),
                    Ids.parse(STORE),
                    "rung twice",
                    Ids.parse(MANAGER),
                    key,
                    restock -> null,
                    reversal -> null));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("VOID_DUPLICATE_KEY"));
    assertThat(
        rig().one("SELECT status FROM \"order\".orders WHERE id='" + second + "'"), is(status));
    assertThat(rig().count("pos_void_log", "order_id='" + second + "'"), is(0L));
    assertThat(rig().count("pos_void_log", "idempotency_key='" + key + "'"), is(1L));
    assertThat(rig().events(second, "OrderVoided"), is(0L));
  }

  // ── a special order ───────────────────────────────────────────────────────

  @Test
  @DisplayName("A special order whose key already made one is refused as a duplicate, once")
  void aSpecialOrderUnderAKeyThatAlreadyMadeOneIsRefused() {
    String key = Ids.newId().toString();
    repo.createSpecialOrder(specialOrder(key), List.of());
    SpecialOrder lost = specialOrder(key);

    ApiException e = refusal(() -> repo.createSpecialOrder(lost, List.of()));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("SPECIAL_ORDER_DUPLICATE_KEY"));
    assertThat(
        rig().count("special_orders", "tenant_id='" + T + "' AND idempotency_key='" + key + "'"),
        is(1L));
    assertThat(rig().count("special_order_status_history", "so_id='" + lost.id() + "'"), is(0L));
  }

  private static SpecialOrder specialOrder(String key) {
    return new SpecialOrder(
        Ids.newId(),
        Ids.parse(T),
        Ids.parse(STORE),
        null,
        "Sam Shopper",
        null,
        null,
        null,
        null,
        null,
        SpecialOrder.STATUS_PENDING,
        new BigDecimal("10.00"),
        new BigDecimal("10.00"),
        "USD",
        key,
        Instant.now(),
        Instant.now());
  }

  // ── a gift card ───────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A gift card whose code is already a card of the business is refused, and no card or ledger row is made")
  void aGiftCardWithACodeAlreadyInUseIsRefused() {
    String code = "GC-" + Ids.newId();
    Envelopes.exec(
        PG,
        "INSERT INTO \"order\".gift_cards"
            + " (id, tenant_id, store_id, code, initial_balance, current_balance, status, currency)"
            + " VALUES ('"
            + Ids.newId()
            + "', '"
            + T
            + "', '"
            + STORE
            + "', '"
            + code
            + "', 25.00, 25.00, 'ACTIVE', 'USD')");
    UUID cardId = Ids.newId();
    var card =
        new GiftCard(
            cardId,
            Ids.parse(T),
            Ids.parse(STORE),
            code,
            new BigDecimal("50.00"),
            new BigDecimal("50.00"),
            GiftCard.STATUS_ACTIVE,
            "USD",
            Instant.now(),
            null);
    var tx =
        new GiftCardTransaction(
            Ids.newId(),
            Ids.parse(T),
            cardId,
            GiftCardTransaction.TX_ISSUE,
            new BigDecimal("50.00"),
            BigDecimal.ZERO,
            new BigDecimal("50.00"),
            null,
            null,
            Instant.now());

    ApiException e =
        refusal(
            () ->
                repo.issueGiftCard(
                    card,
                    tx,
                    null,
                    new OrderRepository.HandLoad(
                        Ids.newId(), "GOODWILL", "a clash of codes", Ids.parse(MANAGER))));

    assertThat(e.status(), is(409));
    assertThat(e.code(), is("GIFT_CARD_CODE_EXISTS"));
    assertThat(rig().count("gift_cards", "tenant_id='" + T + "' AND code='" + code + "'"), is(1L));
    assertThat(
        rig().one("SELECT current_balance FROM \"order\".gift_cards WHERE code='" + code + "'"),
        containsString("25.00"));
    assertThat(rig().count("gift_card_transactions", "gift_card_id='" + cardId + "'"), is(0L));
  }
}
