package com.storeql.inventory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Expiry;
import com.storeql.inventory.service.InventoryService;
import com.storeql.test.Envelopes;
import com.storeql.test.PostgresSupport;
import com.storeql.test.WebTargets;
import com.storeql.web.ApiException;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A batch's expiry date is the last day it may be sold. From the day after it still counts on hand
 * and is valued, and is reported as {@code expired}, but is never available, never held and never
 * drawn by a sale, a transfer or a yield run; a write-off and a return to the vendor still take it.
 * A batch with no date is unaffected, and another business's stock is untouched.
 */
@HelidonTest
class ExpiryIT {

  private static final PostgresSupport PG = PostgresSupport.start().wire("inventory");

  private static final String T = Ids.newId().toString();
  private static final String OTHER_T = Ids.newId().toString();
  private static final String USER = Ids.newId().toString();

  @Inject WebTarget target;
  @Inject InventoryService inventory;

  @AfterAll
  static void stopDb() {
    PG.stop();
  }

  private static LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  private Response call(String method, String path, String json, String tenant, String role) {
    var b =
        WebTargets.at(target, path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", USER)
            .header("X-Roles", role)
            .header("Idempotency-Key", Ids.newId().toString());
    return switch (method) {
      case "GET" -> b.get();
      default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
    };
  }

  private String receive(
      String tenant, String store, String variant, int qty, String lot, LocalDate expiry) {
    String json =
        "{\"storeId\":\""
            + store
            + "\",\"variantId\":\""
            + variant
            + "\",\"qty\":"
            + qty
            + ",\"batchNo\":\""
            + lot
            + "\",\"costPrice\":2.00"
            + (expiry == null ? "" : ",\"expiryDate\":\"" + expiry + "\"")
            + "}";
    return Envelopes.created(call("POST", "/admin/inventory/receive", json, tenant, "OWNER"))
        .getString("id");
  }

  private JsonObject level(String tenant, String store, String variant) {
    var levels =
        Envelopes.okArray(
            call("GET", "/admin/inventory/levels?store=" + store, null, tenant, "OWNER"));
    return Envelopes.find(levels, "variantId", variant);
  }

  private static BigDecimal num(JsonObject o, String field) {
    return o.getJsonNumber(field).bigDecimalValue();
  }

  private static BigDecimal remaining(String batchId) {
    return new BigDecimal(
        Envelopes.scalar(
            PG,
            "SELECT remaining_qty FROM inventory.inventory_batches WHERE id = '" + batchId + "'"));
  }

  private void sell(String tenant, String store, String variant, String qty) {
    inventory.deductSaleFromOrderOnce(
        Ids.newId(),
        "it",
        Ids.parse(tenant),
        Ids.parse(store),
        Ids.parse(variant),
        new BigDecimal(qty),
        Ids.newId());
  }

  private static void assertInsufficient(Runnable r) {
    ApiException e = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, r::run);
    assertEquals(422, e.status());
    assertEquals("INSUFFICIENT_STOCK", e.code());
  }

  @Test
  @DisplayName("Sellable on its last day, not the day after; on hand and valued, never available")
  void aBatchIsSellableOnItsLastDayAndNotTheDayAfter() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String expired = receive(T, store, variant, 4, "E", today().minusDays(1));
    String lastDay = receive(T, store, variant, 6, "L", today());
    String future = receive(T, store, variant, 10, "F", today().plusDays(30));
    String undated = receive(T, store, variant, 2, "N", null);

    JsonObject lv = level(T, store, variant);
    assertThat(num(lv, "onHand"), comparesEqualTo(new BigDecimal("22")));
    assertThat(num(lv, "expired"), comparesEqualTo(new BigDecimal("4")));
    assertThat(num(lv, "available"), comparesEqualTo(new BigDecimal("18")));
    assertThat(num(lv, "reserved"), comparesEqualTo(BigDecimal.ZERO));

    // Valued as before: all 22 units at 2.00.
    JsonObject valued =
        Envelopes.find(
            Envelopes.okArray(
                call(
                    "GET", "/admin/inventory/reports/valuation?groupBy=VARIANT", null, T, "OWNER")),
            "groupKey",
            variant);
    assertThat(num(valued, "onHandQty"), comparesEqualTo(new BigDecimal("22")));
    assertThat(num(valued, "value"), comparesEqualTo(new BigDecimal("44")));

    // A hold cannot take what is past its date: 19 wanted, 18 available.
    Response hold =
        call(
            "POST",
            "/inventory/reservations",
            "{\"storeId\":\"" + store + "\",\"variantId\":\"" + variant + "\",\"qty\":19}",
            T,
            "OWNER");
    assertThat(hold.getStatus(), is(422));

    // A sale that needs the expired batch is refused as insufficient stock, and moves nothing.
    assertInsufficient(() -> sell(T, store, variant, "19"));
    assertThat(remaining(expired), comparesEqualTo(new BigDecimal("4")));
    assertThat(remaining(lastDay), comparesEqualTo(new BigDecimal("6")));

    // A sale that unexpired stock covers draws that instead, FEFO among what may be sold: the last
    // day first, then the next; the expired batch is skipped although it is the earliest.
    sell(T, store, variant, "12");
    assertThat(remaining(expired), comparesEqualTo(new BigDecimal("4")));
    assertThat(remaining(lastDay), comparesEqualTo(BigDecimal.ZERO));
    assertThat(remaining(future), comparesEqualTo(new BigDecimal("4")));
    assertThat(remaining(undated), comparesEqualTo(new BigDecimal("2")));
    lv = level(T, store, variant);
    assertThat(num(lv, "onHand"), comparesEqualTo(new BigDecimal("10")));
    assertThat(num(lv, "expired"), comparesEqualTo(new BigDecimal("4")));
    assertThat(num(lv, "available"), comparesEqualTo(new BigDecimal("6")));

    // The day it lapses, the last-day batch behaves as the expired one does: it was sellable today.
    assertThat(Expiry.sellable(today(), today()), is(true));
    assertThat(Expiry.sellable(today().minusDays(1), today()), is(false));
  }

  @Test
  @DisplayName("Expired stock can still be written off and returned to the vendor")
  void expiredStockCanStillBeWrittenOffAndReturnedToTheVendor() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    String expired = receive(T, store, variant, 4, "E", today().minusDays(3));
    String future = receive(T, store, variant, 5, "F", today().plusDays(30));

    // A write-off draws the earliest date first, the expired batch included.
    Response off =
        call(
            "POST",
            "/admin/inventory/adjust",
            "{\"storeId\":\""
                + store
                + "\",\"variantId\":\""
                + variant
                + "\",\"delta\":-3,\"reason\":\"Expired\",\"reasonCode\":\"EXPIRY\"}",
            T,
            "STOREKEEPER");
    assertThat(off.getStatus(), is(200));
    assertThat(remaining(expired), comparesEqualTo(new BigDecimal("1")));
    assertThat(remaining(future), comparesEqualTo(new BigDecimal("5")));

    // A return to the vendor takes it too.
    assertThat(
        inventory.returnToVendorOnce(
            Ids.newId(),
            "it",
            Ids.parse(T),
            Ids.parse(store),
            Ids.parse(variant),
            new BigDecimal("1"),
            Ids.newId()),
        is(true));
    assertThat(remaining(expired), comparesEqualTo(BigDecimal.ZERO));

    // Nothing but expired stock: nothing to sell, hold or move, still on hand until written off.
    String only = Ids.newId().toString();
    receive(T, store, only, 3, "E2", today().minusDays(1));
    JsonObject lv = level(T, store, only);
    assertThat(num(lv, "onHand"), comparesEqualTo(new BigDecimal("3")));
    assertThat(num(lv, "available"), comparesEqualTo(BigDecimal.ZERO));
    assertInsufficient(() -> sell(T, store, only, "1"));
    assertThat(
        call(
                "POST",
                "/inventory/reservations",
                "{\"storeId\":\"" + store + "\",\"variantId\":\"" + only + "\",\"qty\":1}",
                T,
                "OWNER")
            .getStatus(),
        is(422));

    // A transfer of it cannot ship.
    String toStore = Ids.newId().toString();
    JsonObject transfer =
        Envelopes.created(
            call(
                "POST",
                "/admin/inventory/transfers",
                "{\"fromStoreId\":\""
                    + store
                    + "\",\"toStoreId\":\""
                    + toStore
                    + "\",\"transferType\":\"DIRECT\",\"lines\":[{\"variantId\":\""
                    + only
                    + "\",\"requestedQty\":1}]}",
                T,
                "OWNER"));
    Response ship =
        call(
            "POST",
            "/admin/inventory/transfers/" + transfer.getString("id") + "/ship",
            "",
            T,
            "OWNER");
    assertThat(ship.getStatus(), is(422));
    assertThat(
        level(T, store, only).getJsonNumber("onHand").bigDecimalValue(),
        comparesEqualTo(new BigDecimal("3")));

    // The write-off still clears it.
    assertThat(
        call(
                "POST",
                "/admin/inventory/adjust",
                "{\"storeId\":\""
                    + store
                    + "\",\"variantId\":\""
                    + only
                    + "\",\"delta\":-3,\"reason\":\"Expired\"}",
                T,
                "MANAGER")
            .getStatus(),
        is(200));
    assertThat(
        new BigDecimal(
            Envelopes.scalar(
                PG,
                "SELECT COALESCE(SUM(remaining_qty),0) FROM inventory.inventory_batches WHERE"
                    + " variant_id = '"
                    + only
                    + "'")),
        comparesEqualTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("Another business's stock is untouched by ours, expired or not")
  void anotherBusinessesStockIsUntouched() {
    String store = Ids.newId().toString();
    String variant = Ids.newId().toString();
    // The same store and variant ids in two businesses: ours expired, theirs in date.
    receive(T, store, variant, 4, "E", today().minusDays(2));
    String theirs = receive(OTHER_T, store, variant, 7, "F", today().plusDays(30));

    JsonObject ours = level(T, store, variant);
    assertThat(num(ours, "available"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(num(ours, "expired"), comparesEqualTo(new BigDecimal("4")));
    JsonObject their = level(OTHER_T, store, variant);
    assertThat(num(their, "onHand"), comparesEqualTo(new BigDecimal("7")));
    assertThat(num(their, "expired"), comparesEqualTo(BigDecimal.ZERO));
    assertThat(num(their, "available"), comparesEqualTo(new BigDecimal("7")));

    // Their sale draws theirs; ours cannot be sold from under us, and theirs is not ours to write
    // off past what they hold.
    sell(OTHER_T, store, variant, "7");
    assertThat(remaining(theirs), comparesEqualTo(BigDecimal.ZERO));
    assertInsufficient(() -> sell(T, store, variant, "1"));
    assertThat(
        call(
                "POST",
                "/admin/inventory/adjust",
                "{\"storeId\":\""
                    + store
                    + "\",\"variantId\":\""
                    + variant
                    + "\",\"delta\":-5,\"reason\":\"x\"}",
                OTHER_T,
                "MANAGER")
            .getStatus(),
        is(422));
    assertThat(num(level(T, store, variant), "onHand"), comparesEqualTo(new BigDecimal("4")));
  }

  /**
   * The SQL the queries share is valid and reads each store at its own day: at 23:30 UTC on the
   * 30th it is already the 1st in Auckland, so a batch dated the 30th is sellable at a London-less
   * UTC store and expired at the Auckland one.
   */
  @Test
  @DisplayName("The shared condition reads each store at its own day")
  void theSharedConditionReadsEachStoreAtItsOwnDay() {
    String auckland = Ids.newId().toString();
    String elsewhere = Ids.newId().toString();
    String variant = Ids.newId().toString();
    LocalDate day = LocalDate.parse("2026-09-30");
    String inAuckland = receive(T, auckland, variant, 1, "A", day);
    String inUtc = receive(T, elsewhere, variant, 1, "U", day);
    Expiry x =
        Expiry.at(
            Instant.parse("2026-09-30T23:30:00Z"),
            Map.of(Ids.parse(auckland), ZoneId.of("Pacific/Auckland")));
    for (String[] c : new String[][] {{inAuckland, "false"}, {inUtc, "true"}}) {
      assertThat(
          c[0],
          Envelopes.scalar(
              PG,
              "SELECT "
                  + x.sellableSql("b")
                  + " FROM inventory.inventory_batches b WHERE b.id = '"
                  + c[0]
                  + "'"),
          is(c[1]));
    }
    assertThat(
        Envelopes.scalar(
            PG,
            "SELECT "
                + x.expiredSql("b")
                + " FROM inventory.inventory_batches b WHERE b.id = '"
                + inAuckland
                + "'"),
        is("true"));
  }
}
