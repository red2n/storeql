package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.Concurrency;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who may put stored value on a gift card, and that two tills cannot overspend one. Issuing and
 * reloading are staff work at the store the card belongs to: a shopper, a caller with no role, a
 * caller held to another store and another business's staff of every role move nothing.
 */
@HelidonTest
class GiftCardAccessIT {

  private static final String T = "01a0a1c7-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c7-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a1c7-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1c7-2222-7000-8000-00000000000b";
  private static final String MANAGER = "01a0a1c7-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c7-4444-7000-8000-000000000002";
  private static final String SHOPPER = "01a0a1c7-4444-7000-8000-000000000009";

  private static final PostgresSupport PG;
  private static final JsonStub PRICING;

  static {
    PG = PostgresSupport.start();
    TenantSvcStub.start().with(T, "USD", "US").with(OTHER_T, "USD", "US");
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

  private static String issueBody(String store, String amount) {
    return "{\"storeId\":\"" + store + "\",\"amount\":" + amount + ",\"paidBy\":\"CASH\"}";
  }

  private static String reloadBody(String amount) {
    return "{\"amount\":" + amount + ",\"paidBy\":\"CASH\"}";
  }

  private long cards(String tenant) {
    return rig().count("gift_cards", "tenant_id='" + tenant + "'");
  }

  private long ledger(String cardId) {
    return rig().count("gift_card_transactions", "gift_card_id='" + cardId + "'");
  }

  private BigDecimal balanceOf(String cardId) {
    return new BigDecimal(
        rig().one("SELECT current_balance FROM \"order\".gift_cards WHERE id='" + cardId + "'"));
  }

  private JsonObject issued(String store, String amount) {
    return data(
        rig().post("/gift-cards", issueBody(store, amount), T, "MANAGER", MANAGER, null), 201);
  }

  @Test
  @DisplayName("A shopper, or a caller with no role, cannot issue or reload a gift card")
  void aShopperCannotIssueOrReload() {
    JsonObject card = issued(STORE, "20.00");
    long before = cards(T);
    long ledgerBefore = ledger(card.getString("id"));

    for (String roles : new String[] {"CUSTOMER", null}) {
      Response issue = rig().post("/gift-cards", issueBody(STORE, "500"), T, roles, SHOPPER, null);
      assertThat(String.valueOf(roles), issue.getStatus(), is(403));
      Response reload =
          rig()
              .post(
                  "/gift-cards/" + card.getString("code") + "/reload",
                  reloadBody("500"),
                  T,
                  roles,
                  SHOPPER,
                  null);
      assertThat(String.valueOf(roles), reload.getStatus(), is(403));
    }
    assertThat(cards(T), is(before));
    assertThat(ledger(card.getString("id")), is(ledgerBefore));
    assertThat(balanceOf(card.getString("id")).compareTo(new BigDecimal("20.00")), is(0));
    assertThat(rig().events(card.getString("id"), "GiftCardLoaded"), is(1L));
  }

  @Test
  @DisplayName(
      "Staff held to another store cannot issue at, or reload a card issued at, this store")
  void staffOfAnotherStoreAreRefused() {
    JsonObject card = issued(STORE, "20.00");
    String id = card.getString("id");
    long before = cards(T);

    Response issue =
        rig().postHeld("/gift-cards", issueBody(STORE, "50"), T, "CASHIER", CASHIER, OTHER_STORE);
    assertThat(issue.getStatus(), is(403));
    assertThat(issue.readEntity(String.class), containsString("STORE_ACCESS_DENIED"));
    Response reload =
        rig()
            .postHeld(
                "/gift-cards/" + card.getString("code") + "/reload",
                reloadBody("50"),
                T,
                "CASHIER",
                CASHIER,
                OTHER_STORE);
    assertThat(reload.getStatus(), is(403));
    assertThat(reload.readEntity(String.class), containsString("STORE_ACCESS_DENIED"));
    assertThat(cards(T), is(before));
    assertThat(balanceOf(id).compareTo(new BigDecimal("20.00")), is(0));
    assertThat(ledger(id), is(1L));

    // The same cashier at the card's own store, and a caller held to no store, both may.
    Response ok =
        rig()
            .postHeld(
                "/gift-cards/" + card.getString("code") + "/reload",
                reloadBody("5.00"),
                T,
                "CASHIER",
                CASHIER,
                STORE);
    assertThat(ok.readEntity(String.class), ok.getStatus(), is(200));
    Response whole =
        rig()
            .post(
                "/gift-cards/" + card.getString("code") + "/reload",
                reloadBody("5.00"),
                T,
                "MANAGER",
                MANAGER,
                null);
    assertThat(whole.getStatus(), is(200));
    assertThat(balanceOf(id).compareTo(new BigDecimal("30.00")), is(0));
  }

  @Test
  @DisplayName("Another business's staff of every role, naming our card and store, move nothing")
  void otherBusinessMovesNothing() {
    JsonObject card = issued(STORE, "20.00");
    String id = card.getString("id");
    long ours = cards(T);
    long theirs = cards(OTHER_T);
    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      Response reload =
          rig()
              .post(
                  "/gift-cards/" + card.getString("code") + "/reload",
                  reloadBody("50"),
                  OTHER_T,
                  role,
                  Ids.newId().toString(),
                  null);
      assertThat(role, reload.getStatus(), is("CUSTOMER".equals(role) ? 403 : 404));
      // Naming our store from their business makes a card of theirs, never one of ours.
      Response issue =
          rig().postHeld("/gift-cards", issueBody(STORE, "50"), OTHER_T, role, SHOPPER, null);
      assertThat(role, issue.getStatus(), is("CUSTOMER".equals(role) ? 403 : 201));
      Response read =
          rig()
              .getHeld(
                  "/gift-cards/" + card.getString("code"),
                  OTHER_T,
                  role,
                  Ids.newId().toString(),
                  null);
      assertThat(role, read.getStatus() == 404 || read.getStatus() == 403, is(true));
    }
    assertThat(cards(T), is(ours));
    assertThat(balanceOf(id).compareTo(new BigDecimal("20.00")), is(0));
    assertThat(ledger(id), is(1L));
    // Their own issues stand in their own business only.
    assertThat(cards(OTHER_T) > theirs, is(true));
  }

  @Test
  @DisplayName("Two tills redeeming one card at once cannot overspend it")
  void twoTillsCannotOverspendOneCard() throws Exception {
    JsonObject card = issued(STORE, "10.00");
    String id = card.getString("id");
    String code = card.getString("code");
    String orderA = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    String orderB = rig().sale(T, STORE, V_A, 1, null, MANAGER);

    java.util.concurrent.atomic.AtomicInteger next =
        new java.util.concurrent.atomic.AtomicInteger();
    List<Integer> results =
        Concurrency.inParallel(
            2,
            () -> {
              String order = next.getAndIncrement() == 0 ? orderA : orderB;
              return rig()
                  .post(
                      "/gift-cards/" + code + "/redeem",
                      "{\"amount\":8.00,\"orderId\":\"" + order + "\"}",
                      T,
                      "CASHIER",
                      CASHIER,
                      Ids.newId().toString())
                  .getStatus();
            });
    assertThat(results.stream().filter(s -> s == 200).count(), is(1L));
    assertThat(results.stream().filter(s -> s == 409).count(), is(1L));
    // Eight went out, once; two is left; one ledger row of each kind.
    assertThat(balanceOf(id).compareTo(new BigDecimal("2.00")), is(0));
    assertThat(
        rig().count("gift_card_transactions", "gift_card_id='" + id + "' AND tx_type='REDEEM'"),
        is(1L));
    assertThat(rig().events(id, "GiftCardRedeemed"), is(1L));
  }
}
