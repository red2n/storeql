package com.storeql.order.messaging;

import com.storeql.ids.Ids;
import com.storeql.order.service.OrderService;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Handles PaymentCaptured / PaymentFailed events; delegates to {@link OrderService}.
 * PaymentCaptured is idempotent via the {@code order_payment_events} ledger keyed on {@code
 * paymentId} (golden rule #7), so redelivery of the same tender is a no-op. Malformed payloads and
 * 4xx business conflicts are skipped; transient failures propagate so the consumer loop redelivers
 * instead of losing the event.
 */
@ApplicationScoped
class PaymentEventHandler {

  private static final Logger LOG = System.getLogger(PaymentEventHandler.class.getName());

  @Inject OrderService svc;

  void handle(String payload) {
    String eventType;
    UUID orderId;
    UUID tenantId;
    UUID paymentId;
    UUID eventId;
    BigDecimal amount;
    String method;
    String kind;
    try {
      JsonObject obj = Json.createReader(new StringReader(payload)).readObject();
      eventType = stringOrNull(obj, "eventType");
      String orderIdStr = stringOrNull(obj, "orderId");
      String tenantIdStr = stringOrNull(obj, "tenantId");
      String paymentIdStr = stringOrNull(obj, "paymentId");
      String eventIdStr = stringOrNull(obj, "eventId");
      if (orderIdStr == null || tenantIdStr == null) return;
      orderId = Ids.parse(orderIdStr);
      tenantId = Ids.parse(tenantIdStr);
      paymentId = paymentIdStr != null ? Ids.parse(paymentIdStr) : null;
      eventId = eventIdStr != null ? Ids.parse(eventIdStr) : null;
      amount =
          obj.containsKey("amount") && !obj.isNull("amount")
              ? obj.getJsonNumber("amount").bigDecimalValue()
              : null;
      // How the tender was paid (18.5): the German fiscal file lists every payment as cash or
      // not, and the security module signs that split. Absent from events older than this field.
      method = stringOrNull(obj, "method");
      // What kind of refund (substitutions for out-of-stock online lines): ORDER_ADJUSTMENT for a
      // line closed short or substituted; absent from a return's or a cancellation's.
      kind = stringOrNull(obj, "kind");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed payment event skipped: " + e.getMessage());
      return;
    }

    try {
      if ("PaymentCaptured".equals(eventType)) {
        svc.handlePaymentCaptured(tenantId, orderId, paymentId, amount, method);
      } else if ("PaymentFailed".equals(eventType)) {
        svc.handlePaymentFailed(tenantId, orderId);
      } else if ("PaymentRefunded".equals(eventType)) {
        // An adjustment refund — a line closed short or substituted — records the money but moves
        // no status: the order's total was lowered by as much and the goods are still to be handed
        // over (substitutions for out-of-stock online lines).
        svc.applyRefund(eventId, tenantId, orderId, amount, "ORDER_ADJUSTMENT".equals(kind));
      }
    } catch (ApiException e) {
      if (e.status() >= 500) {
        throw e; // transient (DB etc.) — let the consumer loop redeliver
      }
      // 4xx = business conflict (e.g. order already transitioned) — redelivery cannot fix it
      LOG.log(Level.WARNING, "Payment event for order {0} skipped: {1}", orderId, e.getMessage());
    }
  }

  private static String stringOrNull(JsonObject o, String key) {
    return o.containsKey(key) && !o.isNull(key) ? o.getString(key) : null;
  }
}
