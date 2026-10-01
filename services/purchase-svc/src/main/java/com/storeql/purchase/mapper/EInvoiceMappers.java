package com.storeql.purchase.mapper;

import com.storeql.purchase.config.Jsons;
import com.storeql.purchase.domain.EInvoiceIntake;
import com.storeql.purchase.domain.SupplierEInvoices.Document;
import com.storeql.purchase.domain.SupplierEInvoices.Line;
import com.storeql.purchase.dto.EInvoiceDtos.DeliveryResponse;
import com.storeql.purchase.dto.EInvoiceDtos.RuleViolationResponse;
import com.storeql.purchase.dto.EInvoiceDtos.SupplierEInvoiceLineResponse;
import com.storeql.purchase.dto.EInvoiceDtos.SupplierEInvoiceResponse;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Received supplier e-invoices to their wire form. */
public final class EInvoiceMappers {

  private EInvoiceMappers() {}

  /** A delivery's receipt: the network learns the id and nothing of the receiver's own. */
  public static DeliveryResponse toDto(Document d, boolean alreadyReceived) {
    return new DeliveryResponse(
        d.id(), d.channel(), d.deliveryRef(), alreadyReceived, d.receivedAt());
  }

  public static SupplierEInvoiceResponse toDto(
      Document d, List<Line> lines, boolean alreadyReceived) {
    return new SupplierEInvoiceResponse(
        d.id(),
        d.receivedAt(),
        d.channel(),
        d.deliveryRef(),
        d.container(),
        d.syntax(),
        d.embeddedFilename(),
        d.typeCode(),
        d.typeCode() != null
            && com.storeql.einvoice.Invoice.CREDIT_NOTE_TYPES.contains(d.typeCode()),
        d.invoiceNumber(),
        d.issueDate(),
        d.currency(),
        d.sellerName(),
        d.sellerVatId(),
        d.sellerEndpoint(),
        d.buyerVatId(),
        d.buyerEndpoint(),
        d.orderReference(),
        d.precedingInvoice(),
        d.netAmount(),
        d.vatAmount(),
        d.grossAmount(),
        d.payableAmount(),
        d.status(),
        EInvoiceIntake.OPEN.contains(d.status()) ? d.problem() : null,
        d.supplierId(),
        d.poId(),
        d.supplierInvoiceId(),
        d.vendorReturnId(),
        d.decidedAt(),
        d.decidedBy(),
        d.decisionReason(),
        alreadyReceived,
        violations(d.violationsJson()),
        lines.stream().map(EInvoiceMappers::toDto).toList());
  }

  static SupplierEInvoiceLineResponse toDto(Line l) {
    return new SupplierEInvoiceLineResponse(
        l.position(),
        l.lineId(),
        l.itemName(),
        l.sellersItemId(),
        l.buyersItemId(),
        l.standardItemId(),
        l.orderLineReference(),
        l.quantity(),
        l.unitCode(),
        l.netAmount(),
        l.netPrice(),
        l.vatCategory(),
        l.vatRate(),
        l.poLineId(),
        l.variantId(),
        l.matchedBy());
  }

  /** The violations as stored on arrival: a JSON array of rule, severity and message. */
  static List<RuleViolationResponse> violations(String json) {
    List<RuleViolationResponse> out = new ArrayList<>();
    if (json == null || json.isBlank()) return out;
    try (JsonReader reader = Jsons.PROVIDER.createReader(new StringReader(json))) {
      for (JsonValue v : reader.readArray()) {
        JsonObject o = v.asJsonObject();
        out.add(
            new RuleViolationResponse(
                o.getString("rule", null),
                o.getString("severity", null),
                o.getString("message", null)));
      }
    }
    return out;
  }
}
