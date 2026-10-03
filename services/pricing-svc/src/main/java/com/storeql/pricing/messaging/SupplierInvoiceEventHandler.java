package com.storeql.pricing.messaging;

import com.storeql.ids.Ids;
import com.storeql.pricing.domain.Domain.InputTaxTransaction;
import com.storeql.pricing.repo.PricingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Projects {@code SupplierInvoiceCaptured} (purchase-svc) into input VAT (SJ-D39): the figures box
 * 4 and box 7 of the VAT return are made of, keyed by invoice date as the tax point. A {@code
 * SupplierInvoiceRejected} on the same topic (07.7) is projected as the negative of the same
 * figures, so an invoice a manager refused leaves the return the way it left the ledger.
 *
 * <p>Idempotent on the event id — the invoice id for a capture, derived from it for a rejection —
 * so a redelivered event records nothing twice. A malformed event is logged and skipped, never
 * retried into a loop; a transient database failure propagates so the consumer loop redelivers.
 */
@ApplicationScoped
public class SupplierInvoiceEventHandler {

  private static final Logger LOG = System.getLogger(SupplierInvoiceEventHandler.class.getName());

  @Inject PricingRepository repo;

  /**
   * Handles one event payload.
   *
   * @param json the event as published
   * @return true when a row was recorded; false when skipped or already recorded
   */
  public boolean handle(String json) {
    JsonObject obj;
    UUID eventId;
    UUID tenantId;
    UUID invoiceId;
    BigDecimal net;
    BigDecimal vat;
    BigDecimal gross;
    Instant taxPoint;
    boolean rejected;
    try {
      try (var reader = Json.createReader(new StringReader(json))) {
        obj = reader.readObject();
      }
      String type = obj.getString("eventType", "");
      // A rejection carries the same figures as the capture it undoes. It is projected as a
      // second, negative row rather than a delete: the table is append-only, and box 4 is a sum.
      rejected = "SupplierInvoiceRejected".equals(type);
      if (!rejected && !"SupplierInvoiceCaptured".equals(type)) {
        return false;
      }
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      invoiceId = Ids.parse(obj.getString("invoiceId"));
      net = obj.getJsonNumber("netAmount").bigDecimalValue();
      vat = obj.getJsonNumber("vatAmount").bigDecimalValue();
      gross = obj.getJsonNumber("grossAmount").bigDecimalValue();
      taxPoint =
          LocalDate.parse(obj.getString("invoiceDate")).atStartOfDay(ZoneOffset.UTC).toInstant();
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed supplier invoice event skipped: " + e.getMessage());
      return false;
    }
    if (rejected) {
      net = net.negate();
      vat = vat.negate();
      gross = gross.negate();
    }
    // In the invoice currency's own minor units (half up, as the columns kept it before V16); an
    // event that does not name its currency is kept as it came.
    String currency = obj.getString("currency", null);
    if (currency != null && !currency.isBlank()) {
      int units = com.storeql.service.Fx.minorUnits(currency);
      net = net.setScale(units, java.math.RoundingMode.HALF_UP);
      vat = vat.setScale(units, java.math.RoundingMode.HALF_UP);
      gross = gross.setScale(units, java.math.RoundingMode.HALF_UP);
    }
    var row =
        new InputTaxTransaction(
            Ids.newId(),
            tenantId,
            eventId,
            invoiceId,
            optionalUuid(obj, "poId"),
            optionalUuid(obj, "supplierId"),
            obj.getString("invoiceNumber", null),
            obj.getString("currency", null),
            net,
            vat,
            gross,
            taxPoint);
    boolean recorded = repo.recordInputTaxOnce(row);
    LOG.log(
        Level.INFO,
        "{0} {1}: {2}",
        rejected ? "SupplierInvoiceRejected" : "SupplierInvoiceCaptured",
        invoiceId,
        recorded ? "input VAT " + vat + " recorded" : "already recorded");
    return recorded;
  }

  private static UUID optionalUuid(JsonObject obj, String name) {
    String v = obj.getString(name, null);
    if (v == null || v.isBlank()) {
      return null;
    }
    try {
      return Ids.parse(v);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
