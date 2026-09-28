package com.storeql.purchase.service;

import com.storeql.events.EventPayload;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.GoodsReceiptLine;
import com.storeql.purchase.domain.Domain.VendorReturnLine;
import com.storeql.purchase.domain.LandedCost;
import com.storeql.service.OutboxRow;
import java.util.List;
import java.util.UUID;

/** Outbox event factory for purchase-svc. */
final class Events {

  private Events() {}

  static OutboxRow purchaseOrderCreated(UUID tenantId, UUID poId) {
    return new OutboxRow(
        "PurchaseOrderCreated",
        "storeql.purchase.purchase-order-created",
        tenantId,
        poId,
        "{\"poId\":\"" + poId + "\"}");
  }

  /**
   * Cancellation carries the reason, not just the id: a consumer reconciling open commitments needs
   * to distinguish a supplier-side failure from a buyer-side change of mind without calling back.
   * The reason is caller-supplied text, so it goes through {@link EventPayload#esc} — a quote in it
   * must not be able to corrupt the event JSON.
   */
  /**
   * How a variant is fulfilled from now on (dropship): from the supplier per order, or from stock
   * again. inventory-svc keeps the projection it answers availability and holds from.
   */
  static OutboxRow variantSourcingChanged(
      UUID tenantId, UUID variantId, String fulfilment, UUID supplierId) {
    return new OutboxRow(
        "VariantSourcingChanged",
        "storeql.purchase.variant-sourcing-changed",
        tenantId,
        variantId,
        EventPayload.base("VariantSourcingChanged", tenantId, variantId)
            + ",\"variantId\":\""
            + variantId
            + "\",\"fulfilment\":\""
            + fulfilment
            + "\",\"supplierId\":"
            + (supplierId == null ? "null" : "\"" + supplierId + "\"")
            + "}");
  }

  /**
   * The whole of a warehouse order's cross-dock allocations, as they now stand (cross-docking): a
   * snapshot inventory-svc replaces its record with — what each shop is owed of each product when
   * the delivery arrives. Empty when the order is rejected, cancelled or closed short. Keyed by the
   * order, so the snapshots of one order arrive in the order they were made.
   */
  public static OutboxRow crossDockAllocationsSet(
      UUID tenantId, UUID poId, UUID warehouseId, List<Domain.LineAllocation> allocations) {
    StringBuilder sb =
        new StringBuilder(EventPayload.base("CrossDockAllocationsSet", tenantId, poId))
            .append(",\"poId\":\"")
            .append(poId)
            .append("\",\"warehouseId\":\"")
            .append(warehouseId)
            .append("\",\"allocations\":[");
    for (int i = 0; i < allocations.size(); i++) {
      Domain.LineAllocation a = allocations.get(i);
      if (i > 0) sb.append(',');
      sb.append("{\"variantId\":\"")
          .append(a.variantId())
          .append("\",\"storeId\":\"")
          .append(a.storeId())
          .append("\",\"qty\":")
          .append(a.qty().stripTrailingZeros().toPlainString())
          .append('}');
    }
    sb.append("]}");
    return new OutboxRow(
        "CrossDockAllocationsSet",
        "storeql.purchase.crossdock-allocations-set",
        tenantId,
        poId,
        sb.toString());
  }

  static OutboxRow purchaseOrderCancelled(UUID tenantId, UUID poId, String reason) {
    return new OutboxRow(
        "PurchaseOrderCancelled",
        "storeql.purchase.purchase-order-cancelled",
        tenantId,
        poId,
        "{\"poId\":\"" + poId + "\",\"reason\":\"" + EventPayload.esc(reason) + "\"}");
  }

  /**
   * The stock movements inventory-svc writes from this event are labelled {@code ref_type='GRN'},
   * so {@code refId} has to be the goods receipt (SJ-D21). It carried the <em>purchase order</em>
   * id instead.
   *
   * <p>That was survivable while a purchase order could only ever have one receipt — the two ids
   * were in one-to-one correspondence, so citing the order still identified the delivery. Partial
   * receipt ends that: a purchase order now has many receipts, and every movement from every
   * delivery cited the same id under a label claiming to name a specific one. A goods-in
   * discrepancy could not be traced from the stock movement back to the delivery note, which is the
   * entire purpose of that reference.
   *
   * <p>The order is still one hop away — {@code goods_receipts.po_id} — and is carried here as
   * {@code poId} for consumers that want it without another lookup. <b>Movements written before
   * this fix still hold the purchase order id</b>; they cannot be corrected from here without
   * reaching into another service's schema (golden rule #1), and there was exactly one receipt per
   * order back then, so nothing was lost that a join cannot recover.
   */
  /** A receipt of the business's own, duty-paid goods, the supplier unnamed. */
  static OutboxRow goodsReceived(
      UUID tenantId,
      UUID grId,
      UUID storeId,
      UUID poId,
      List<GoodsReceiptLine> lines,
      java.util.Map<UUID, java.math.BigDecimal> unitPrice) {
    return goodsReceived(
        tenantId, grId, storeId, poId, lines, unitPrice, Domain.PO_OWNERSHIP_OWNED, null);
  }

  /** A receipt of duty-paid goods of the given ownership. */
  static OutboxRow goodsReceived(
      UUID tenantId,
      UUID grId,
      UUID storeId,
      UUID poId,
      List<GoodsReceiptLine> lines,
      java.util.Map<UUID, java.math.BigDecimal> unitPrice,
      String ownership,
      UUID supplierId) {
    return goodsReceived(
        tenantId,
        grId,
        storeId,
        poId,
        lines,
        unitPrice,
        ownership,
        supplierId,
        Domain.PO_DUTY_PAID);
  }

  static OutboxRow goodsReceived(
      UUID tenantId,
      UUID grId,
      UUID storeId,
      UUID poId,
      List<GoodsReceiptLine> lines,
      java.util.Map<UUID, java.math.BigDecimal> unitPrice,
      String ownership,
      UUID supplierId,
      String dutyStatus) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(grId)
        .append("\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"refId\":\"")
        .append(grId)
        .append("\",\"poId\":\"")
        .append(poId)
        // Whose the stock is (consignment stock ownership): OWNED, or the supplier's until sold.
        .append("\",\"ownership\":\"")
        .append(ownership == null ? Domain.PO_OWNERSHIP_OWNED : ownership)
        .append("\",\"supplierId\":\"")
        .append(supplierId)
        // Bonded stock: a delivery under bond arrives with its duty suspended.
        .append("\",\"dutyStatus\":\"")
        .append(dutyStatus == null ? Domain.PO_DUTY_PAID : dutyStatus)
        .append("\",\"lines\":[");
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) sb.append(",");
      GoodsReceiptLine l = lines.get(i);
      sb.append("{\"variantId\":\"")
          .append(l.variantId())
          .append("\",\"qty\":")
          .append(l.qtyReceived());
      // The order's price is what the batch cost (07.x): a receipt without one is still recorded,
      // but its stock is reported unvalued rather than valued at zero.
      java.math.BigDecimal price = unitPrice.get(l.variantId());
      if (price != null) {
        sb.append(",\"costPrice\":").append(price.toPlainString());
      }
      sb.append("}");
    }
    sb.append("]}");
    return new OutboxRow(
        "GoodsReceived", "storeql.purchase.goods-received", tenantId, grId, sb.toString());
  }

  static final String LANDED_COST_APPLIED = "LandedCostApplied";
  static final String LANDED_COST_REVERSED = "LandedCostReversed";

  /**
   * A charge landed on a receipt, or was taken off it again (07.x): what inventory-svc lifts the
   * cost of the receipt's batches by, per unit, or lowers it by on a reversal. Both kinds share one
   * topic and one key — the charge — so a reversal never overtakes what it reverses. The event id
   * is the charge id on application and a derived id on reversal; a consumer dedupes on it.
   */
  static OutboxRow landedCost(
      String eventType, LandedCost.Charge c, List<LandedCost.Line> lines, UUID eventId) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(eventId)
        .append("\",\"eventType\":\"")
        .append(eventType)
        .append("\",\"tenantId\":\"")
        .append(c.tenantId())
        .append("\",\"storeId\":\"")
        .append(c.storeId())
        .append("\",\"refId\":\"")
        .append(c.grId())
        .append("\",\"landedCostId\":\"")
        .append(c.id())
        .append("\",\"poId\":\"")
        .append(c.poId())
        .append("\",\"chargeType\":\"")
        .append(c.chargeType())
        .append("\",\"currency\":\"")
        .append(c.currency())
        .append("\",\"amount\":")
        .append(c.amount().toPlainString())
        .append(",\"lines\":[");
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) sb.append(",");
      LandedCost.Line l = lines.get(i);
      sb.append("{\"variantId\":\"")
          .append(l.variantId())
          .append("\",\"qty\":")
          .append(l.qty().toPlainString())
          .append(",\"amount\":")
          .append(l.amount().toPlainString())
          .append(",\"perUnit\":")
          .append(l.perUnit().toPlainString())
          .append("}");
    }
    sb.append("]}");
    return new OutboxRow(
        eventType, "storeql.purchase.landed-cost-applied", c.tenantId(), c.id(), sb.toString());
  }

  /**
   * Goods went back to the supplier (07.8): what inventory-svc deducts, line by line, from the
   * store the order was delivered to. The event id is the return id; a consumer dedupes on it.
   */
  static OutboxRow returnedToVendor(
      UUID tenantId,
      UUID returnId,
      UUID storeId,
      UUID poId,
      UUID supplierId,
      List<VendorReturnLine> lines) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"eventId\":\"")
        .append(returnId)
        .append("\",\"eventType\":\"ReturnedToVendor\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"storeId\":\"")
        .append(storeId)
        .append("\",\"refId\":\"")
        .append(returnId)
        .append("\",\"poId\":\"")
        .append(poId)
        .append("\",\"supplierId\":\"")
        .append(supplierId)
        .append("\",\"lines\":[");
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) sb.append(",");
      VendorReturnLine l = lines.get(i);
      sb.append("{\"variantId\":\"")
          .append(l.variantId())
          .append("\",\"qty\":")
          .append(l.qty().toPlainString())
          .append("}");
    }
    sb.append("]}");
    return new OutboxRow(
        "ReturnedToVendor",
        "storeql.purchase.returned-to-vendor",
        tenantId,
        returnId,
        sb.toString());
  }

  /**
   * A supplier invoice was captured (SJ-D39): the figures a VAT return's box 4 (input VAT
   * reclaimed) and box 7 (net purchases) are made of, by invoice date — the tax point. pricing-svc
   * projects it; nothing else needs to. eventId is the invoice id, so a redelivery is the same
   * event and is recorded once.
   */
  static OutboxRow supplierInvoiceCaptured(UUID tenantId, Domain.SupplierInvoice inv) {
    return new OutboxRow(
        "SupplierInvoiceCaptured",
        "storeql.purchase.supplier-invoice-captured",
        tenantId,
        inv.id(),
        invoiceJson(
            "SupplierInvoiceCaptured",
            inv.id(),
            tenantId,
            inv,
            ",\"status\":\"" + inv.status() + "\""));
  }

  /**
   * A flagged invoice was rejected: its input VAT must leave the return, and its posting has been
   * reversed here. Same topic as capture, so the consumer that projected the invoice reverses it.
   *
   * @param tenantId the owning tenant
   * @param inv the rejected invoice, its figures as captured
   * @param eventId the event's own id — derived from the invoice id so a retried rejection is one
   *     event, not two
   * @return the outbox row
   */
  static OutboxRow supplierInvoiceRejected(
      UUID tenantId, Domain.SupplierInvoice inv, UUID eventId) {
    return new OutboxRow(
        "SupplierInvoiceRejected",
        "storeql.purchase.supplier-invoice-captured",
        tenantId,
        inv.id(),
        invoiceJson("SupplierInvoiceRejected", eventId, tenantId, inv, ""));
  }

  /** The figures pricing-svc projects, the same for a capture and for its reversal. */
  private static String invoiceJson(
      String type, UUID eventId, UUID tenantId, Domain.SupplierInvoice inv, String extra) {
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\""
        + type
        + "\",\"tenantId\":\""
        + tenantId
        + "\",\"invoiceId\":\""
        + inv.id()
        + "\",\"poId\":\""
        + inv.poId()
        + "\",\"supplierId\":\""
        + inv.supplierId()
        + "\",\"invoiceNumber\":\""
        + EventPayload.esc(inv.invoiceNumber())
        + "\",\"invoiceDate\":\""
        + inv.invoiceDate()
        + "\",\"currency\":\""
        + inv.currency()
        + "\",\"netAmount\":"
        + inv.netAmount().toPlainString()
        + ",\"vatAmount\":"
        + inv.vatAmount().toPlainString()
        + ",\"grossAmount\":"
        + inv.grossAmount().toPlainString()
        + extra
        + "}";
  }

  /**
   * A payment run paid a supplier (17.10): the advice notification-svc emails to the supplier's
   * remittance address — which invoices the payment settles, which credit notes it offsets, the
   * total. One per supplier per run; the event id is derived from the two, so a retried payment is
   * one advice, not two.
   *
   * @param items the documents the run settled for this supplier
   * @return the outbox row, keyed by the run so a run's advices are delivered in order
   */
  static OutboxRow supplierRemittanceIssued(
      UUID tenantId,
      com.storeql.purchase.domain.PaymentRuns.PaymentRun run,
      Domain.Supplier supplier,
      java.util.List<com.storeql.purchase.domain.PaymentRuns.Item> items,
      java.math.BigDecimal total) {
    UUID eventId = com.storeql.ids.Ids.derived(run.id(), "remittance:" + supplier.id());
    var lines = jakarta.json.Json.createArrayBuilder();
    for (var item : items) {
      var line =
          jakarta.json.Json.createObjectBuilder()
              .add("type", item.itemType())
              .add("reference", item.reference())
              .add("amount", item.amount());
      if (item.documentDate() != null) line.add("documentDate", item.documentDate().toString());
      lines.add(line);
    }
    var json =
        jakarta.json.Json.createObjectBuilder()
            .add("eventId", eventId.toString())
            .add("eventType", "SupplierRemittanceIssued")
            .add("tenantId", tenantId.toString())
            .add("aggregateId", run.id().toString())
            .add("occurredAt", java.time.Instant.now().toString())
            .add("runId", run.id().toString())
            .add("runReference", run.reference())
            .add("supplierId", supplier.id().toString())
            .add("supplierName", supplier.name())
            .add("paymentDate", run.paymentDate().toString())
            .add("currency", run.currency())
            .add("total", total)
            .add("items", lines);
    if (supplier.remittanceEmail() != null) json.add("remittanceEmail", supplier.remittanceEmail());
    return new OutboxRow(
        "SupplierRemittanceIssued",
        "storeql.purchase.supplier-remittance-issued",
        tenantId,
        run.id(),
        json.build().toString());
  }

  static OutboxRow intercompanyInvoiceRaised(UUID tenantId, UUID invoiceId) {
    return new OutboxRow(
        "IntercompanyInvoiceRaised",
        "storeql.purchase.intercompany-invoice-raised",
        tenantId,
        invoiceId,
        "{\"invoiceId\":\"" + invoiceId + "\"}");
  }
}
