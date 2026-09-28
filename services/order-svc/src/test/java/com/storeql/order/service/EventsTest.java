package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.storeql.ids.Ids;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.ReturnItem;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * inventory-svc's OrderEventHandler requires a top-level {@code eventId} on every OrderFulfilled /
 * OrderReturned payload for per-line dedupe — without it the event is silently skipped as malformed
 * and stock is never deducted/restocked. Regression coverage for that bug.
 */
class EventsTest {

  private static final UUID TENANT = Ids.newId();
  private static final UUID ORDER = Ids.newId();
  private static final UUID STORE = Ids.newId();
  private static final UUID RETURN = Ids.newId();
  private static final UUID VARIANT = Ids.newId();

  @Test
  void orderFulfilledPayloadCarriesAParseableEventId() {
    var item =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    var row = Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item));

    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertDoesNotThrow(() -> Ids.parse(json.getString("eventId")));
  }

  @Test
  void orderFulfilledSaysWhatIsStillOutstandingAndWhetherTheOrderIsDone() {
    // inventory-svc's waiting list (wave picking) is set from these absolute figures, so a
    // redelivered or reordered event states the same truth rather than subtracting twice.
    var item =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            new BigDecimal("2"),
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    var row =
        Events.orderFulfilled(
            TENANT,
            ORDER,
            STORE,
            List.of(item),
            java.util.Map.of(),
            2,
            java.util.Map.of(VARIANT, new BigDecimal("1")),
            "PARTIALLY_FULFILLED",
            "ONLINE",
            "PICKUP");
    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals("PARTIALLY_FULFILLED", json.getString("status"));
    assertEquals("ONLINE", json.getString("channel"));
    assertEquals("PICKUP", json.getString("fulfilmentType"));
    JsonObject cancelled =
        Json.createReader(
                new StringReader(
                    Events.orderCancelled(TENANT, ORDER, "changed mind", "ONLINE", "DELIVERY")
                        .payload()))
            .readObject();
    assertEquals("DELIVERY", cancelled.getString("fulfilmentType"));
    assertEquals(
        false,
        Json.createReader(
                new StringReader(Events.orderCancelled(TENANT, ORDER, "expired").payload()))
            .readObject()
            .containsKey("channel"));
    assertEquals(
        0,
        new BigDecimal("1")
            .compareTo(
                json.getJsonArray("items")
                    .getJsonObject(0)
                    .getJsonNumber("outstandingQty")
                    .bigDecimalValue()));
    // The plain shape says neither: a consumer reads them only when they are there.
    JsonObject plain =
        Json.createReader(
                new StringReader(
                    Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item)).payload()))
            .readObject();
    assertEquals(false, plain.containsKey("status"));
    assertEquals(false, plain.getJsonArray("items").getJsonObject(0).containsKey("outstandingQty"));
  }

  @Test
  void orderFulfilledGeneratesADistinctEventIdPerCall() {
    var item =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    var first = Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item));
    var second = Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item));

    String firstId =
        Json.createReader(new StringReader(first.payload())).readObject().getString("eventId");
    String secondId =
        Json.createReader(new StringReader(second.payload())).readObject().getString("eventId");
    assertNotEquals(firstId, secondId);
  }

  @Test
  void orderReturnedPayloadCarriesAParseableEventId() {
    var item =
        new ReturnItem(Ids.newId(), TENANT, RETURN, VARIANT, BigDecimal.ONE, BigDecimal.TEN, null);
    var row =
        Events.orderReturned(
            TENANT, ORDER, RETURN, STORE, List.of(item), BigDecimal.TEN, "ORIGINAL", "GBP");

    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertDoesNotThrow(() -> Ids.parse(json.getString("eventId")));
    assertEquals("OrderReturned", json.getString("eventType"));
    // payment-svc reverses the captured payment from these fields for ORIGINAL-tender returns.
    assertEquals("ORIGINAL", json.getString("refundMethod"));
    assertEquals(0, BigDecimal.TEN.compareTo(json.getJsonNumber("refundAmount").bigDecimalValue()));
  }

  /**
   * Sales by category (19.x) is read line by line in reporting-svc, and OrderConfirmed is the event
   * that says a sale happened: it carries each line's variant, quantity and money.
   */
  @Test
  void orderConfirmedCarriesItsLines() {
    UUID other = Ids.newId();
    var cola =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            new BigDecimal("2"),
            new BigDecimal("2.00"),
            new BigDecimal("4.00"),
            null,
            null);
    var crisps =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            other,
            new BigDecimal("1.500"),
            new BigDecimal("1.00"),
            new BigDecimal("1.50"),
            null,
            null);
    var row =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "POS",
            null,
            new BigDecimal("5.50"),
            new BigDecimal("0.92"),
            "GBP",
            List.of(cola, crisps));

    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals("OrderConfirmed", json.getString("eventType"));
    // When it was confirmed: inventory-svc lists the orders waiting to be picked by this, not by
    // when the event happened to arrive.
    Instant occurredAt = Instant.parse(json.getString("occurredAt"));
    assertFalse(occurredAt.isAfter(Instant.now()));
    assertFalse(occurredAt.isBefore(Instant.now().minusSeconds(60)));
    assertEquals(
        0, new BigDecimal("5.50").compareTo(json.getJsonNumber("total").bigDecimalValue()));
    var lines = json.getJsonArray("lines");
    assertEquals(2, lines.size());
    var first = lines.getJsonObject(0);
    assertEquals(VARIANT.toString(), first.getString("variantId"));
    assertEquals(0, new BigDecimal("2").compareTo(first.getJsonNumber("qty").bigDecimalValue()));
    assertEquals(
        0, new BigDecimal("2.00").compareTo(first.getJsonNumber("unitPrice").bigDecimalValue()));
    assertEquals(
        0, new BigDecimal("4.00").compareTo(first.getJsonNumber("lineTotal").bigDecimalValue()));
    assertEquals(other.toString(), lines.getJsonObject(1).getString("variantId"));
  }

  @Test
  void orderConfirmedCarriesWhereADeliveryGoes() {
    // Dropship (consignment and dropship stock ownership): purchase-svc raises the supplier's order
    // from this event, so it must say how the order is fulfilled and where the goods go.
    var row =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "ONLINE",
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "GBP",
            List.of(),
            "DELIVERY",
            "12 High Street, Leeds, LS1 1AA",
            "Chris Carter",
            "07700900123");
    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals("DELIVERY", json.getString("fulfilmentType"));
    assertEquals("12 High Street, Leeds, LS1 1AA", json.getString("deliveryAddress"));
    assertEquals("Chris Carter", json.getString("deliveryRecipientName"));
    assertEquals("07700900123", json.getString("deliveryRecipientPhone"));

    // A till sale delivers nowhere: the fields are there and null, never missing or empty.
    var till =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "POS",
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "GBP",
            List.of(),
            "INSTORE",
            null,
            null,
            null);
    JsonObject tillJson = Json.createReader(new StringReader(till.payload())).readObject();
    assertEquals("INSTORE", tillJson.getString("fulfilmentType"));
    assertEquals(true, tillJson.isNull("deliveryAddress"));
  }

  @Test
  void orderConfirmedWithNoLinesStillSaysSo() {
    var row =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "ONLINE",
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "GBP",
            List.of());
    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals(0, json.getJsonArray("lines").size());
  }

  // ── ship-from-store and dark-store picking ─────────────────────────────────

  @Test
  void orderFulfilledNamesTheBuyerSoAPickupCanBeToldItIsReady() {
    UUID customer = Ids.newId();
    UUID login = Ids.newId();
    var item =
        new OrderItem(
            Ids.newId(),
            TENANT,
            ORDER,
            VARIANT,
            new BigDecimal("2"),
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null);
    var row =
        Events.orderFulfilled(
            TENANT,
            ORDER,
            STORE,
            List.of(item),
            java.util.Map.of(),
            2,
            java.util.Map.of(VARIANT, BigDecimal.ZERO),
            "FULFILLED",
            "ONLINE",
            "PICKUP",
            customer,
            login);
    JsonObject json = Json.createReader(new StringReader(row.payload())).readObject();
    assertEquals(customer.toString(), json.getString("customerId"));
    assertEquals(login.toString(), json.getString("loginId"));
    assertEquals("FULFILLED", json.getString("status"));
    // A till sale or a guest checkout names nobody, and says so rather than leaving it out.
    var guest = Events.orderFulfilled(TENANT, ORDER, STORE, List.of(item));
    JsonObject g = Json.createReader(new StringReader(guest.payload())).readObject();
    assertEquals(true, g.isNull("customerId"));
    assertEquals(true, g.isNull("loginId"));
  }

  @Test
  void aHandoverNamesTheOrderTheStoreTheBuyerAndHowItLeft() {
    UUID customer = Ids.newId();
    UUID login = Ids.newId();
    var dispatched =
        Events.orderDispatched(TENANT, ORDER, STORE, customer, login, "DPD", "1Z999", 2);
    assertEquals("OrderDispatched", dispatched.eventType());
    assertEquals("storeql.order.order-dispatched", dispatched.topic());
    JsonObject d = Json.createReader(new StringReader(dispatched.payload())).readObject();
    assertDoesNotThrow(() -> Ids.parse(d.getString("eventId")));
    assertDoesNotThrow(() -> Instant.parse(d.getString("occurredAt")));
    assertEquals(ORDER.toString(), d.getString("orderId"));
    assertEquals(STORE.toString(), d.getString("storeId"));
    assertEquals(customer.toString(), d.getString("customerId"));
    assertEquals(login.toString(), d.getString("loginId"));
    assertEquals("DPD", d.getString("carrier"));
    assertEquals("1Z999", d.getString("reference"));
    assertEquals(2, d.getInt("parcels"));

    var collected = Events.orderCollected(TENANT, ORDER, STORE, null, login, "Sam Shopper");
    assertEquals("OrderCollected", collected.eventType());
    assertEquals("storeql.order.order-collected", collected.topic());
    JsonObject c = Json.createReader(new StringReader(collected.payload())).readObject();
    assertEquals(true, c.isNull("customerId"));
    assertEquals("Sam Shopper", c.getString("collectedBy"));
    // Nothing noted about who took it: said as null, not left out or blank.
    JsonObject quiet =
        Json.createReader(
                new StringReader(
                    Events.orderCollected(TENANT, ORDER, STORE, null, null, null).payload()))
            .readObject();
    assertEquals(true, quiet.isNull("collectedBy"));
    assertEquals(true, quiet.isNull("loginId"));
  }

  // ── substitutions for out-of-stock online lines ────────────────────────────

  @Test
  void aLineClosedShortOrSubstitutedNamesTheOrderTheBuyerAndTheMoney() {
    UUID customer = Ids.newId();
    UUID login = Ids.newId();
    var order =
        new com.storeql.order.domain.Domain.Order(
            ORDER,
            TENANT,
            STORE,
            customer,
            login,
            "ONLINE",
            "DELIVERY",
            "PARTIALLY_FULFILLED",
            new BigDecimal("8.00"),
            new BigDecimal("1.60"),
            BigDecimal.ZERO,
            new BigDecimal("9.60"),
            "GBP",
            null,
            null,
            Instant.now(),
            Instant.now(),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            BigDecimal.ZERO,
            null,
            true);
    var closed =
        Events.orderLineShortClosed(
            order, VARIANT, "Apples", new BigDecimal("1"), new BigDecimal("2.40"));
    assertEquals("OrderLineShortClosed", closed.eventType());
    assertEquals("storeql.order.order-line-short-closed", closed.topic());
    JsonObject c = Json.createReader(new StringReader(closed.payload())).readObject();
    assertDoesNotThrow(() -> Ids.parse(c.getString("eventId")));
    assertEquals(customer.toString(), c.getString("customerId"));
    assertEquals(login.toString(), c.getString("loginId"));
    assertEquals(VARIANT.toString(), c.getString("variantId"));
    assertEquals("Apples", c.getString("variantName"));
    assertEquals("2.40", c.getJsonNumber("refundAmount").toString());
    assertEquals("9.60", c.getJsonNumber("orderTotal").toString());
    assertEquals("GBP", c.getString("currency"));
    assertEquals("DELIVERY", c.getString("fulfilmentType"));

    UUID pears = Ids.newId();
    var swapped =
        Events.orderLineSubstituted(
            order,
            VARIANT,
            "Apples",
            pears,
            null,
            new BigDecimal("2"),
            new BigDecimal("4.80"),
            BigDecimal.ZERO);
    assertEquals("storeql.order.order-line-substituted", swapped.topic());
    JsonObject w = Json.createReader(new StringReader(swapped.payload())).readObject();
    assertEquals(pears.toString(), w.getString("toVariantId"));
    assertEquals(true, w.isNull("toName"));
    assertEquals("4.80", w.getJsonNumber("chargedAmount").toString());
    assertEquals("0", w.getJsonNumber("refundAmount").toString());
  }

  // ── delivery and collection slots ─────────────────────────────────────────

  private static final Instant SLOT_STARTS_AT = Instant.parse("2026-09-27T15:00:00Z");
  private static final Instant SLOT_ENDS_AT = Instant.parse("2026-09-27T17:00:00Z");
  private static final String SLOT_TIME_ZONE = "Europe/Warsaw";

  @Test
  void orderPlacedCarriesTheSlotWhenTheOrderHoldsOneAndNotWhenItDoesNot() {
    var withSlot =
        Events.orderPlaced(
            TENANT,
            ORDER,
            "ONLINE",
            null,
            null,
            STORE,
            null,
            SLOT_STARTS_AT,
            SLOT_ENDS_AT,
            SLOT_TIME_ZONE);
    JsonObject json = Json.createReader(new StringReader(withSlot.payload())).readObject();
    assertEquals(SLOT_STARTS_AT.toString(), json.getString("slotStartsAt"));
    assertEquals(SLOT_ENDS_AT.toString(), json.getString("slotEndsAt"));
    assertEquals(SLOT_TIME_ZONE, json.getString("slotTimeZone"));

    // A split checkout's part still names its group alongside the window.
    UUID group = Ids.newId();
    var withGroupAndSlot =
        Events.orderPlaced(
            TENANT,
            ORDER,
            "ONLINE",
            null,
            null,
            STORE,
            group,
            SLOT_STARTS_AT,
            SLOT_ENDS_AT,
            SLOT_TIME_ZONE);
    JsonObject withGroup =
        Json.createReader(new StringReader(withGroupAndSlot.payload())).readObject();
    assertEquals(group.toString(), withGroup.getString("groupId"));
    assertEquals(SLOT_STARTS_AT.toString(), withGroup.getString("slotStartsAt"));

    // No window: the fields are absent, not present as JSON null — a till sale or a store with
    // no windows looks exactly as it did before this feature existed.
    var noSlot = Events.orderPlaced(TENANT, ORDER, "POS", null, null, STORE);
    JsonObject plain = Json.createReader(new StringReader(noSlot.payload())).readObject();
    assertEquals(false, plain.containsKey("slotStartsAt"));
    assertEquals(false, plain.containsKey("slotEndsAt"));
    assertEquals(false, plain.containsKey("slotTimeZone"));
  }

  @Test
  void orderConfirmedCarriesTheSlotWhenTheOrderHoldsOneAndNotWhenItDoesNot() {
    var withSlot =
        Events.orderConfirmed(
            TENANT,
            ORDER,
            STORE,
            "ONLINE",
            null,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "GBP",
            List.of(),
            "DELIVERY",
            "12 High Street, Leeds, LS1 1AA",
            "Chris Carter",
            "07700900123",
            SLOT_STARTS_AT,
            SLOT_ENDS_AT,
            SLOT_TIME_ZONE);
    JsonObject json = Json.createReader(new StringReader(withSlot.payload())).readObject();
    assertEquals(SLOT_STARTS_AT.toString(), json.getString("slotStartsAt"));
    assertEquals(SLOT_ENDS_AT.toString(), json.getString("slotEndsAt"));
    assertEquals(SLOT_TIME_ZONE, json.getString("slotTimeZone"));

    var noSlot =
        Events.orderConfirmed(
            TENANT, ORDER, STORE, "POS", null, BigDecimal.TEN, BigDecimal.ZERO, "GBP", List.of());
    JsonObject plain = Json.createReader(new StringReader(noSlot.payload())).readObject();
    assertEquals(false, plain.containsKey("slotStartsAt"));
    assertEquals(false, plain.containsKey("slotEndsAt"));
    assertEquals(false, plain.containsKey("slotTimeZone"));
  }
}
