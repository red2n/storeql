package com.storeql.order.messaging;

import com.storeql.ids.Ids;
import com.storeql.order.client.ProductClient;
import com.storeql.order.client.ProductClient.VariantName;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.RecallNotice.Line;
import com.storeql.order.domain.RecallNotice.Notice;
import com.storeql.order.domain.RecallNotice.Remedy;
import com.storeql.order.domain.RecallNotice.Status;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.repo.RecallNoticeRepository;
import com.storeql.order.service.Events;
import com.storeql.order.service.TillPhone;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns one order a recall reached (inventory-svc's {@code RecallSaleAffected}) into the notice to
 * its buyer (GPSR art.35). The order says who that is: the login that placed it, the shop's
 * customer record, or a phone number a guest left; a till sale to nobody in particular is kept as
 * UNIDENTIFIED, for the count and for the buyer who comes back with the receipt. Idempotent: the
 * event's dedupe mark, the notice and its announcement commit in one transaction. A malformed
 * payload, a withdrawal, or an order this service never saw is skipped with a warning.
 */
@ApplicationScoped
public class RecallSaleAffectedHandler {

  private static final Logger LOG = System.getLogger(RecallSaleAffectedHandler.class.getName());
  static final String CONSUMER_NAME = "order-svc/recall-notices";

  @Inject OrderRepository orders;
  @Inject RecallNoticeRepository notices;
  @Inject ProductClient products;
  @Inject TenantProfiles profiles;

  /**
   * Handles one event.
   *
   * @param json the {@code RecallSaleAffected} payload
   * @return whether a notice was issued now
   */
  public boolean handle(String json) {
    UUID eventId;
    UUID tenantId;
    UUID orderId;
    JsonObject obj;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      if (!"RECALL".equals(obj.getString("kind", ""))) {
        return false; // a withdrawal takes stock off sale and tells nobody
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      orderId = Ids.parse(obj.getString("orderId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RecallSaleAffected payload skipped: " + e.getMessage());
      return false;
    }
    Order order = orders.findOrder(tenantId, orderId).orElse(null);
    if (order == null) {
      LOG.log(
          Level.WARNING,
          "RecallSaleAffected names order {0}, which this service does not hold; skipped",
          orderId);
      return false;
    }
    Notice notice;
    List<Line> lines;
    try {
      notice = notice(obj, order, buyerPhoneOf(order));
      lines = lines(obj, tenantId);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed RecallSaleAffected payload skipped: " + e.getMessage());
      return false;
    }
    boolean issued =
        notices.issueOnce(
            eventId,
            CONSUMER_NAME,
            notice,
            lines,
            notice.status() == Status.ISSUED ? Events.recallNoticeIssued(notice, lines) : null);
    if (issued) {
      LOG.log(Level.DEBUG, "Recall notice {0} issued for order {1}", notice.id(), orderId);
    }
    return issued;
  }

  /**
   * The number a recall notice reaches the buyer at (a phone at the till): the order's own
   * international form; for an order placed before that was kept, its number as typed, read now in
   * the store's own country, then the business's; else the number as typed — never dropped, so
   * notification-svc can still fall back to the customer's own when it cannot text it.
   */
  private String buyerPhoneOf(Order order) {
    if (order.contactPhoneE164() != null) return order.contactPhoneE164();
    String typed = order.contactPhone();
    if (typed == null || typed.isBlank()) return null;
    Map<UUID, String> countries = storeCountries(order);
    String e164 =
        TillPhone.read(
                typed,
                countries.get(order.storeId()),
                homeCountry(order.tenantId()),
                countries.values())
            .e164();
    return e164 != null ? e164 : typed;
  }

  private Map<UUID, String> storeCountries(Order order) {
    try {
      var stores = profiles.stores(order.tenantId(), order.storeId());
      return stores == null ? Map.of() : stores.countries();
    } catch (ApiException e) {
      return Map.of();
    }
  }

  private String homeCountry(UUID tenantId) {
    try {
      return profiles.requireCountry(tenantId);
    } catch (ApiException e) {
      return null;
    }
  }

  private static Notice notice(JsonObject obj, Order order, String buyerPhone) {
    Set<Remedy> remedies =
        Set.copyOf(
            obj.getJsonArray("remedies").getValuesAs(JsonString.class).stream()
                .map(s -> Remedy.valueOf(s.getString()))
                .toList());
    if (remedies.isEmpty()) {
      throw new IllegalArgumentException("a recall notice offers a remedy");
    }
    boolean identified = Notice.identifies(order.customerId(), order.loginId(), buyerPhone);
    return new Notice(
        Ids.newId(),
        order.tenantId(),
        Ids.parse(obj.getString("recallId")),
        obj.getString("reference"),
        obj.getString("hazard"),
        obj.getString("reason"),
        obj.getString("customerNotice"),
        remedies,
        text(obj, "singleRemedyReason"),
        text(obj, "contactPhone"),
        text(obj, "contactUrl"),
        order.id(),
        order.storeId(),
        order.channel(),
        order.customerId(),
        order.loginId(),
        buyerPhone,
        identified,
        Instant.parse(obj.getString("soldAt")),
        identified ? Status.ISSUED : Status.UNIDENTIFIED,
        null,
        null,
        null);
  }

  private List<Line> lines(JsonObject obj, UUID tenantId) {
    var raw = obj.getJsonArray("lines").getValuesAs(JsonObject.class);
    if (raw.isEmpty()) {
      throw new IllegalArgumentException("a recall notice names what was bought");
    }
    List<UUID> variantIds = raw.stream().map(l -> Ids.parse(l.getString("variantId"))).toList();
    // Best-effort: a notice without the product's name still says which lot and when; the buyer's
    // own order has the rest.
    Map<UUID, VariantName> names = products.namesAsSystem(tenantId, variantIds).orElse(Map.of());
    List<Line> out = new ArrayList<>(raw.size());
    for (JsonObject l : raw) {
      UUID variantId = Ids.parse(l.getString("variantId"));
      VariantName name = names.get(variantId);
      String expiry = text(l, "expiryDate");
      out.add(
          new Line(
              Ids.newId(),
              variantId,
              name == null ? null : name.productName(),
              name == null ? null : name.sku(),
              text(l, "batchNo"),
              expiry == null ? null : LocalDate.parse(expiry),
              new BigDecimal(l.get("qty").toString()),
              l.getString("match")));
    }
    return out;
  }

  private static String text(JsonObject obj, String field) {
    return obj.containsKey(field) && !obj.isNull(field) ? obj.getString(field) : null;
  }
}
