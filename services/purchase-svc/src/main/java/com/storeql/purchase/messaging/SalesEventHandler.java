package com.storeql.purchase.messaging;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.SalesPosting;
import com.storeql.purchase.service.SalesPostingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads the three events that describe a sale and hands them to {@link SalesPostingService} (17.7).
 * A payload that is not what its producer sends is logged and skipped: redelivering it would never
 * make it parse, and the order's clearing stays open on the report where someone will see it.
 */
@ApplicationScoped
public class SalesEventHandler {

  private static final Logger LOG = System.getLogger(SalesEventHandler.class.getName());

  @Inject SalesPostingService postings;
  @Inject com.storeql.purchase.service.DropshipService dropship;
  @Inject com.storeql.purchase.service.DeferredRevenueService deferred;

  /** {@code OrderConfirmed}: the sale, with its total, the VAT inside it and its currency. */
  public void orderConfirmed(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      if (!"OrderConfirmed".equals(o.getString("eventType", ""))) return;
      postings.postSale(
          Ids.parse(o.getString("eventId")),
          Ids.parse(o.getString("tenantId")),
          Ids.parse(o.getString("orderId")),
          EventJson.optUuid(o, "storeId"),
          o.getJsonNumber("total").bigDecimalValue(),
          o.getJsonNumber("taxAmount").bigDecimalValue(),
          o.getString("currency"));
      // Dropship: a line the supplier ships to the customer raises the supplier's order.
      dropship.orderConfirmed(o);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "OrderConfirmed not posted, malformed: " + e.getMessage());
    }
  }

  /** {@code PaymentCaptured}: one tender, keyed by its payment id. */
  public void paymentCaptured(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      if (!"PaymentCaptured".equals(o.getString("eventType", ""))) return;
      UUID paymentId = Ids.parse(o.getString("paymentId"));
      UUID tenantId = Ids.parse(o.getString("tenantId"));
      UUID orderId = Ids.parse(o.getString("orderId"));
      UUID storeId = EventJson.optUuid(o, "storeId");
      String method = o.getString("method", null);
      BigDecimal amount = o.getJsonNumber("amount").bigDecimalValue();
      postings.postTender(paymentId, tenantId, orderId, storeId, method, amount);
      // A gift card spent recognises the breakage that goes with it (17.11).
      if ("GIFT_CARD".equalsIgnoreCase(method)) {
        deferred.giftCardSpent(paymentId, tenantId, orderId, storeId, amount);
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "PaymentCaptured not posted, malformed: " + e.getMessage());
    }
  }

  /**
   * {@code PaymentRefunded}: the refund and each tender's share of it. A refund announced before
   * payment-svc sent shares posts its whole amount to unallocated receipts rather than guessing.
   */
  public void paymentRefunded(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      if (!"PaymentRefunded".equals(o.getString("eventType", ""))) return;
      List<SalesPosting.Allocation> shares = new ArrayList<>();
      UUID store = null;
      if (o.containsKey("tenders")
          && o.get("tenders").getValueType() == JsonValue.ValueType.ARRAY) {
        for (JsonObject t : o.getJsonArray("tenders").getValuesAs(JsonObject.class)) {
          shares.add(
              new SalesPosting.Allocation(
                  t.getString("method", null), t.getJsonNumber("amount").bigDecimalValue()));
          if (store == null) store = EventJson.optUuid(t, "storeId");
        }
      }
      if (shares.isEmpty()) {
        shares.add(new SalesPosting.Allocation(null, o.getJsonNumber("amount").bigDecimalValue()));
      }
      postings.postRefund(
          Ids.parse(o.getString("eventId")),
          Ids.parse(o.getString("tenantId")),
          Ids.parse(o.getString("orderId")),
          store,
          shares);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "PaymentRefunded not posted, malformed: " + e.getMessage());
    }
  }

  /**
   * {@code PaymentDisputeOpened} and {@code PaymentDisputeFundsWithdrawn} (11.9): the acquirer
   * taking a disputed card payment. A dispute opened with the money still in hand posts nothing
   * yet.
   */
  public void disputeFundsTaken(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      String type = o.getString("eventType", "");
      if (!"PaymentDisputeOpened".equals(type) && !"PaymentDisputeFundsWithdrawn".equals(type)) {
        return;
      }
      if (!o.getBoolean("fundsWithdrawn", false)) return;
      postings.postChargebackWithdrawn(
          Ids.parse(o.getString("eventId")),
          Ids.parse(o.getString("tenantId")),
          Ids.parse(o.getString("orderId")),
          EventJson.optUuid(o, "storeId"),
          o.getJsonNumber("amount").bigDecimalValue(),
          o.getJsonNumber("feeAmount").bigDecimalValue());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "dispute not posted, malformed: " + e.getMessage());
    }
  }

  /** {@code PaymentDisputeClosed}: won brings the money back, lost or accepted writes it off. */
  public void disputeClosed(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      if (!"PaymentDisputeClosed".equals(o.getString("eventType", ""))) return;
      postings.postChargebackClosed(
          Ids.parse(o.getString("eventId")),
          Ids.parse(o.getString("tenantId")),
          Ids.parse(o.getString("orderId")),
          EventJson.optUuid(o, "storeId"),
          o.getJsonNumber("amount").bigDecimalValue(),
          "WON".equals(o.getString("outcome", "")),
          o.getBoolean("fundsWithdrawn", false));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "dispute outcome not posted, malformed: " + e.getMessage());
    }
  }

  /**
   * {@code SettlementReconciled} (11.10): a payout agreed with what payment-svc holds. Card
   * clearing empties into the bank, store by store, for what the acquirer paid.
   */
  public void settlementReconciled(String json) {
    try {
      JsonObject o = EventJson.parse(json);
      if (!"SettlementReconciled".equals(o.getString("eventType", ""))) return;
      List<SalesPosting.StoreSettlement> stores = new ArrayList<>();
      for (JsonValue v : o.getJsonArray("stores")) {
        JsonObject s = v.asJsonObject();
        stores.add(
            new SalesPosting.StoreSettlement(
                EventJson.optUuid(s, "storeId"),
                s.getJsonNumber("bank").bigDecimalValue(),
                s.getJsonNumber("fees").bigDecimalValue(),
                s.getJsonNumber("clearing").bigDecimalValue(),
                s.getJsonNumber("unallocated").bigDecimalValue()));
      }
      postings.postCardSettlement(
          Ids.parse(o.getString("eventId")),
          Ids.parse(o.getString("tenantId")),
          Ids.parse(o.getString("batchId")),
          o.getString("reference", "") + " paid " + o.getString("payoutDate", ""),
          stores);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "settlement not posted, malformed: " + e.getMessage());
    }
  }
}
