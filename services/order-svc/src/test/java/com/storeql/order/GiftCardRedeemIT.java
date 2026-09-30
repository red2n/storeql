package com.storeql.order;

import static com.storeql.order.support.ReturnsRig.V_A;
import static com.storeql.order.support.ReturnsRig.data;
import static com.storeql.test.Envelopes.exec;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.order.support.ReturnsRig;
import com.storeql.test.JsonStub;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The till's gift-card tender, charged first (intent/return-controls.md): one Idempotency-Key, one
 * charge; the card debited, the ledger row written and {@code GiftCardRedeemed} announced on one
 * transaction; and every refusal leaves the card exactly as it was.
 */
@HelidonTest
class GiftCardRedeemIT {

  private static final String T = "01a0a1c5-1111-7000-8000-000000000001";
  private static final String OTHER_T = "01a0a1c5-1111-7000-8000-000000000002";
  private static final String STORE = "01a0a1c5-2222-7000-8000-00000000000a";
  private static final String OTHER_STORE = "01a0a1c5-2222-7000-8000-00000000000b";
  private static final String OTHER_T_STORE = "01a0a1c5-2222-7000-8000-00000000000c";
  private static final String MANAGER = "01a0a1c5-4444-7000-8000-000000000001";
  private static final String CASHIER = "01a0a1c5-4444-7000-8000-000000000002";

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

  /** A card of this business holding {@code amount}: id and code. */
  private String[] card(String tenant, String store, String amount) {
    JsonObject c =
        data(
            rig()
                .post(
                    "/gift-cards",
                    "{\"storeId\":\"" + store + "\",\"amount\":" + amount + ",\"paidBy\":\"CASH\"}",
                    tenant,
                    "MANAGER",
                    MANAGER,
                    null),
            201);
    return new String[] {c.getString("id"), c.getString("code")};
  }

  private Response redeem(
      String code, String amount, String order, String tenant, String role, String key) {
    return rig()
        .post(
            "/gift-cards/" + code + "/redeem",
            "{\"amount\":" + amount + (order == null ? "" : ",\"orderId\":\"" + order + "\"") + "}",
            tenant,
            role,
            CASHIER,
            key);
  }

  private BigDecimal balanceOf(String cardId) {
    return new BigDecimal(
        rig().one("SELECT current_balance FROM \"order\".gift_cards WHERE id='" + cardId + "'"));
  }

  private long redemptionsOf(String cardId) {
    return rig()
        .count("gift_card_transactions", "gift_card_id='" + cardId + "' AND tx_type='REDEEM'");
  }

  /** Nothing was charged: the balance is what it was, with no ledger row and no event. */
  private void assertUntouched(String cardId, String balance) {
    assertThat(balanceOf(cardId).compareTo(new BigDecimal(balance)), is(0));
    assertThat(redemptionsOf(cardId), is(0L));
    assertThat(rig().events(cardId, "GiftCardRedeemed"), is(0L));
  }

  // ── the charge ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A redemption debits the card once, writes its ledger row and announces it")
  void aRedemptionChargesOnceAndIsAnnounced() {
    String[] gc = card(T, STORE, "50.00");
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);
    String key = Ids.newId().toString();

    JsonObject first = data(redeem(gc[1], "15.00", order, T, "CASHIER", key), 200);
    assertThat(first.getString("giftCardId"), is(gc[0]));
    assertThat(first.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("15.00")));
    assertThat(first.getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("35.00")));
    String redemptionId = first.getString("redemptionId");
    assertThat(balanceOf(gc[0]).compareTo(new BigDecimal("35.00")), is(0));

    // The ledger row is the redemption, and it carries the order and the key.
    assertThat(
        rig()
            .one(
                "SELECT order_id::text || '/' || idempotency_key::text FROM \"order\".gift_card_transactions"
                    + " WHERE id='"
                    + redemptionId
                    + "' AND tx_type='REDEEM'"),
        is(order + "/" + key));

    JsonObject ev = rig().event(gc[0], "GiftCardRedeemed");
    assertThat(ev.getString("eventType"), is("GiftCardRedeemed"));
    assertThat(ev.getString("tenantId"), is(T));
    assertThat(ev.getString("redemptionId"), is(redemptionId));
    assertThat(ev.getString("giftCardId"), is(gc[0]));
    assertThat(ev.getString("orderId"), is(order));
    assertThat(ev.getString("storeId"), is(STORE));
    assertThat(ev.getJsonNumber("amount").bigDecimalValue(), is(new BigDecimal("15.00")));
    assertThat(ev.getString("currency"), is("USD"));
    assertThat(ev.containsKey("eventId"), is(true));

    // A retry answers with the first redemption and writes nothing.
    JsonObject retry = data(redeem(gc[1], "15.00", order, T, "CASHIER", key), 200);
    assertThat(retry.getString("redemptionId"), is(redemptionId));
    assertThat(retry.getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("35.00")));
    assertThat(redemptionsOf(gc[0]), is(1L));
    assertThat(rig().events(gc[0], "GiftCardRedeemed"), is(1L));

    // The same card towards the same order under a fresh key is the older guarantee: still once.
    JsonObject fresh =
        data(redeem(gc[1], "15.00", order, T, "CASHIER", Ids.newId().toString()), 200);
    assertThat(fresh.getString("redemptionId"), is(redemptionId));
    assertThat(redemptionsOf(gc[0]), is(1L));
    assertThat(balanceOf(gc[0]).compareTo(new BigDecimal("35.00")), is(0));
    // ...but a different amount for it is a mistake to be told of, not a second charge.
    Response other = redeem(gc[1], "5.00", order, T, "CASHIER", Ids.newId().toString());
    assertThat(other.getStatus(), is(409));
    assertThat(
        other.readEntity(String.class), containsString("GIFT_CARD_ALREADY_REDEEMED_FOR_ORDER"));
    // And the key cannot be lent to another tender.
    Response reused = redeem(gc[1], "9.00", order, T, "CASHIER", key);
    assertThat(reused.getStatus(), is(409));
    assertThat(reused.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REUSED"));
    assertThat(redemptionsOf(gc[0]), is(1L));

    // Another order is another charge.
    String next = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    JsonObject second =
        data(redeem(gc[1], "10.00", next, T, "CASHIER", Ids.newId().toString()), 200);
    assertThat(second.getJsonNumber("balance").bigDecimalValue(), is(new BigDecimal("25.00")));
    assertThat(redemptionsOf(gc[0]), is(2L));
  }

  @Test
  @DisplayName("Spending a card to nothing depletes it")
  void spendingItAllDepletesTheCard() {
    String[] gc = card(T, STORE, "10.00");
    String order = rig().sale(T, STORE, V_A, 1, null, MANAGER);
    data(redeem(gc[1], "10.00", order, T, "CASHIER", Ids.newId().toString()), 200);
    assertThat(
        rig().one("SELECT status FROM \"order\".gift_cards WHERE id='" + gc[0] + "'"),
        is("DEPLETED"));
  }

  // ── the refusals ──────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A card that cannot cover, has expired, holds another currency or is cancelled is refused")
  void aCardThatCannotPayIsRefusedWithNothingDebited() {
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);

    String[] poor = card(T, STORE, "5.00");
    Response insufficient = redeem(poor[1], "6.00", order, T, "CASHIER", Ids.newId().toString());
    assertThat(insufficient.getStatus(), is(409));
    assertThat(
        insufficient.readEntity(String.class), containsString("GIFT_CARD_INSUFFICIENT_BALANCE"));
    assertUntouched(poor[0], "5.00");

    String[] old = card(T, STORE, "30.00");
    exec(
        PG,
        "UPDATE \"order\".gift_cards SET expires_at = now() - interval '1 day' WHERE id='"
            + old[0]
            + "'");
    Response expired = redeem(old[1], "5.00", order, T, "CASHIER", Ids.newId().toString());
    assertThat(expired.getStatus(), is(409));
    assertThat(expired.readEntity(String.class), containsString("GIFT_CARD_EXPIRED"));
    assertUntouched(old[0], "30.00");

    String[] euro = card(T, STORE, "30.00");
    exec(PG, "UPDATE \"order\".gift_cards SET currency='EUR' WHERE id='" + euro[0] + "'");
    Response wrong = redeem(euro[1], "5.00", order, T, "CASHIER", Ids.newId().toString());
    assertThat(wrong.getStatus(), is(409));
    assertThat(wrong.readEntity(String.class), containsString("GIFT_CARD_CURRENCY_MISMATCH"));
    assertUntouched(euro[0], "30.00");

    String[] dead = card(T, STORE, "30.00");
    exec(PG, "UPDATE \"order\".gift_cards SET status='CANCELLED' WHERE id='" + dead[0] + "'");
    Response cancelled = redeem(dead[1], "5.00", order, T, "CASHIER", Ids.newId().toString());
    assertThat(cancelled.getStatus(), is(409));
    assertThat(cancelled.readEntity(String.class), containsString("GIFT_CARD_NOT_ACTIVE"));
    assertUntouched(dead[0], "30.00");
  }

  @Test
  @DisplayName("Bad input, the wrong caller, the wrong store and the wrong business are refused")
  void wrongInputAndWrongCallersAreRefused() {
    String[] gc = card(T, STORE, "30.00");
    String order = rig().sale(T, STORE, V_A, 2, null, MANAGER);

    // No key; no order; an amount that is nothing.
    Response noKey = redeem(gc[1], "5.00", order, T, "CASHIER", null);
    assertThat(noKey.getStatus(), is(400));
    assertThat(noKey.readEntity(String.class), containsString("IDEMPOTENCY_KEY_REQUIRED"));
    assertThat(
        redeem(gc[1], "5.00", null, T, "CASHIER", Ids.newId().toString()).getStatus(), is(400));
    assertThat(
        redeem(gc[1], "0", order, T, "CASHIER", Ids.newId().toString()).getStatus(), is(400));
    // A key that is not a UUIDv7.
    assertThat(redeem(gc[1], "5.00", order, T, "CASHIER", "not-a-key").getStatus(), is(400));
    // A shopper is no member of staff.
    assertThat(
        redeem(gc[1], "5.00", order, T, "CUSTOMER", Ids.newId().toString()).getStatus(), is(403));
    // An order that is not there.
    assertThat(
        redeem(gc[1], "5.00", Ids.newId().toString(), T, "CASHIER", Ids.newId().toString())
            .getStatus(),
        is(404));
    // A caller held to another store cannot charge a card to this store's order.
    Response elsewhere =
        rig()
            .as("/gift-cards/" + gc[1] + "/redeem", T, "CASHIER", CASHIER)
            .header("X-Store-Ids", OTHER_STORE)
            .header("Idempotency-Key", Ids.newId().toString())
            .post(
                Entity.entity(
                    "{\"amount\":5.00,\"orderId\":\"" + order + "\"}", MediaType.APPLICATION_JSON));
    assertThat(elsewhere.getStatus(), is(403));
    assertUntouched(gc[0], "30.00");

    // Another business's staff of every role: neither the card nor the order is theirs.
    String[] theirs = card(OTHER_T, OTHER_T_STORE, "30.00");
    for (String role : new String[] {"OWNER", "MANAGER", "CASHIER", "STOREKEEPER"}) {
      Response ours = redeem(gc[1], "5.00", order, OTHER_T, role, Ids.newId().toString());
      assertThat(role, ours.getStatus(), is(404));
      // Their card against our order is not found either: the order is ours.
      Response mixed = redeem(theirs[1], "5.00", order, OTHER_T, role, Ids.newId().toString());
      assertThat(role, mixed.getStatus(), is(404));
    }
    // Our staff cannot spend their card.
    Response cross = redeem(theirs[1], "5.00", order, T, "OWNER", Ids.newId().toString());
    assertThat(cross.getStatus(), is(404));
    assertThat(cross.readEntity(String.class), containsString("GIFT_CARD_NOT_FOUND"));
    assertUntouched(gc[0], "30.00");
    assertUntouched(theirs[0], "30.00");
  }
}
