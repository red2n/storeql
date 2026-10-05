package com.storeql.payment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.storeql.ids.Ids;
import com.storeql.payment.ItCalls.Answer;
import com.storeql.payment.ItCalls.Caller;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.TenantSvcStub;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.WebTarget;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A till's tender as the till sends it. The app adds a sale up in binary floating point and posts
 * the double it has, so three items at 1.10 paid with a 5.00 note arrive as {@code
 * 3.3000000000000003}. That is an ordinary cash sale with change and is taken — at the currency's
 * own minor units, rounded half up as order-svc rounds the sale's lines — in a business that keeps
 * pounds, dinars (three places) or yen (none). The row, the answer and the {@code PaymentCaptured}
 * order-svc reads agree; an offline sale replayed under its key is the same one tender; only a
 * tender that comes to nothing at the currency's units is refused, with nothing written.
 */
@HelidonTest
class TillTenderAmountIT {

  private static final PostgresSupport PG;

  private static final UUID GB = Ids.newId();
  private static final UUID KW = Ids.newId();
  private static final UUID JP = Ids.newId();
  private static final UUID GB_STORE = Ids.newId();
  private static final UUID KW_STORE = Ids.newId();
  private static final UUID JP_STORE = Ids.newId();

  static {
    PG = PostgresSupport.start().wire("payment");
    TenantSvcStub.start()
        .with(GB.toString(), "GBP", "GB")
        .withStore(GB.toString(), GB_STORE.toString(), "GB")
        .with(KW.toString(), "KWD", "KW")
        .withStore(KW.toString(), KW_STORE.toString(), "KW")
        .with(JP.toString(), "JPY", "JP")
        .withStore(JP.toString(), JP_STORE.toString(), "JP");
  }

  @Inject WebTarget target;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  /** The body the till posts: its double written as Dart writes it, unquoted. */
  private Answer tender(
      UUID tenant, UUID store, UUID order, String amount, String method, String key) {
    return ItCalls.call(
        target,
        "POST",
        "/payments",
        new Caller(tenant, Ids.newId(), "CASHIER", store),
        "{\"orderId\":\""
            + order
            + "\",\"amount\":"
            + amount
            + ",\"method\":\""
            + method
            + "\",\"storeId\":\""
            + store
            + "\"}",
        key);
  }

  private String stored(UUID tenant, UUID order) {
    return Envelopes.scalar(
        PG,
        "SELECT coalesce(string_agg(amount::text, ','), 'none') FROM payment.payment_tenders"
            + " WHERE tenant_id = '"
            + tenant
            + "' AND order_id = '"
            + order
            + "'");
  }

  private String announced(UUID tenant, UUID order, String amount) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
            + tenant
            + "' AND event_type = 'PaymentCaptured' AND payload LIKE '%\"orderId\":\""
            + order
            + "\",\"amount\":"
            + amount
            + ",%'");
  }

  private String captures(UUID tenant, UUID order) {
    return Envelopes.scalar(
        PG,
        "SELECT count(*) FROM payment.outbox WHERE tenant_id = '"
            + tenant
            + "' AND event_type = 'PaymentCaptured' AND payload LIKE '%\"orderId\":\""
            + order
            + "\"%'");
  }

  private void taken(UUID tenant, UUID store, String sent, String method, String expected) {
    UUID order = Ids.newId();
    Answer a = tender(tenant, store, order, sent, method, Ids.newId().toString());
    assertThat(a.body().toString(), a.status(), is(201));
    assertThat(
        sent,
        a.data().getJsonNumber("amount").bigDecimalValue().compareTo(new BigDecimal(expected)),
        is(0));
    assertThat(
        "the column keeps the currency's units, at its four places",
        new BigDecimal(stored(tenant, order)).compareTo(new BigDecimal(expected)),
        is(0));
    assertThat("order-svc is told the same", announced(tenant, order, expected), is("1"));
  }

  @Test
  @DisplayName("3 x 1.10 paid with a 5.00 note: 3.3000000000000003 is a cash tender of 3.30")
  void aCashSaleWithChangeIsTaken() {
    taken(GB, GB_STORE, "3.3000000000000003", "CASH", "3.30");
    // The back office's "collect outstanding": 25.99 less 10.00 already paid.
    taken(GB, GB_STORE, "15.989999999999998", "CARD", "15.99");
    // A weighed 0.375 kg at 12.99, a line the till never rounds and order-svc totals to 4.87.
    taken(GB, GB_STORE, "4.87125", "CASH", "4.87");
  }

  @Test
  @DisplayName("Dinars keep three places and yen none: the till's figure is taken at each")
  void dinarsAndYen() {
    taken(KW, KW_STORE, "3.3000000000000003", "CASH", "3.300");
    taken(KW, KW_STORE, "1.125", "CARD", "1.125");
    taken(JP, JP_STORE, "487.125", "CASH", "487");
    taken(JP, JP_STORE, "1250.0", "CARD", "1250");
  }

  @Test
  @DisplayName("An offline sale replayed under its key is the one tender it was")
  void anOfflineReplayIsTheSameTender() {
    UUID order = Ids.newId();
    String key = Ids.newId().toString();
    Answer first = tender(GB, GB_STORE, order, "3.3000000000000003", "CASH", key);
    Answer replay = tender(GB, GB_STORE, order, "3.3000000000000003", "CASH", key);
    assertThat(first.body().toString(), first.status(), is(201));
    assertThat(replay.body().toString(), replay.status(), is(201));
    assertThat(replay.data().getString("id"), is(first.data().getString("id")));
    assertThat(new BigDecimal(stored(GB, order)).compareTo(new BigDecimal("3.30")), is(0));
  }

  @Test
  @DisplayName("A tender that comes to nothing at the currency's units is 400, nothing written")
  void aTenderOfNothingIsRefused() {
    for (Object[] c :
        new Object[][] {{GB, GB_STORE, "0.004"}, {KW, KW_STORE, "0.0004"}, {JP, JP_STORE, "0.4"}}) {
      UUID order = Ids.newId();
      Answer a =
          tender((UUID) c[0], (UUID) c[1], order, (String) c[2], "CASH", Ids.newId().toString());
      assertThat(a.body().toString(), a.status(), is(400));
      assertThat(a.code(), is("PAYMENT_AMOUNT_INVALID"));
      assertThat("no tender", stored((UUID) c[0], order), is("none"));
      assertThat("nothing announced", captures((UUID) c[0], order), is("0"));
    }
  }

  @Test
  @DisplayName(
      "1E-80000000, twelve characters any cashier can send, is a fast 400 with nothing written")
  void aHugeExponentIsAFast400() {
    for (Object[] c : new Object[][] {{GB, GB_STORE}, {KW, KW_STORE}, {JP, JP_STORE}}) {
      UUID order = Ids.newId();
      Answer a =
          assertTimeout(
              Duration.ofSeconds(5),
              () ->
                  tender(
                      (UUID) c[0],
                      (UUID) c[1],
                      order,
                      "1E-80000000",
                      "CASH",
                      Ids.newId().toString()));
      assertThat(a.body().toString(), a.status(), is(400));
      assertThat(a.code(), is("VALIDATION_FAILED"));
      assertThat("no tender", stored((UUID) c[0], order), is("none"));
      assertThat("nothing announced", captures((UUID) c[0], order), is("0"));
    }
  }
}
