package com.storeql.notification.messaging;

import com.storeql.ids.Ids;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Values;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends a supplier its remittance advice when a payment run pays it (17.10): which invoices the
 * payment settles, which credit notes it offsets, and the total, so the supplier's accounts clerk
 * can allocate the money without ringing the shop.
 *
 * <p>Idempotent on the event id, which purchase-svc derives from the run and the supplier. A
 * supplier with no remittance email on record is skipped, not failed; a malformed payload is logged
 * and skipped.
 */
@ApplicationScoped
public class SupplierRemittanceHandler {

  private static final Logger LOG = System.getLogger(SupplierRemittanceHandler.class.getName());
  static final String TYPE = "SUPPLIER_REMITTANCE";

  @Inject Notifier notifier;

  /**
   * @param json the {@code SupplierRemittanceIssued} payload
   */
  public void handle(String json) {
    JsonObject obj;
    UUID eventId;
    UUID tenantId;
    UUID supplierId;
    try (var reader = Json.createReader(new StringReader(json))) {
      obj = reader.readObject();
      if (!"SupplierRemittanceIssued".equals(obj.getString("eventType", ""))) return;
      eventId = Ids.parse(obj.getString("eventId"));
      tenantId = Ids.parse(obj.getString("tenantId"));
      supplierId = Ids.parse(obj.getString("supplierId"));
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Malformed SupplierRemittanceIssued skipped: " + e.getMessage());
      return;
    }
    String email = obj.getString("remittanceEmail", null);
    if (email == null || email.isBlank()) {
      LOG.log(Level.DEBUG, "No remittance email for supplier {0}; advice not sent", supplierId);
      return;
    }
    String reference = obj.getString("runReference", "");
    String currency = obj.getString("currency", "");
    List<Values> items = new ArrayList<>();
    if (obj.containsKey("items") && !obj.isNull("items")) {
      for (JsonObject item : obj.getJsonArray("items").getValuesAs(JsonObject.class)) {
        items.add(
            Values.of()
                .text("reference", item.getString("reference", ""))
                .day("document_date", Payloads.day(item, "documentDate"))
                .money("amount", item.getJsonNumber("amount").bigDecimalValue().abs(), currency)
                .flag("credit", "CREDIT_NOTE".equals(item.getString("type", ""))));
      }
    }
    // In the business's own language: nothing says which one a supplier reads.
    notifier.notifyOnce(
        eventId,
        TYPE,
        tenantId,
        supplierId,
        email.trim(),
        new Messages.Message(
            "SUPPLIER_REMITTANCE",
            Catalogue.Form.EMAIL,
            null,
            Values.of()
                .text("reference", reference)
                .day("payment_date", Payloads.day(obj, "paymentDate"))
                .text("supplier", obj.getString("supplierName", ""))
                .items("items", items)
                .money("total", obj.getJsonNumber("total").bigDecimalValue(), currency)));
  }
}
