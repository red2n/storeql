package com.storeql.inventory.messaging;

import com.storeql.ids.Ids;
import com.storeql.inventory.domain.Domain.Reservation;
import com.storeql.inventory.service.InventoryService;
import com.storeql.inventory.service.WaveService;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Gap #50 — POS→SIM direction. Handles OrderFulfilled, OrderReturned and OrderCancelled events from
 * order-svc.
 *
 * <ul>
 *   <li>OrderFulfilled → consume the checkout stock hold for each line that has one (reservation
 *       placed by order-svc at ONLINE checkout); FIFO-deduct directly for lines without a hold (POS
 *       orders, or online orders whose hold expired). (SALE movement either way.)
 *   <li>OrderReturned → receive stock back for each returned line item (RETURN movement).
 *   <li>OrderVoided → receive back what a voided till sale took, net of anything already returned
 *       (RECEIVE movement, reference type VOID). Empty when the sale was never handed over.
 *   <li>OrderCancelled → release every HELD reservation for the order so the stock returns to
 *       availability.
 * </ul>
 *
 * <p>Each fulfil/return line is deduped on a deterministic per-line id INSIDE the line's
 * transaction, so a redelivered event skips lines that already committed and retries only the rest.
 * A 4xx business rejection (e.g. insufficient stock) skips just that line, as before; transient
 * failures propagate so the consumer loop redelivers the event. Cancellation release is naturally
 * idempotent (releasing a non-HELD reservation is a no-op).
 *
 * <p>Expected fulfil/return/void payload shape: {@code {eventId, eventType, tenantId, orderId,
 * storeId, items: [{variantId, qty, netAmount?}]}}, netAmount on fulfilled lines only (19.7).
 * Cancelled payload: {@code {eventType, tenantId, orderId, reason}}.
 */
@ApplicationScoped
class OrderEventHandler {

  private static final Logger LOG = System.getLogger(OrderEventHandler.class.getName());
  static final String CONSUMER_NAME = "inventory-svc/order-sync";

  @Inject InventoryService service;
  @Inject WaveService waves;

  void handle(String json) {
    JsonObject obj;
    String eventType;
    UUID tenantId;
    UUID orderId;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      eventType = obj.getString("eventType", "");
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed order event skipped: " + e.getMessage());
      return;
    }

    if ("OrderConfirmed".equals(eventType)) {
      awaitConfirmed(obj, tenantId, orderId);
      return;
    }
    if ("OrderCancelled".equals(eventType)) {
      releaseHolds(tenantId, orderId);
      waves.forget(tenantId, orderId, waits(obj));
      return;
    }
    if ("OrderLineShortClosed".equals(eventType) || "OrderLineSubstituted".equals(eventType)) {
      lineClosed(obj, eventType, tenantId, orderId);
      return;
    }

    boolean fulfil = "OrderFulfilled".equals(eventType);
    boolean returned = "OrderReturned".equals(eventType);
    // SJ-D40: a till sale now deducts stock when it is paid for, so voiding one must put it back.
    // A void that was never handed over carries no lines, and an OrderVoided from before this
    // change has no eventId or storeId and is skipped below as malformed — correctly, because the
    // till sales voided then had never deducted anything.
    boolean voided = "OrderVoided".equals(eventType);
    if (!fulfil && !returned && !voided) {
      return;
    }

    UUID eventId;
    UUID storeId;
    JsonArray items;
    String orderStatus;
    try {
      eventId = Ids.parse(obj.getString("eventId"));
      storeId = Ids.parse(obj.getString("storeId"));
      items = obj.getJsonArray("items");
      orderStatus = obj.getString("status", null);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed order event skipped: " + e.getMessage());
      return;
    }
    if (items == null || items.isEmpty()) {
      return;
    }

    // The checkout holds for this order, if any. Consumed holds vanish from this list, so on a
    // redelivered event an already-consumed line falls to the deduct path — which the per-line
    // dedupe mark then skips.
    List<Reservation> holds = fulfil ? heldReservationsQuietly(tenantId, orderId) : List.of();
    boolean complete = "FULFILLED".equals(orderStatus);
    boolean partial = "PARTIALLY_FULFILLED".equals(orderStatus);

    for (int i = 0; i < items.size(); i++) {
      JsonObject line = items.getJsonObject(i);
      UUID variantId = Ids.parse(line.getString("variantId"));
      BigDecimal qty = new BigDecimal(line.get("qty").toString());
      BigDecimal netAmount = netAmount(line);
      UUID dedupeId = lineDedupeId(eventId, i);
      BigDecimal outstanding = outstanding(line);
      try {
        if (fulfil) {
          // The waiting list first, from what order-svc says is still outstanding: a wave
          // completing between this and the deduction below then draws no more than that.
          if (outstanding != null)
            waves.outstandingKnown(tenantId, orderId, variantId, outstanding);
          // What a wave already picked left the shelf when the wave completed: only the rest of
          // the line leaves now, and the line's revenue is recorded with the pick, once.
          BigDecimal picked =
              waves.pickedByWave(
                  dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId, netAmount);
          BigDecimal rest = qty.subtract(picked).max(BigDecimal.ZERO);
          boolean deducted = false;
          if (rest.signum() > 0) {
            BigDecimal net = picked.signum() > 0 ? null : netAmount;
            Reservation hold = takeMatchingHold(holds, variantId, rest);
            deducted =
                hold != null
                    ? service.consumeOnce(dedupeId, CONSUMER_NAME, tenantId, hold.id(), net)
                    : service.deductSaleFromOrderOnce(
                        dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, rest, orderId, net);
          }
          if (picked.signum() > 0 && !complete) {
            // A hold left on a line a wave picked short still covers what waits for the next
            // wave: not a leftover to release below, unless the order is handed over in full.
            holds.removeIf(r -> r.variantId().equals(variantId));
          }
          // Without an outstanding figure (an older event), the waiting line is reduced by what
          // this fulfilment actually deducted — never by a redelivery.
          if (outstanding == null && deducted) {
            waves.fulfilledByHand(tenantId, orderId, variantId, rest);
          }
        } else if (voided) {
          service.receiveVoidFromOrderOnce(
              dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId);
        } else {
          service.receiveReturnFromOrderOnce(
              dedupeId, CONSUMER_NAME, tenantId, storeId, variantId, qty, orderId);
        }
      } catch (ApiException e) {
        if (e.status() >= 500) {
          throw e; // transient — let the consumer loop redeliver; completed lines are deduped
        }
        // business rejection (e.g. insufficient stock) — skip this line, as before
        LOG.log(
            Level.WARNING,
            "{0} line variant {1} skipped: {2}",
            eventType,
            variantId,
            e.getMessage());
      }
    }
    // Defensive: holds that matched no fulfilled line (order edited, qty drift) must not stay
    // HELD forever — release them so the stock returns to availability. Not on a part handover:
    // the lines it did not name still wait, with their holds, for the next one.
    if (!partial) {
      for (Reservation leftover : holds) {
        releaseQuietly(tenantId, leftover.id());
      }
    }
    // Handed over in full: the order waits no more, and a confirmation arriving late changes
    // nothing.
    if (fulfil && complete) waves.forget(tenantId, orderId, waits(obj));
    LOG.log(Level.INFO, "{0} {1}: processed {2} line(s)", eventType, orderId, items.size());
  }

  /**
   * A line the store could not fill (substitutions for out-of-stock online lines): the quantity
   * closed short — or replaced, the substitute's own draw riding the {@code OrderFulfilled} beside
   * this — leaves the order's hold on the variant and its waiting line, once per event. A failure
   * to write propagates so the loop redelivers.
   */
  private void lineClosed(JsonObject obj, String eventType, UUID tenantId, UUID orderId) {
    UUID eventId;
    UUID variantId;
    BigDecimal qty;
    try {
      eventId = Ids.parse(obj.getString("eventId"));
      variantId =
          Ids.parse(
              obj.getString(
                  "OrderLineSubstituted".equals(eventType) ? "fromVariantId" : "variantId"));
      qty = new BigDecimal(obj.get("qty").toString());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, eventType + " " + orderId + " malformed, skipped: " + e.getMessage());
      return;
    }
    if (qty.signum() <= 0) {
      return;
    }
    if (service.lineClosedOnce(eventId, CONSUMER_NAME, tenantId, orderId, variantId, qty)) {
      LOG.log(
          Level.INFO,
          "{0} {1}: {2} of {3} given back",
          eventType,
          orderId,
          qty.stripTrailingZeros().toPlainString(),
          variantId);
    }
  }

  /** Whether the event's order is one that waits to be picked (online, pickup or delivery). */
  private static boolean waits(JsonObject obj) {
    String channel = obj.getString("channel", null);
    String fulfilment =
        obj.containsKey("fulfilmentType") && !obj.isNull("fulfilmentType")
            ? obj.getString("fulfilmentType", null)
            : null;
    return com.storeql.inventory.service.WaveService.waits(channel, fulfilment);
  }

  /** What the line still has outstanding after this handover, when the event says. */
  private static BigDecimal outstanding(JsonObject line) {
    if (!line.containsKey("outstandingQty") || line.isNull("outstandingQty")) return null;
    try {
      return new BigDecimal(line.get("outstandingQty").toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * A confirmed online order for pickup or delivery waits at its store to be picked; anything else
   * (a till sale, a malformed confirmation) is not this handler's to keep.
   */
  private void awaitConfirmed(JsonObject obj, UUID tenantId, UUID orderId) {
    UUID eventId;
    UUID storeId;
    String channel;
    String fulfilment;
    Instant confirmedAt;
    java.util.Map<UUID, BigDecimal> wanted = new java.util.LinkedHashMap<>();
    try {
      eventId = Ids.parse(obj.getString("eventId"));
      storeId = Ids.parse(obj.getString("storeId"));
      channel = obj.getString("channel", "");
      fulfilment =
          obj.containsKey("fulfilmentType") && !obj.isNull("fulfilmentType")
              ? obj.getString("fulfilmentType")
              : null;
      JsonArray lines = obj.containsKey("lines") ? obj.getJsonArray("lines") : null;
      // When order-svc confirmed it; the order waits from then, not from when this arrived.
      confirmedAt =
          obj.containsKey("occurredAt") && !obj.isNull("occurredAt")
              ? Instant.parse(obj.getString("occurredAt"))
              : Instant.now();
      if (lines != null) {
        for (int i = 0; i < lines.size(); i++) {
          JsonObject line = lines.getJsonObject(i);
          wanted.merge(
              Ids.parse(line.getString("variantId")),
              new BigDecimal(line.get("qty").toString()),
              BigDecimal::add);
        }
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "OrderConfirmed " + orderId + " malformed, not projected: " + e);
      return;
    }
    // A failure to write is not swallowed here: it propagates, the loop redelivers, and the
    // projection is idempotent on the event id.
    if (waves.awaitConfirmedOnce(
        eventId, tenantId, orderId, storeId, channel, fulfilment, confirmedAt, wanted)) {
      LOG.log(Level.INFO, "OrderConfirmed {0}: waiting to be picked at {1}", orderId, storeId);
    }
  }

  /** Removes and returns the first HELD reservation matching this line, or null if none. */
  private static Reservation takeMatchingHold(
      List<Reservation> holds, UUID variantId, BigDecimal qty) {
    for (int i = 0; i < holds.size(); i++) {
      Reservation r = holds.get(i);
      if (r.variantId().equals(variantId) && r.qty().compareTo(qty) == 0) {
        return holds.remove(i);
      }
    }
    return null;
  }

  private List<Reservation> heldReservationsQuietly(UUID tenantId, UUID orderId) {
    try {
      return new ArrayList<>(service.heldReservationsByOrder(tenantId, orderId));
    } catch (RuntimeException e) {
      // Fall back to the plain deduct path — the per-line dedupe still protects correctness;
      // any unconsumed hold is reclaimed by the TTL sweeper.
      LOG.log(Level.WARNING, "hold lookup for order {0} failed: {1}", orderId, e.getMessage());
      return new ArrayList<>();
    }
  }

  private void releaseHolds(UUID tenantId, UUID orderId) {
    List<Reservation> holds = heldReservationsQuietly(tenantId, orderId);
    for (Reservation r : holds) {
      releaseQuietly(tenantId, r.id());
    }
    if (!holds.isEmpty()) {
      LOG.log(Level.INFO, "OrderCancelled {0}: released {1} hold(s)", orderId, holds.size());
    }
  }

  private void releaseQuietly(UUID tenantId, UUID reservationId) {
    try {
      service.release(tenantId, reservationId);
    } catch (RuntimeException e) {
      // Already released/consumed or transient — the TTL sweeper is the backstop.
      LOG.log(Level.WARNING, "release of hold {0} failed: {1}", reservationId, e.getMessage());
    }
  }

  /**
   * What the line earned, net of VAT and discounts (19.7), or null when the event carried none or
   * carried something that is not an amount. Revenue is reporting; a bad figure must never stop the
   * stock the line sold from being deducted, so it is dropped here and the sale counts as unpriced.
   */
  static BigDecimal netAmount(JsonObject line) {
    if (!(line.get("netAmount") instanceof jakarta.json.JsonNumber n)) {
      return null;
    }
    BigDecimal amount = n.bigDecimalValue();
    return amount.signum() < 0 ? null : amount;
  }

  /** Deterministic per-line dedupe id: stable across redeliveries of the same event. */
  static UUID lineDedupeId(UUID eventId, int lineIndex) {
    return Ids.derived(eventId, CONSUMER_NAME + ":" + lineIndex);
  }
}
