package com.storeql.pricing.messaging;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.TaxTransaction;
import com.storeql.pricing.repo.PricingRepository;
import com.storeql.service.Fx;
import com.storeql.service.TenantProfiles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projects the sales events into output VAT: the figures boxes 1 and 6 of the VAT return are made
 * of (the mirror of {@link SupplierInvoiceEventHandler}, which makes the input side). Until this
 * row nothing wrote them: the table was fed only by a manual endpoint, so a business's return
 * showed no sales.
 *
 * <ul>
 *   <li>{@code OrderConfirmed}: one row per line, the net and VAT it was sold at, positive.
 *   <li>{@code OrderReturned}: one negative row per returned line, for what was refunded for it.
 *   <li>{@code NoReceiptReturnRecorded}: the same, for a return with no sale behind it.
 *   <li>{@code OrderVoided} and {@code OrderCancelled}: the remainder of the order, negative, so a
 *       sale taken back leaves the return the way it left the ledger.
 * </ul>
 *
 * <p>The tax point is the <b>store's own calendar day</b> of the event, written as that date at
 * midnight UTC (as an invoice date is), so a return's period boundaries, which are dates, cut the
 * same place whatever the store's zone. Rows are append-only and keyed by ids derived from the
 * event id, so a redelivered event records nothing twice. A malformed event is logged and skipped,
 * never retried into a loop; a transient failure (the database, the store list) propagates so the
 * consumer loop redelivers.
 */
@ApplicationScoped
public class SalesTaxEventHandler {

  private static final Logger LOG = System.getLogger(SalesTaxEventHandler.class.getName());

  /** A line whose code the event did not carry. */
  static final String UNCODED = "UNCODED";

  /** A return line whose VAT was worked out from the return's total, not carried by the line. */
  static final String DERIVED = "VAT-DERIVED";

  @Inject PricingRepository repo;
  @Inject TenantProfiles profiles;

  /**
   * Handles one event payload.
   *
   * @return how many rows were recorded; 0 when the event is not one of these, was skipped, or was
   *     already recorded
   */
  public int handle(String json) {
    JsonObject o;
    String type;
    try (var reader = Json.createReader(new StringReader(json))) {
      o = reader.readObject();
      type = o.getString("eventType", "");
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed sales event skipped: " + e.getMessage());
      return 0;
    }
    try {
      return switch (type) {
        case "OrderConfirmed" -> sale(o);
        case "OrderReturned" -> returned(o);
        case "NoReceiptReturnRecorded" -> noReceipt(o);
        case "OrderVoided", "OrderCancelled" -> reverse(o);
        default -> 0;
      };
    } catch (IllegalArgumentException | ClassCastException e) {
      LOG.log(Level.WARNING, type + " not projected into output VAT, malformed: " + e.getMessage());
      return 0;
    }
  }

  // ── a sale ───────────────────────────────────────────────────────────────────

  private int sale(JsonObject o) {
    UUID eventId = Ids.parse(text(o, "eventId"));
    UUID tenantId = Ids.parse(text(o, "tenantId"));
    UUID orderId = Ids.parse(text(o, "orderId"));
    UUID storeId = Ids.parse(text(o, "storeId"));
    Instant taxPoint = taxPoint(tenantId, storeId, o);
    List<TaxTransaction> rows = new ArrayList<>();
    JsonArray lines = o.getJsonArray("lines");
    for (int i = 0; lines != null && i < lines.size(); i++) {
      JsonObject l = lines.getJsonObject(i);
      BigDecimal vat = number(l, "vatAmount");
      if (vat == null) {
        LOG.log(
            Level.WARNING, "Order {0} line {1} carries no VAT: not in the VAT return", orderId, i);
        continue;
      }
      BigDecimal gross = number(l, "grossTotal");
      if (gross == null) {
        BigDecimal net = number(l, "lineTotal");
        if (net == null) continue;
        gross = net.add(vat);
      }
      String lineKey = l.getString("lineId", String.valueOf(i));
      rows.add(
          row(
              Ids.derived(eventId, "sale:" + lineKey),
              tenantId,
              orderId,
              l.containsKey("lineId")
                  ? Ids.parse(text(l, "lineId"))
                  : Ids.derived(eventId, "line:" + i),
              Ids.parse(text(l, "variantId")),
              storeId,
              l.getString("vatCode", UNCODED),
              number(l, "vatRate"),
              gross,
              vat,
              taxPoint,
              null));
    }
    return repo.appendTaxTransactions(rows);
  }

  // ── what comes back ──────────────────────────────────────────────────────────

  private int returned(JsonObject o) {
    UUID eventId = Ids.parse(text(o, "eventId"));
    UUID tenantId = Ids.parse(text(o, "tenantId"));
    UUID orderId = Ids.parse(text(o, "orderId"));
    UUID storeId = Ids.parse(text(o, "storeId"));
    Instant taxPoint = taxPoint(tenantId, storeId, o);
    JsonArray items = o.getJsonArray("items");
    if (items == null || items.isEmpty()) return 0;

    // A return line is kept net; the event's total is what the customer got back. Each line's gross
    // is its net plus the VAT inside it: the VAT the line carries (shelf prices), else what the
    // total leaves once the nets are taken out, shared over the lines by their nets.
    BigDecimal[] net = new BigDecimal[items.size()];
    BigDecimal[] vat = new BigDecimal[items.size()];
    boolean everyLineTaxed = true;
    BigDecimal sumNet = BigDecimal.ZERO;
    for (int i = 0; i < items.size(); i++) {
      JsonObject item = items.getJsonObject(i);
      net[i] = number(item, "netAmount");
      if (net[i] == null) {
        LOG.log(
            Level.WARNING,
            "Return {0} line {1} carries no amount: not in the VAT return",
            text(o, "returnId"),
            i);
        return 0;
      }
      vat[i] = number(item, "vatAmount");
      everyLineTaxed &= vat[i] != null;
      sumNet = sumNet.add(net[i]);
    }
    boolean derived = !everyLineTaxed;
    if (derived) {
      BigDecimal total = number(o, "refundAmount");
      if (total == null || sumNet.signum() <= 0 || total.compareTo(sumNet) < 0) {
        LOG.log(
            Level.WARNING,
            "Return {0} cannot be split into net and VAT: not in the VAT return",
            text(o, "returnId"));
        return 0;
      }
      vat = share(total.subtract(sumNet), net, sumNet, Fx.minorUnits(text(o, "currency")));
    }

    List<TaxTransaction> sold = repo.findTaxTransactionsByOrder(tenantId, orderId);
    List<TaxTransaction> rows = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      UUID variantId = Ids.parse(text(items.getJsonObject(i), "variantId"));
      TaxTransaction origin =
          sold.stream()
              .filter(t -> t.variantId().equals(variantId) && t.grossAmount().signum() > 0)
              .findFirst()
              .orElse(null);
      BigDecimal gross = net[i].add(vat[i]);
      if (gross.signum() == 0) continue;
      rows.add(
          row(
              Ids.derived(eventId, "ret:" + i),
              tenantId,
              orderId,
              origin == null ? Ids.derived(eventId, "line:" + i) : origin.orderLineId(),
              variantId,
              storeId,
              origin == null ? UNCODED : origin.vatCode(),
              origin == null ? null : origin.vatRate(),
              gross.negate(),
              vat[i].negate(),
              taxPoint,
              derived ? DERIVED : null));
    }
    return repo.appendTaxTransactions(rows);
  }

  /**
   * {@code amount} shared over lines by their weights to the currency's minor unit; the last takes
   * the rest.
   */
  static BigDecimal[] share(BigDecimal amount, BigDecimal[] weights, BigDecimal sum, int places) {
    BigDecimal[] out = new BigDecimal[weights.length];
    BigDecimal given = BigDecimal.ZERO;
    for (int i = 0; i < weights.length; i++) {
      out[i] =
          i == weights.length - 1
              ? amount.subtract(given)
              : amount.multiply(weights[i]).divide(sum, places, RoundingMode.HALF_UP);
      given = given.add(out[i]);
    }
    return out;
  }

  private int noReceipt(JsonObject o) {
    UUID eventId = Ids.parse(text(o, "eventId"));
    UUID tenantId = Ids.parse(text(o, "tenantId"));
    UUID returnId = Ids.parse(text(o, "returnId"));
    UUID storeId = Ids.parse(text(o, "storeId"));
    Instant taxPoint = taxPoint(tenantId, storeId, o);
    List<TaxTransaction> rows = new ArrayList<>();
    JsonArray items = o.getJsonArray("items");
    for (int i = 0; items != null && i < items.size(); i++) {
      JsonObject item = items.getJsonObject(i);
      BigDecimal unit = number(item, "unitPrice");
      BigDecimal qty = number(item, "qty");
      BigDecimal vat = number(item, "taxAmount");
      if (unit == null || qty == null || vat == null) continue;
      BigDecimal gross = unit.multiply(qty);
      rows.add(
          row(
              Ids.derived(eventId, "nrr:" + i),
              tenantId,
              returnId,
              Ids.derived(eventId, "line:" + i),
              Ids.parse(text(item, "variantId")),
              storeId,
              UNCODED,
              null,
              gross.negate(),
              vat.negate(),
              taxPoint,
              "NO-RECEIPT"));
    }
    return repo.appendTaxTransactions(rows);
  }

  // ── a sale taken back whole ──────────────────────────────────────────────────

  /**
   * What is left of the order, taken back: the sum of its rows by variant and code, if above
   * nothing.
   */
  private int reverse(JsonObject o) {
    UUID eventId = Ids.parse(text(o, "eventId"));
    UUID tenantId = Ids.parse(text(o, "tenantId"));
    UUID orderId = Ids.parse(text(o, "orderId"));
    List<TaxTransaction> rows = repo.findTaxTransactionsByOrder(tenantId, orderId);
    if (rows.isEmpty()) return 0; // never confirmed, or confirmed before this existed
    Map<String, TaxTransaction> left = new LinkedHashMap<>();
    for (TaxTransaction t : rows) {
      left.merge(
          t.variantId() + "|" + t.vatCode(),
          t,
          (a, b) ->
              row(
                  a.id(),
                  a.tenantId(),
                  a.orderId(),
                  a.orderLineId(),
                  a.variantId(),
                  a.storeId(),
                  a.vatCode(),
                  a.vatRate(),
                  a.grossAmount().add(b.grossAmount()),
                  a.vatAmount().add(b.vatAmount()),
                  a.taxPointDate(),
                  null));
    }
    UUID storeId = rows.get(0).storeId();
    Instant taxPoint = taxPoint(tenantId, storeId, o);
    List<TaxTransaction> back = new ArrayList<>();
    for (Map.Entry<String, TaxTransaction> e : left.entrySet()) {
      TaxTransaction t = e.getValue();
      if (t.grossAmount().signum() <= 0 && t.vatAmount().signum() <= 0) continue;
      back.add(
          row(
              Ids.derived(eventId, "rev:" + e.getKey()),
              tenantId,
              orderId,
              t.orderLineId(),
              t.variantId(),
              storeId,
              t.vatCode(),
              t.vatRate(),
              t.grossAmount().negate(),
              t.vatAmount().negate(),
              taxPoint,
              "REVERSAL"));
    }
    return repo.appendTaxTransactions(back);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private static TaxTransaction row(
      UUID id,
      UUID tenantId,
      UUID orderId,
      UUID lineId,
      UUID variantId,
      UUID storeId,
      String code,
      BigDecimal rate,
      BigDecimal gross,
      BigDecimal vat,
      Instant taxPoint,
      String ref) {
    BigDecimal r = rate == null ? BigDecimal.ZERO : rate.setScale(4, RoundingMode.HALF_UP);
    return new TaxTransaction(
        id,
        tenantId,
        orderId,
        lineId,
        variantId,
        storeId,
        code.length() > 8 ? code.substring(0, 8) : code,
        r,
        gross.subtract(vat),
        vat,
        gross,
        false,
        taxPoint,
        ref,
        null);
  }

  /** A required string member; absent is a malformed event, not a null to trip over later. */
  private static String text(JsonObject o, String key) {
    String v = o.getString(key, null);
    if (v == null) throw new IllegalArgumentException("missing " + key);
    return v;
  }

  private static BigDecimal number(JsonObject o, String key) {
    JsonValue v = o.get(key);
    return v instanceof JsonNumber n ? n.bigDecimalValue() : null;
  }

  /** The store's own calendar day of the event, as that date at midnight UTC. */
  private Instant taxPoint(UUID tenantId, UUID storeId, JsonObject o) {
    Instant at;
    try {
      at = Instant.parse(text(o, "occurredAt"));
    } catch (RuntimeException e) {
      at = Instant.now();
    }
    ZoneId zone = profiles.stores(tenantId, storeId).zoneOf(storeId);
    LocalDate day = at.atZone(zone == null ? ZoneOffset.UTC : zone).toLocalDate();
    return day.atStartOfDay(ZoneOffset.UTC).toInstant();
  }
}
